package ca.siva.orchestrator.config;

import ca.siva.orchestrator.actionregistry.ActionRegistry;
import ca.siva.orchestrator.dag.DagRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Custom Spring Boot health indicators exposing the readiness of the two critical
 * startup registries. Without these, the {@code /actuator/health/readiness} probe
 * would return UP even if both registries failed to load — silently swallowing
 * a startup bug that would break every incoming flow.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code /actuator/health/actionRegistry} — DOWN if the action-code registry is empty</li>
 *   <li>{@code /actuator/health/dagRegistry}    — DOWN if no DAG YAML files were loaded</li>
 * </ul>
 *
 * <p>Both are included in the {@code readiness} health group (see application.yml),
 * so Kubernetes will not route traffic to a pod whose registries aren't populated.</p>
 */
@Configuration
@RequiredArgsConstructor
public class RegistryHealthIndicators {

    @Bean("actionRegistry")
    public HealthIndicator actionRegistryHealth(ActionRegistry registry) {
        return () -> {
            if (registry.isReady()) {
                return Health.up()
                        .withDetail("actionCodes", registry.actionCount())
                        .withDetail("dcxActionCodes", registry.dcxActionCount())
                        .build();
            }
            return Health.down()
                    .withDetail("reason", "action registry failed to load or is empty")
                    .withDetail("actionCodes", registry.actionCount())
                    .withDetail("dcxActionCodes", registry.dcxActionCount())
                    .build();
        };
    }

    @Bean("dagRegistry")
    public HealthIndicator dagRegistryHealth(DagRegistry registry) {
        return () -> {
            if (registry.isReady()) {
                return Health.up()
                        .withDetail("dagCount", registry.dagCount())
                        .build();
            }
            return Health.down()
                    .withDetail("reason", "no DAG YAML files were loaded")
                    .build();
        };
    }
}
