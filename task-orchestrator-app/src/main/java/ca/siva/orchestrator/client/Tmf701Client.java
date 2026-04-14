package ca.siva.orchestrator.client;

import ca.siva.orchestrator.config.Tmf701Properties;
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
 * <p>Provides two PATCH operations:</p>
 * <ul>
 *   <li>Add a taskFlow reference to the parent processFlow</li>
 *   <li>Update the processFlow lifecycle state (completed / failed)</li>
 * </ul>
 *
 * <p>All operations are best-effort: errors are logged but not propagated,
 * since the orchestrator must not block on downstream HTTP failures.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Tmf701Client {

    private final Tmf701Properties props;
    private final RestClient.Builder builder;
    private final Environment environment;

    private RestClient client;

    /** Initializes the RestClient after the server port is known (needed for test random-port). */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        String baseUrl = resolveBaseUrl();
        this.client = builder.baseUrl(baseUrl).build();
        log.info("TMF-701 client initialized with base URL: {}", baseUrl);
    }

    /** Registers a taskFlow reference on the parent processFlow via PATCH. */
    public void patchProcessFlowAddTaskFlowRef(String processFlowId, String taskFlowId,
                                                String taskFlowHref, String actionName) {
        Map<String, Object> patch = Map.of(
                "relatedEntity", List.of(Map.of(
                        "id",            taskFlowId,
                        "href",          Objects.toString(taskFlowHref, ""),
                        "role",          "TaskFlow",
                        "@type",         "RelatedEntity",
                        "@referredType", "TaskFlow",
                        "name",          Objects.toString(actionName, "")
                ))
        );
        try {
            client.patch()
                    .uri("/processFlow/{id}", processFlowId)
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
                    .uri("/processFlow/{id}", processFlowId)
                    .body(Map.of("state", state))
                    .retrieve()
                    .toBodilessEntity();
            log.info("PATCH processFlow {} state -> {}", processFlowId, state);
        } catch (RestClientException e) {
            log.warn("PATCH processFlow {} state {} failed: {}",
                    processFlowId, state, e.getMessage());
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
