package ca.siva.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka topic names used by the orchestrator.
 *
 * <p>The orchestrator listens on exactly one topic — {@code task.command} —
 * for every message shape it consumes (commands, events, signals, lifecycle).
 * Upstream topics such as {@code notification.management} are a pamconsumer
 * concern and are configured in the pamconsumer module, not here.</p>
 *
 * @param taskCommand the single topic for all orchestrator messages
 */
@ConfigurationProperties(prefix = "orchestrator.topics")
public record TopicsProperties(
        String taskCommand
) {}
