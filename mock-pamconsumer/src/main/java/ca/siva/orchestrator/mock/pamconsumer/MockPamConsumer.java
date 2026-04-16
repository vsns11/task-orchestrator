package ca.siva.orchestrator.mock.pamconsumer;

import ca.siva.orchestrator.domain.MessageName;
import ca.siva.orchestrator.domain.MessageType;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.TaskCommand.Action;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import ca.siva.orchestrator.dto.TaskCommand.Batch;
import ca.siva.orchestrator.dto.TaskCommand.Downstream;
import ca.siva.orchestrator.dto.TaskCommand.Inputs;
import ca.siva.orchestrator.dto.tmf.NotificationEvent;
import ca.siva.orchestrator.kafka.TaskCommandFactory;
import ca.siva.orchestrator.kafka.TaskCommandPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
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
@Profile("local-dev")
public class MockPamConsumer {

    private final TaskCommandPublisher publisher;
    private final TaskCommandFactory taskCommandFactory;
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

        // Convert NotificationEvent to typed ProcessFlow
        ProcessFlow processFlow =
                objectMapper.convertValue(event, ProcessFlow.class);

        // Build processFlow.initiated and publish to task.command
        TaskCommand initiated = taskCommandFactory.buildBase(
                processFlowId, MessageName.PROCESS_FLOW_INITIATED.getValue(),
                MessageType.EVENT, Sources.PAMCONSUMER);
        initiated.setDagKey(dagKey);
        initiated.setInputs(TaskCommand.Inputs.builder()
                .processFlow(processFlow)
                .build());

        publisher.publish(initiated);

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
                event.getCorrelationId(), MessageName.TASK_SIGNAL.getValue(),
                MessageType.SIGNAL, Sources.PAMCONSUMER);

        signal.setInputs(Inputs.builder()
                .downstream(Downstream.builder()
                        .id(downstreamTransactionId)
                        .href(downstreamHref)
                        .build())
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
                command.getCorrelationId(), MessageName.TASK_SIGNAL.getValue(),
                MessageType.SIGNAL, Sources.PAMCONSUMER);

        signal.setTask(TaskCommand.Task.builder().id(taskId).href(taskHref).build());
        copyAction(command).ifPresent(signal::setAction);
        copyBatch(command).ifPresent(signal::setBatch);

        signal.setInputs(Inputs.builder()
                .downstream(Downstream.builder().id(downstreamId).href(downstreamHref).build())
                .externalEventId(UUID.randomUUID().toString())
                .externalType("TaskFinalAsyncResponseSend")
                .reportingSystem("ACUT")
                .build());

        publisher.publish(signal);
        log.info("[MOCK pamconsumer] published task.signal for taskId={} downstreamId={}",
                taskId, downstreamId);
    }

    // ---- helpers ----

    private static Optional<Action> copyAction(TaskCommand taskCommand) {
        return Optional.ofNullable(taskCommand.getAction())
                .map(action -> Action.builder()
                        .actionName(action.getActionName())
                        .actionCode(action.getActionCode())
                        .dcxActionCode(action.getDcxActionCode())
                        .build());
    }

    private static Optional<Batch> copyBatch(TaskCommand taskCommand) {
        return Optional.ofNullable(taskCommand.getBatch())
                .map(batch -> Batch.builder().index(batch.getIndex()).build());
    }

    private static void sleepRandom(int minMs, int maxMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
