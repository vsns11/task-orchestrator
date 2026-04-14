package ca.siva.orchestrator.demo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Waits for all Kafka consumer groups to have their partitions assigned
 * before the app starts accepting HTTP requests.
 *
 * <p>This solves the timing problem with {@code auto-offset-reset: latest} —
 * if a message is published before a consumer group has its partitions assigned,
 * the consumer misses it. By waiting here, we ensure all consumers are ready
 * before the DemoFlowTrigger endpoint can publish.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumerReadinessWaiter {

    private final KafkaListenerEndpointRegistry registry;

    @EventListener(ApplicationReadyEvent.class)
    public void waitForConsumers() {
        log.info("Waiting for Kafka consumer groups to be assigned partitions...");
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            String groupId = container.getGroupId();
            // Wait up to 30 seconds for partitions to be assigned
            for (int i = 0; i < 60; i++) {
                Collection<TopicPartition> assigned = container.getAssignedPartitions();
                if (assigned != null && !assigned.isEmpty()) {
                    log.info("Consumer group '{}' ready — {} partitions assigned",
                            groupId, assigned.size());
                    break;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.info("All consumer groups ready. App is fully operational.");
    }
}
