package ca.siva.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka topic names used by the orchestrator system.
 *
 * @param taskCommand            the single topic for all orchestrator messages (commands, events, signals, lifecycle)
 * @param notificationManagement TMF-701 notification topic (pamconsumer reads from)
 */
@ConfigurationProperties(prefix = "orchestrator.topics")
public record TopicsProperties(
        String taskCommand,
        String notificationManagement
) {}
