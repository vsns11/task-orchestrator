package ca.siva.orchestrator.kafka;

import ca.siva.orchestrator.config.TopicsProperties;
import ca.siva.orchestrator.dto.TaskCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link TaskCommand} messages to the {@code task.command} topic.
 *
 * <p>Sets the Kafka record key to {@code correlationId} to guarantee all events
 * for a single process flow are serialized on the same partition.</p>
 *
 * <p>Custom headers (eventId, messageType, messageName, source, schemaVersion)
 * are set on each message for easy filtering in Offset Explorer and kcat.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskCommandPublisher {

    private final KafkaTemplate<String, TaskCommand> kafka;
    private final TopicsProperties topics;

    /** Publishes an envelope to the task.command topic with partition-key = correlationId. */
    public void publish(TaskCommand taskCommand) {
        String key = taskCommand.getCorrelationId();

        Message<TaskCommand> kafkaMessage = MessageBuilder
                .withPayload(taskCommand)
                .setHeader(KafkaHeaders.TOPIC, topics.taskCommand())
                .setHeader(KafkaHeaders.KEY, key)
                .setHeader("eventId",       taskCommand.getEventId())
                .setHeader("messageType",   taskCommand.getMessageType())
                .setHeader("messageName",   taskCommand.getMessageName())
                .setHeader("source",        taskCommand.getSource())
                .setHeader("schemaVersion", taskCommand.getSchemaVersion())
                .build();

        log.info("PUBLISH {} eventId={} corrId={} source={}",
                taskCommand.getMessageName(), taskCommand.getEventId(), key, taskCommand.getSource());

        kafka.send(kafkaMessage);
    }
}
