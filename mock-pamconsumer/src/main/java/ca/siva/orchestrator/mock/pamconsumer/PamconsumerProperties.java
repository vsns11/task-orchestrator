package ca.siva.orchestrator.mock.pamconsumer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the mock pamconsumer module.
 *
 * <p>The pamconsumer (real or mocked) is the component that listens on
 * {@code notification.management} for TMF-701 native events and translates
 * them into {@code task.command} messages the orchestrator understands.
 * This property record owns the pamconsumer-side topic name so the
 * orchestrator app can stay strictly single-topic.</p>
 *
 * @param notificationTopic upstream TMF-701 notifications topic the mock
 *                          pamconsumer listens on and DemoFlowTrigger
 *                          publishes to.
 */
@ConfigurationProperties(prefix = "pamconsumer")
public record PamconsumerProperties(
        String notificationTopic
) {}
