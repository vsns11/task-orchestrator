package ca.siva.orchestrator.client;

import ca.siva.orchestrator.config.Tmf701Properties;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 * <p>All operations are best-effort: errors are logged but not propagated,
 * since the orchestrator must not block on downstream HTTP failures.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Tmf701Client {

    private static final String RELATED_ENTITY_ROLE = "TaskFlow";
    private static final String RELATED_ENTITY_TYPE = "RelatedEntity";
    private static final String STATE_FIELD         = "state";

    private final Tmf701Properties props;
    private final RestClient.Builder builder;
    private final Environment environment;

    private RestClient client;

    /** Initializes the RestClient after the server port is known (needed for test random-port). */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        String baseUrl = resolveBaseUrl();
        this.client = builder.baseUrl(baseUrl).build();
        log.info("TMF-701 client initialized with base URL: {} processFlowPath: {}",
                baseUrl, props.processFlowPath());
    }

    /** Registers a taskFlow reference on the parent processFlow via PATCH. */
    public void patchProcessFlowAddTaskFlowRef(String processFlowId, String taskFlowId,
                                                String taskFlowHref, String actionName) {
        Map<String, Object> patch = Map.of(
                "relatedEntity", List.of(Map.of(
                        "id",            taskFlowId,
                        "href",          Objects.toString(taskFlowHref, ""),
                        "role",          RELATED_ENTITY_ROLE,
                        "@type",         RELATED_ENTITY_TYPE,
                        "@referredType", RELATED_ENTITY_ROLE,
                        "name",          Objects.toString(actionName, "")
                ))
        );
        try {
            client.patch()
                    .uri(props.processFlowPath(), processFlowId)
                    .body(patch)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("PATCH processFlow {} added taskFlow ref {}", processFlowId, taskFlowId);
        } catch (RestClientException e) {
            log.warn("PATCH processFlow {} (add ref {}) failed: {}",
                    processFlowId, taskFlowId, e.getMessage());
        }
    }

    /** Updates the processFlow lifecycle state (e.g. "completed", "failed"). */
    public void patchProcessFlowState(String processFlowId, String state) {
        try {
            client.patch()
                    .uri(props.processFlowPath(), processFlowId)
                    .body(Map.of(STATE_FIELD, state))
                    .retrieve()
                    .toBodilessEntity();
            log.info("PATCH processFlow {} state -> {}", processFlowId, state);
        } catch (RestClientException e) {
            log.warn("PATCH processFlow {} state {} failed: {}",
                    processFlowId, state, e.getMessage());
        }
    }

    /**
     * Fetches the processFlow object from TMF-701 by ID.
     * Used when promoting to the next batch — avoids keeping the processFlow in memory.
     *
     * @param processFlowId the processFlow UUID
     * @return the processFlow object, or empty if not found or call fails
     */
    public Optional<ProcessFlow> getProcessFlow(String processFlowId) {
        try {
            ProcessFlow processFlow = client.get()
                    .uri(props.processFlowPath(), processFlowId)
                    .retrieve()
                    .body(ProcessFlow.class);
            return Optional.ofNullable(processFlow);
        } catch (RestClientException e) {
            log.warn("GET processFlow {} failed: {}", processFlowId, e.getMessage());
            return Optional.empty();
        }
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
