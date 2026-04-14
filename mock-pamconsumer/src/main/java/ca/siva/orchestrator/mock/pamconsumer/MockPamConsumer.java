package ca.siva.orchestrator.mock.pamconsumer;

import ca.siva.orchestrator.domain.MessageNames;
import ca.siva.orchestrator.domain.MessageType;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.TaskCommand.Action;
import ca.siva.orchestrator.dto.TaskCommand.Batch;
import ca.siva.orchestrator.dto.TaskCommand.Downstream;
import ca.siva.orchestrator.dto.TaskCommand.Inputs;
import ca.siva.orchestrator.dto.TaskCommand.Trigger;
import ca.siva.orchestrator.dto.tmf.NotificationEvent;
import ca.siva.orchestrator.kafka.TaskCommandFactory;
import ca.siva.orchestrator.kafka.TaskCommandPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-process mock of the pamconsumer service.
 *
 * <h3>Job 1 — Flow initiation (notification.management listener):</h3>
 * <p>Reads real TMF-701 {@code processFlow} events ({@code @type = "processFlow"})
 * from the {@code notification.management} topic. Extracts {@code processFlowSpecification}
 * as the dagKey. Publishes {@code processFlow.initiated} TaskCommand to {@code task.command}.</p>
 *
 * <h3>Job 2 — Async signal relay (called by MockTaskRunner):</h3>
 * <p>When MockTaskRunner encounters an ASYNC task, it calls
 * {@link #publishSignalAfterDelay} which simulates the external system completing
 * and pamconsumer publishing {@code task.signal} to {@code task.command}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockPamConsumer {

    private final TaskCommandPublisher publisher;
    private final TaskCommandFactory taskCommandFactory;
    private final ca.siva.orchestrator.kafka.TaskEventsPublisher taskEventsPublisher;
    private final ObjectMapper objectMapper;

    // ---- Job 1: notification.management → processFlow.initiated ----

    /**
     * Listens on {@code notification.management} for TMF-701 native events.
     * Uses the {@code notificationListenerContainerFactory} which deserializes
     * into {@link NotificationEvent} (NOT TaskCommand).
     */
    @KafkaListener(
            topics = "${orchestrator.topics.notification-management}",
            groupId = "mock-pamconsumer",
            containerFactory = "notificationListenerContainerFactory"
    )
    public void onNotificationEvent(NotificationEvent event, Acknowledgment ack) {
        try {
            if (event == null) return;

            if (event.isProcessFlowCreated()) {
                handleProcessFlowCreated(event);
            } else if (event.isAsyncResponse()) {
                handleAsyncResponse(event);
            } else {
                log.debug("[MOCK pamconsumer] Ignoring event @type={}", event.getType());
            }
        } catch (Exception e) {
            log.error("[MOCK pamconsumer] Error processing notification event: {}", e.getMessage(), e);
        } finally {
            ack.acknowledge();
        }
    }

    /**
     * Transforms a real TMF-701 processFlow event into a processFlow.initiated TaskCommand.
     *
     * <p>Extracts:</p>
     * <ul>
     *   <li>{@code id} → correlationId</li>
     *   <li>{@code processFlowSpecification} → dagKey</li>
     *   <li>entire processFlow object → inputs.processFlow (as a Map)</li>
     * </ul>
     */
    private void handleProcessFlowCreated(NotificationEvent event) {
        String processFlowId = event.getId();
        String dagKey = event.getProcessFlowSpecification();

        if (processFlowId == null || dagKey == null) {
            log.warn("[MOCK pamconsumer] processFlow missing id or processFlowSpecification - skipping");
            return;
        }

        // Convert the full processFlow event into a Map for the TaskCommand inputs
        @SuppressWarnings("unchecked")
        Map<String, Object> processFlowMap = objectMapper.convertValue(event, Map.class);

        // Build processFlow.initiated and publish to task.command
        TaskCommand initiated = taskCommandFactory.buildBase(
                processFlowId, MessageNames.PROCESS_FLOW_INITIATED,
                MessageType.EVENT, Sources.PAMCONSUMER);
        initiated.setDagKey(dagKey);
        initiated.setInputs(TaskCommand.Inputs.builder()
                .processFlow(processFlowMap)
                .build());

        publisher.publish(initiated);

        // Publish INITIATED lifecycle event to task.events topic
        taskEventsPublisher.publishInitiated(processFlowId, dagKey);

        log.info("[MOCK pamconsumer] processFlow.created → processFlow.initiated dagKey={} corrId={}",
                dagKey, processFlowId);
    }

    /**
     * Handles a TaskFinalAsyncResponseSend event from the notification.management topic.
     *
     * <p>In production, pamconsumer would look up the waiting_task table by
     * {@code event.id} (the downstreamTransactionId) to find the task and flow. Here we
     * extract the correlationId directly from the event and publish a task.signal.</p>
     */
    private void handleAsyncResponse(NotificationEvent event) {
        if (event.getEvent() == null || event.getEvent().getId() == null) {
            log.warn("[MOCK pamconsumer] AsyncResponse missing event.id - skipping");
            return;
        }

        String downstreamTransactionId = event.getEvent().getId();
        String downstreamHref = event.getEvent().getHref();
        String reportingSystemId = Optional.ofNullable(event.getReportingSystem())
                .map(r -> r.getId()).orElse("UNKNOWN");

        // Build task.signal — note: in production, pamconsumer would look up
        // the correlationId and task info from the waiting_task table.
        // Here we use the correlationId from the event itself.
        TaskCommand signal = taskCommandFactory.buildBase(
                event.getCorrelationId(), MessageNames.TASK_SIGNAL,
                MessageType.SIGNAL, Sources.PAMCONSUMER);

        signal.setInputs(Inputs.builder()
                .downstream(Downstream.builder()
                        .id(downstreamTransactionId)
                        .href(downstreamHref)
                        .build())
                .build());

        signal.setTrigger(Trigger.builder()
                .externalEventId(event.getEventId())
                .externalType(event.getType())
                .reportingSystem(reportingSystemId)
                .build());

        publisher.publish(signal);
        log.info("[MOCK pamconsumer] AsyncResponse → task.signal downstreamTransactionId={} corrId={}",
                downstreamTransactionId, event.getCorrelationId());
    }

    // ---- Job 2: Async signal injection (called by MockTaskRunner) ----

    /**
     * Simulates the external async response flow by publishing a task.signal
     * directly to task.command. Called by MockTaskRunner for ASYNC actions.
     */
    public void publishSignalAfterDelay(TaskCommand command,
                                         String taskId, String taskHref,
                                         String downstreamId, String downstreamHref) {
        sleepRandom(300, 900);

        TaskCommand signal = taskCommandFactory.buildBase(
                command.getCorrelationId(), MessageNames.TASK_SIGNAL,
                MessageType.SIGNAL, Sources.PAMCONSUMER);

        signal.setTask(TaskCommand.Task.builder().id(taskId).href(taskHref).build());
        copyAction(command).ifPresent(signal::setAction);
        copyBatch(command).ifPresent(signal::setBatch);

        signal.setInputs(Inputs.builder()
                .downstream(Downstream.builder().id(downstreamId).href(downstreamHref).build())
                .build());
        signal.setTrigger(Trigger.builder()
                .externalEventId(UUID.randomUUID().toString())
                .externalType("TaskFinalAsyncResponseSend")
                .reportingSystem("ACUT")
                .build());

        publisher.publish(signal);
        log.info("[MOCK pamconsumer] published task.signal for taskId={} downstreamId={}",
                taskId, downstreamId);
    }

    // ---- helpers ----

    private static Optional<Action> copyAction(TaskCommand source) {
        return Optional.ofNullable(source.getAction())
                .map(a -> Action.builder()
                        .actionName(a.getActionName()).actionCode(a.getActionCode())
                        .dcxActionCode(a.getDcxActionCode())
                        .build());
    }

    private static Optional<Batch> copyBatch(TaskCommand source) {
        return Optional.ofNullable(source.getBatch())
                .map(b -> Batch.builder().index(b.getIndex()).build());
    }

    private static void sleepRandom(int minMs, int maxMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
