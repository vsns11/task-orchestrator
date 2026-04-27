package ca.siva.orchestrator.client;

import ca.siva.orchestrator.config.AppConfig;
import ca.siva.orchestrator.config.AppConfig.TransientClientError;
import ca.siva.orchestrator.config.FidCredentialsProperties;
import ca.siva.orchestrator.config.HttpClientProperties;
import ca.siva.orchestrator.config.Tmf701Properties;
import ca.siva.orchestrator.domain.ProcessFlowStateType;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import ca.siva.orchestrator.dto.tmf.ProcessFlow.Characteristic;
import ca.siva.orchestrator.dto.tmf.ProcessFlow.RelatedEntity;
import ca.siva.orchestrator.dto.tmf.TaskFlowRef;
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
     * Default {@code @referredType} stamped on every {@link TaskFlowRef}
     * appended to {@code processFlow.taskFlow[]}. Mirrors the legacy Bonita
     * {@code buildTaskFlow(tf.getId(), atReferredType, tf.getHref(), ...)}
     * convention where {@code atReferredType} carries the action name —
     * {@link #patchProcessFlowAddTaskFlowRef} uses the action name when
     * present and falls back to this constant when the caller passes blank.
     */
    private static final String TASKFLOW_REFERRED_TYPE_DEFAULT = "TaskFlow";

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
     * Registers a taskFlow reference on the parent processFlow — populates
     * BOTH {@code relatedEntity[]} (untyped TMF entity ref) AND
     * {@code taskFlow[]} (typed TMF-701 {@link TaskFlowRef} array) on the
     * same merge-patch, mirroring the legacy Bonita
     * {@code enrichProcessFlow} contract:
     * <pre>
     *   processFlow.addRelatedEntityItem( buildRelatedEntity(id, href, ...) );
     *   processFlow.addTaskFlowItem     ( buildTaskFlow(tf.getId(), atReferredType, tf.getHref(), ...) );
     * </pre>
     *
     * <p>Both ids/hrefs are required — the method fails closed (warns and
     * returns) when either is missing, mirroring the upstream
     * {@code enrichProcessFlowWithRelatedEntity} validation rather than
     * PATCHing a half-populated entity.</p>
     *
     * <p><b>Append semantics via GET-then-PATCH.</b> TMF-630 mandates
     * JSON Merge Patch (RFC 7396) for PATCH bodies, which REPLACES arrays
     * rather than appending to them. To preserve previously-registered
     * refs we:</p>
     * <ol>
     *   <li>GET the current processFlow once,</li>
     *   <li>copy both {@code relatedEntity} and {@code taskFlow} lists,</li>
     *   <li>append the new entries (skipping duplicates by {@code id} on
     *       both arrays independently),</li>
     *   <li>PATCH with both merged lists in a single request.</li>
     * </ol>
     *
     * <p>If the GET fails we fall back to PATCHing just the new entries —
     * the orchestration loop must not stall on TMF-701 transport hiccups
     * and losing previously-attached refs on a rare GET failure is
     * preferable to losing this one.</p>
     *
     * <p><b>Concurrency caveat:</b> tasks running in parallel within the
     * same batch race on this read-modify-write. TMF-701 has no ETag /
     * If-Match, so a lost update is possible when two tasks finish inside
     * the same narrow window. Tolerated today; the long-term fix is to
     * switch to RFC 6902 JSON Patch ({@code application/json-patch+json})
     * with explicit {@code {"op":"add","path":"/relatedEntity/-"}} +
     * {@code {"op":"add","path":"/taskFlow/-"}} operations once the server
     * supports it.</p>
     *
     * @param actionName the action's logical name; used for both the
     *                   {@code relatedEntity.name} slot and the
     *                   {@code taskFlow.@referredType} slot. When blank we
     *                   stamp {@link #TASKFLOW_REFERRED_TYPE_DEFAULT} on the
     *                   taskFlow ref so the {@code @referredType} field is
     *                   always populated.
     */
    public void patchProcessFlowAddTaskFlowRef(String processFlowId, String taskFlowId,
                                                String taskFlowHref, String actionName) {
        if (taskFlowId == null || taskFlowId.isBlank()
                || taskFlowHref == null || taskFlowHref.isBlank()) {
            log.warn("Patch processFlow {} skipped — relatedEntity requires both id and href (id={}, href={}, actionName={})",
                    processFlowId, taskFlowId, taskFlowHref, actionName);
            return;
        }

        RelatedEntity newRelatedEntity = RelatedEntity.builder()
                .id(taskFlowId)
                .href(taskFlowHref)
                .role(RELATED_ENTITY_ROLE)
                .type(RELATED_ENTITY_TYPE)
                .referredType(RELATED_ENTITY_ROLE)
                .name(Objects.toString(actionName, ""))
                .build();

        String referredType = (actionName != null && !actionName.isBlank())
                ? actionName : TASKFLOW_REFERRED_TYPE_DEFAULT;
        TaskFlowRef newTaskFlowRef = TaskFlowRef.builder()
                .id(taskFlowId)
                .href(taskFlowHref)
                .referredType(referredType)
                .build();

        // ONE GET — feed both merge lists from the same snapshot so we don't
        // double the read cost when populating both arrays.
        Optional<ProcessFlow> snapshot = getProcessFlow(processFlowId);

        List<RelatedEntity> mergedRelatedEntities = new ArrayList<>(
                snapshot.map(ProcessFlow::getRelatedEntity).orElse(List.of()));
        boolean relatedAlreadyPresent = mergedRelatedEntities.stream()
                .anyMatch(re -> re != null && taskFlowId.equals(re.getId()));
        if (!relatedAlreadyPresent) {
            mergedRelatedEntities.add(newRelatedEntity);
        }

        List<TaskFlowRef> mergedTaskFlowRefs = new ArrayList<>(
                snapshot.map(ProcessFlow::getTaskFlow).orElse(List.of()));
        boolean taskFlowAlreadyPresent = mergedTaskFlowRefs.stream()
                .anyMatch(tf -> tf != null && taskFlowId.equals(tf.getId()));
        if (!taskFlowAlreadyPresent) {
            mergedTaskFlowRefs.add(newTaskFlowRef);
        }

        // @JsonInclude(NON_NULL) on ProcessFlow/RelatedEntity/TaskFlowRef
        // ensures only relatedEntity + taskFlow arrays are sent on the wire —
        // all other processFlow fields stay as the server has them.
        ProcessFlow patch = new ProcessFlow();
        patch.setRelatedEntity(mergedRelatedEntities);
        patch.setTaskFlow(mergedTaskFlowRefs);

        doPatch(processFlowId, patch,
                "Patch processFlow " + processFlowId
                        + " (merge relatedEntity+taskFlow id=" + taskFlowId
                        + " href=" + taskFlowHref
                        + " referredType=" + referredType
                        + " relatedExistingCount=" + (mergedRelatedEntities.size() - (relatedAlreadyPresent ? 0 : 1))
                        + " taskFlowExistingCount=" + (mergedTaskFlowRefs.size() - (taskFlowAlreadyPresent ? 0 : 1))
                        + " relatedAlreadyPresent=" + relatedAlreadyPresent
                        + " taskFlowAlreadyPresent=" + taskFlowAlreadyPresent + ")");
    }

    /**
     * Updates the processFlow lifecycle state. Takes the typed
     * {@link ProcessFlowStateType} so callers cannot accidentally send
     * an unrecognised wire string — the only place the literal value
     * is materialised is at the wire boundary inside this method.
     */
    public void patchProcessFlowState(String processFlowId, ProcessFlowStateType state) {
        if (state == null) {
            log.warn("Patch processFlow {} state skipped — state is required", processFlowId);
            return;
        }
        ProcessFlow patch = new ProcessFlow();
        patch.setState(state.getValue());
        doPatch(processFlowId, patch,
                "Patch processFlow " + processFlowId + " state=" + state.getValue());
    }

    /**
     * Sends the legacy-shape "final state" PATCH used at flow completion / failure.
     *
     * <p>Called once per flow when the orchestrator decides the flow has
     * reached its end — either every batch has closed cleanly (success) or
     * a kickout / non-retryable failure has stopped further promotion
     * (failure). The same body shape applies to both outcomes; only the
     * {@code state} value differs ({@code completed} vs {@code failed}).</p>
     *
     * <p>Body shape (mirrors the upstream Bonita
     * {@code processFlowEntryAction}):</p>
     * <ul>
     *   <li>{@code state} — final lifecycle ({@code completed} or {@code failed})</li>
     *   <li>{@code characteristic} — preserved server characteristics with the
     *       {@code processStatus} entry upserted (always) and the
     *       {@code statusChangeReason} entry upserted (only when non-null)</li>
     * </ul>
     *
     * <p>{@code relatedEntity} and {@code taskFlow} are intentionally NOT
     * included: JSON Merge Patch (RFC 7396) leaves unspecified fields as-is,
     * so the entity / taskFlow refs added by earlier
     * {@link #patchProcessFlowAddTaskFlowRef} calls survive untouched. The
     * {@code characteristic} list, in contrast, IS replaced by merge-patch
     * semantics — hence the GET-first read-modify-write here, same pattern
     * as {@link #patchProcessFlowAddTaskFlowRef}.</p>
     *
     * <p><b>Idempotency.</b> Re-running with the same arguments produces a
     * body byte-identical to the first run because we upsert by
     * characteristic name (case-insensitive): any pre-existing
     * {@code processStatus} / {@code statusChangeReason} entries are removed
     * before the new ones are appended, so retries / redeliveries don't grow
     * the list.</p>
     *
     * @param state               final lifecycle state ({@link ProcessFlowStateType#COMPLETED}
     *                            or {@link ProcessFlowStateType#FAILED}) — required
     * @param processStatus       value for the {@code processStatus}
     *                            characteristic; always sent when non-null
     * @param statusChangeReason  value for the {@code statusChangeReason}
     *                            characteristic; sent only when non-null/non-blank
     */
    public void patchProcessFlowFinalState(String processFlowId, ProcessFlowStateType state,
                                            String processStatus, String statusChangeReason) {
        if (state == null) {
            log.warn("Patch processFlow {} final-state skipped — state is required", processFlowId);
            return;
        }
        String stateValue = state.getValue();

        // Read existing characteristics so merge-patch doesn't clobber siblings
        // (e.g. peinNumber, SDT TX id) the server is already storing. Falling
        // back to an empty list when GET fails or the resource has none keeps
        // the final-state PATCH from stalling on transport hiccups — losing
        // unrelated characteristics on a rare GET failure is preferable to
        // never marking the flow completed.
        List<Characteristic> merged = new ArrayList<>(
                getProcessFlow(processFlowId)
                                .map(ProcessFlow::getCharacteristic)
                        .orElse(List.of()));

        // Upsert by name (case-insensitive — TMF-630 characteristic names are
        // not formally case-sensitive). Removes any prior processStatus /
        // statusChangeReason entry so the re-issued final-state PATCH (retry,
        // redelivery) doesn't grow the list across runs.
        merged.removeIf(c -> c != null && c.getName() != null &&
                ("processStatus".equalsIgnoreCase(c.getName())
                        || "statusChangeReason".equalsIgnoreCase(c.getName())));

        if (processStatus != null) {
            merged.add(buildCharacteristic("processStatus", processStatus));
        }
        if (statusChangeReason != null && !statusChangeReason.isBlank()) {
            merged.add(buildCharacteristic("statusChangeReason", statusChangeReason));
        }

        // Mirror the legacy Bonita processFlowEntryAction body: id is echoed
        // in the payload alongside state + characteristic so consumers that
        // diff the request body against the stored resource see the same
        // shape they did under the old workflow. The URL already targets the
        // resource by id; including it in the body is harmless under
        // merge-patch semantics (id is the identity field, not a mutation).
        ProcessFlow patch = new ProcessFlow();
        patch.setId(processFlowId);
        patch.setState(stateValue);
        patch.setCharacteristic(merged);

        doPatch(processFlowId, patch,
                "Patch processFlow " + processFlowId
                        + " final-state state=" + stateValue
                        + " processStatus=" + processStatus
                        + " statusChangeReason=" + statusChangeReason);
    }

    /**
     * Fetches the processFlow object from TMF-701 by ID.
     * Used when promoting to the next batch — avoids keeping the processFlow in memory.
     *
     * <p>Logs the full deserialized processFlow at INFO on a successful read
     * so operators can validate end-to-end that the per-action ref PATCHes
     * (relatedEntity[] / taskFlow[]) and the final-state PATCH (state +
     * processStatus / statusChangeReason characteristics) actually landed on
     * the server. The PATCH side is already logged in {@link #doPatch}; this
     * read-side log closes the loop.</p>
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
            logProcessFlowPayload(processFlowId, processFlow);
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
     * Serializes the just-fetched processFlow back to JSON and logs it at
     * INFO. Best-effort — a serialization failure here must NOT break the
     * GET (the parsed {@link ProcessFlow} is already in hand and the caller
     * will use it regardless), so the catch downgrades to a one-line WARN.
     *
     * <p>The re-serialization uses the same {@link ObjectMapper} that
     * Jackson used to deserialize, so the logged body's field names and
     * {@code @type} / {@code @baseType} / {@code @referredType} naming are
     * byte-faithful to the wire shape — exactly what an operator needs when
     * cross-checking against the request body logged in {@link #doPatch}.</p>
     */
    private void logProcessFlowPayload(String processFlowId, ProcessFlow processFlow) {
        if (processFlow == null) {
            log.info("TMF-701 Get processFlowId={} payload=<null body>", processFlowId);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(processFlow);
            log.info("TMF-701 Get processFlowId={} payload={}", processFlowId, json);
        } catch (JsonProcessingException e) {
            log.warn("TMF-701 Get processFlowId={} payload-log-skipped (serialization failed) exception={}",
                    processFlowId, e.toString());
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
                // RetryContext.getRetryCount() is the count of PRIOR failures,
                // which equals the retry number about to run: 1, 2, 3.
                // No +1 offset — that would log "retry 4" on the 3rd retry and
                // make the budget look bigger than it is.
                log.warn("TMF-701 {} retry {} after {}",
                        label, ctx.getRetryCount(),
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

    /** TMF-630 string-typed characteristic literals. */
    private static final String CHAR_TYPE_STRING = "string";

    /**
     * Builds a TMF-630 string-typed {@link Characteristic} via setter mutation.
     * Centralizes the {@code valueType="string"} convention so every caller
     * produces a wire-identical shape — and so a future migration to a
     * different value-type (e.g. structured) is a one-line change.
     *
     * <p>The {@code @type} and {@code @baseType} fields are populated to
     * mirror the legacy Bonita {@code Characteristic.setAtType(STRING) /
     * setAtBaseType(STRING)} pair, so the wire payload is byte-identical to
     * what {@code processFlowEntryAction} produced upstream.</p>
     */
    private static Characteristic buildCharacteristic(String name, String value) {
        Characteristic c = new Characteristic();
        c.setName(name);
        c.setValue(value);
        c.setValueType(CHAR_TYPE_STRING);
        c.setType(CHAR_TYPE_STRING);
        c.setBaseType(CHAR_TYPE_STRING);
        return c;
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
