package ca.siva.orchestrator.kafka;

import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.service.OrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for the {@code task.command} topic.
 *
 * <p>Always acknowledges the message — even on error — so the consumer never
 * gets stuck retrying a poison message. Errors are logged and the consumer
 * moves on to the next message.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskCommandListener {

    private final OrchestratorService orchestrator;

    @KafkaListener(
            topics = "${orchestrator.topics.task-command}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMessage(TaskCommand message, Acknowledgment ack) {
        try {
            orchestrator.handle(message);
        } catch (Exception e) {
            // Log and move on — never re-throw, never block the consumer
            log.error("Error handling envelope eventId={}: {}",
                    message != null ? message.getEventId() : "null", e.getMessage(), e);
        } finally {
            // Always ack so the consumer continues to the next message
            ack.acknowledge();
        }
    }
}
