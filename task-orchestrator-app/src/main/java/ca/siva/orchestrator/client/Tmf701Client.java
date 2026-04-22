package ca.siva.orchestrator.client;

import ca.siva.orchestrator.config.AppConfig;
import ca.siva.orchestrator.config.AppConfig.TransientClientError;
import ca.siva.orchestrator.config.FidCredentialsProperties;
import ca.siva.orchestrator.config.HttpClientProperties;
import ca.siva.orchestrator.config.Tmf701Properties;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * HTTP client for the TMF-701 processFlow API.
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>PATCH — add a taskFlow reference to the parent processFlow</li>
 *   <li>PATCH — update the processFlow lifecycle state (completed / failed)</li>
 *   <li>GET — fetch the processFlow object when promoting to the next batch</li>
 * </ul>
 *
 * <p>The URI template for the processFlow resource is loaded from
 * {@link Tmf701Properties#processFlowPath()} — do not hardcode it here.</p>
 *
 * <p>Observability: every PATCH logs the full URL and JSON request body at
 * INFO before the call, and a detailed ERROR (URL + status + response body +
 * request body) on any failure, so silent "swallowed exception" outages are
 * impossible. Errors are still caught so the orchestration loop keeps
 * running — but at least they're now diagnosable from logs alone.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Tmf701Client {

    private static final String RELATED_ENTITY_ROLE = "TaskFlow";
    private static final String RELATED_ENTITY_TYPE = "RelatedEntity";

    /**
     * TMF-630 (§ REST Design Guidelines) mandates JSON Merge Patch (RFC 7396)
     * for PATCH bodies on TMF APIs. Sending under this media type makes the
     * intent explicit to the server and avoids any server-side ambiguity
     * between merge-patch and full-replace semantics.
     *
     * <p>Future migration: when the server adds RFC 6902 support, switch to
     * {@code application/json-patch+json} and replace the read-modify-write
     * in {@link #patchProcessFlowAddTaskFlowRef} with an
     * {@code {"op":"add","path":"/relatedEntity/-"}} operation — which is
     * append-native and race-free without needing an ETag.</p>
     */
    private static final MediaType MERGE_PATCH_JSON = MediaType.valueOf("application/merge-patch+json");

    /**
     * Explicit {@code Accept} header for GET. TMF-701 servers sometimes default
     * to XML if no Accept is sent; pinning to JSON with an explicit charset
     * removes any server-side content-negotiation ambiguity and keeps log
     * diagnostics consistent.
     */
    private static final MediaType JSON_UTF8 = MediaType.valueOf("application/json;charset=utf-8");

    private final Tmf701Properties         props;
    private final FidCredentialsProperties fidCreds;
    private final RestClient.Builder       builder;
    private final Environment              environment;
    private final ObjectMapper             objectMapper;
    private final RetryTemplate            httpCallRetryTemplate;
    private final HttpClientProperties     httpProps;

    private RestClient client;
    /** Cached base URL — resolved at ApplicationReadyEvent, reused in log messages. */
    private String     resolvedBaseUrl;

    /**
     * Initializes the RestClient after the server port is known (needed for
     * test random-port). Wrapped in try/catch so a misconfigured base URL or
     * bad credential property cannot prevent Spring from completing startup —
     * we log the exact failure instead and leave {@code client} null so
     * subsequent PATCH/GET calls fail loudly with a pinpointed root cause
     * rather than a generic NPE deep in the call stack.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            this.resolvedBaseUrl = resolveBaseUrl();
            RestClient.Builder restClientBuilder = builder.baseUrl(resolvedBaseUrl);

            // Shared FID credentials resolved by Spring from application-orchestration.yml:
            //   orchestrator.fid.username = ${FID_USERNAME:}
            //   orchestrator.fid.password = ${FID_PASSWORD:}
            // Same pair is reused by ActionRegistry. When either is blank we skip the
            // Authorization header entirely — useful for local-dev against the
            // in-process mocks and for integration tests.
            String authHeader = BasicAuthSupport.header(fidCreds.username(), fidCreds.password());
            if (authHeader != null) {
                restClientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, authHeader);
            }

            this.client = restClientBuilder.build();
            log.info("TMF-701 client initialized: baseUrl={} processFlowPath={} basicAuth={}",
                    resolvedBaseUrl, props.processFlowPath(),
                    authHeader != null ? "enabled (user=" + fidCreds.username() + ")" : "disabled");
        } catch (RuntimeException e) {
            log.error("TMF-701 client initialization failed — client is unavailable: exception={}",
                    e.toString(), e);
        }
    }

    /**
     * Registers a taskFlow reference on the parent processFlow.
     *
     * <p>The TMF-701 {@code relatedEntity} element requires both {@code id}
     * and {@code href} — the pair together resolves the referenced resource.
     * This method fails closed (warns and returns) when either is missing,
     * mirroring the upstream {@code ActionBuilder.enrichProcessFlowWithRelatedEntity}
     * validation rather than PATCHing a half-populated entity.</p>
     *
     * <p><b>Append semantics via GET-then-PATCH.</b> TMF-630 mandates
     * JSON Merge Patch (RFC 7396) for PATCH bodies, which REPLACES arrays
     * rather than appending to them. To preserve previously-registered
     * taskFlow references we:</p>
     * <ol>
     *   <li>GET the current processFlow,</li>
     *   <li>copy its {@code relatedEntity} list (if any),</li>
     *   <li>append the new {@link ProcessFlow.RelatedEntity}, skipping
     *       duplicates by {@code id},</li>
     *   <li>PATCH with the merged list.</li>
     * </ol>
     *
     * <p>If the GET fails we fall back to PATCHing just the new entry —
     * the orchestration loop must not stall on TMF-701 transport hiccups
     * and losing previously-attached refs on a rare GET failure is
     * preferable to losing this one.</p>
     *
     * <p><b>Concurrency caveat:</b> tasks running in parallel within the
     * same batch race on this read-modify-write. TMF-701 has no ETag /
     * If-Match, so a lost update is possible when two tasks finish inside
     * the same narrow window. Tolerated today; the long-term fix is to
     * switch to RFC 6902 JSON Patch ({@code application/json-patch+json})
     * with an explicit {@code {"op":"add","path":"/relatedEntity/-"}}
     * operation once the server supports it.</p>
     */
    public void patchProcessFlowAddTaskFlowRef(String processFlowId, String taskFlowId,
                                                String taskFlowHref, String actionName) {
        if (taskFlowId == null || taskFlowId.isBlank()
                || taskFlowHref == null || taskFlowHref.isBlank()) {
            log.warn("Patch processFlow {} skipped — relatedEntity requires both id and href (id={}, href={}, actionName={})",
                    processFlowId, taskFlowId, taskFlowHref, actionName);
            return;
        }

        ProcessFlow.RelatedEntity newEntry = ProcessFlow.RelatedEntity.builder()
                .id(taskFlowId)
                .href(taskFlowHref)
                .role(RELATED_ENTITY_ROLE)
                .type(RELATED_ENTITY_TYPE)
                .referredType(RELATED_ENTITY_ROLE)
                .name(Objects.toString(actionName, ""))
                .build();

        // Start from the server's current list so the merge-patch reassignment
        // preserves siblings; fall back to an empty list if GET fails or there
        // are none attached yet.
        List<ProcessFlow.RelatedEntity> merged = new ArrayList<>(
                getProcessFlow(processFlowId)
                        .map(ProcessFlow::getRelatedEntity)
                        .orElse(List.of()));

        // Idempotency: if this taskFlowId is already attached (retry / redelivery
        // of the same task start event), don't append a duplicate.
        boolean alreadyPresent = merged.stream()
                .anyMatch(re -> re != null && taskFlowId.equals(re.getId()));
        if (!alreadyPresent) {
            merged.add(newEntry);
        }

        // @JsonInclude(NON_NULL) on ProcessFlow/RelatedEntity ensures only
        // relatedEntity is sent on the wire — all other processFlow fields
        // stay as the server has them.
        ProcessFlow patch = new ProcessFlow();
        patch.setRelatedEntity(merged);

        doPatch(processFlowId, patch,
                "Patch processFlow " + processFlowId
                        + " (merge relatedEntity id=" + taskFlowId
                        + " href=" + taskFlowHref
                        + " existingCount=" + (merged.size() - (alreadyPresent ? 0 : 1))
                        + " alreadyPresent=" + alreadyPresent + ")");
    }

    /** Updates the processFlow lifecycle state (e.g. "completed", "failed"). */
    public void patchProcessFlowState(String processFlowId, String state) {
        ProcessFlow patch = new ProcessFlow();
        patch.setState(state);
        doPatch(processFlowId, patch,
                "Patch processFlow " + processFlowId + " state=" + state);
    }

    /**
     * Fetches the processFlow object from TMF-701 by ID.
     * Used when promoting to the next batch — avoids keeping the processFlow in memory.
     *
     * @param processFlowId the processFlow UUID
     * @return the processFlow object, or empty if not found or call fails
     */
    public Optional<ProcessFlow> getProcessFlow(String processFlowId) {
        String url = fullUrl(processFlowId);
        log.info("TMF-701 Get url={}", url);
        try {
            ProcessFlow processFlow = executeWithRetry("Get " + processFlowId, () ->
                    client.get()
                            .uri(props.processFlowPath(), processFlowId)
                            .accept(JSON_UTF8)
                            .retrieve()
                            .body(ProcessFlow.class));
            return Optional.ofNullable(processFlow);
        } catch (RestClientResponseException e) {
            log.error("TMF-701 Get failed url={} status={} responseBody={} exception={}",
                    url, e.getStatusCode(), e.getResponseBodyAsString(), e.toString(), e);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.error("TMF-701 Get transport error url={} exception={}", url, e.toString(), e);
            return Optional.empty();
        }
    }

    /**
     * Shared PATCH execution with verbose URL + payload logging and detailed
     * error diagnostics. We still swallow exceptions (orchestration must not
     * block on TMF-701 hiccups), but the log now carries every piece of
     * evidence needed to diagnose the failure without extra instrumentation:
     * the exact URL, the serialized request body, the response status and
     * body, plus the full exception stack.
     *
     * @param processFlowId path parameter (inserted into {@link Tmf701Properties#processFlowPath()})
     * @param payload       request body (serialized to JSON for logging)
     * @param label         human-readable description of the call for log messages
     */
    private void doPatch(String processFlowId, ProcessFlow payload, String label) {
        String url = fullUrl(processFlowId);
        String bodyJson;
        try {
            // Serialize ONCE up front. Using the pre-serialized string for both
            // the request body and the log line guarantees what we log is byte-
            // identical to what we send on the wire — no risk of divergence
            // from Jackson re-serializing at different points.
            bodyJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("TMF-701 {} serialization failed url={} exception={}",
                    label, url, e.toString(), e);
            return;
        }
        log.info("TMF-701 {} url={} body={}", label, url, bodyJson);
        try {
            executeWithRetry(label, () -> {
                client.patch()
                        .uri(props.processFlowPath(), processFlowId)
                        .contentType(MERGE_PATCH_JSON)
                        .accept(JSON_UTF8)
                        .body(bodyJson)
                        .retrieve()
                        .toBodilessEntity();
                return null;
            });
            log.info("TMF-701 {} ok", label);
        } catch (RestClientResponseException e) {
            log.error("TMF-701 {} failed url={} status={} responseBody={} requestBody={} exception={}",
                    label, url, e.getStatusCode(), e.getResponseBodyAsString(), bodyJson, e.toString(), e);
        } catch (RuntimeException e) {
            // Transport-level (connect/read timeout) failures land here AFTER
            // the retry budget is exhausted. Orchestration stays alive either way.
            log.error("TMF-701 {} transport error url={} requestBody={} exception={}",
                    label, url, bodyJson, e.toString(), e);
        }
    }

    /**
     * Executes an HTTP call under the shared {@link RetryTemplate} policy
     * (see {@link AppConfig#httpCallRetryTemplate()}). Transient 4xx responses
     * (408 Request Timeout, 429 Too Many Requests) are translated to
     * {@link TransientClientError} so the retry policy picks them up alongside
     * 5xx and connect/read timeouts. Non-retryable errors (other 4xx) escape
     * immediately and are handled by the caller.
     *
     * @param label  short tag used in retry-warning log lines
     * @param call   wire call — must be idempotent by design (GET is;
     *               merge-patch PATCH is because we send the same merged body
     *               and our idempotency key on the server is the taskFlowId)
     * @return whatever {@code call} returns, or the throwable propagates once
     *         the retry budget is exhausted
     */
    private <T> T executeWithRetry(String label, Supplier<T> call) {
        return httpCallRetryTemplate.execute(ctx -> {
            if (ctx.getRetryCount() > 0) {
                log.warn("TMF-701 {} retry attempt {} after {}",
                        label, ctx.getRetryCount() + 1,
                        ctx.getLastThrowable() == null ? "<no prior error>" : ctx.getLastThrowable().toString());
            }
            try {
                return call.get();
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                // Any status the operator has declared retryable (default set
                // 408/429/500/502/503/504; fully configurable via
                // orchestrator.http.retryable-statuses) is translated into the
                // retry-friendly marker. 5xx instances can also reach the
                // retry template directly as HttpServerErrorException — both
                // paths are already in the whitelist, so behaviour is
                // belt-and-suspenders.
                if (httpProps.isRetryableStatus(status)) {
                    throw new TransientClientError(
                            "Retryable status=" + status, e);
                }
                throw e;
            }
        });
    }

    /** Resolves the full URL with {id} substituted — for log output. */
    private String fullUrl(String processFlowId) {
        String base = resolvedBaseUrl != null ? resolvedBaseUrl : props.baseUrl();
        return base + props.processFlowPath().replace("{id}", processFlowId);
    }

    /**
     * Resolves the base URL, replacing the configured port with {@code local.server.port}
     * when running in a random-port test environment.
     */
    private String resolveBaseUrl() {
        return Optional.ofNullable(environment.getProperty("local.server.port"))
                .map(port -> props.baseUrl().replaceFirst(":\\d+/", ":" + port + "/"))
                .orElse(props.baseUrl());
    }
}
