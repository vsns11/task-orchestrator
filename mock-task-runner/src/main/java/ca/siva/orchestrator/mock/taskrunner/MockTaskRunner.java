package ca.siva.orchestrator.mock.taskrunner;

import ca.siva.orchestrator.domain.ExecutionMode;
import ca.siva.orchestrator.domain.MessageName;
import ca.siva.orchestrator.domain.MessageType;
import ca.siva.orchestrator.domain.ProcessFlowStateType;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.domain.TaskStatus;
import ca.siva.orchestrator.dto.ActionResponse;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.TaskCommand.Action;
import ca.siva.orchestrator.dto.TaskCommand.AwaitingSignal;
import ca.siva.orchestrator.dto.TaskCommand.Batch;
import ca.siva.orchestrator.dto.TaskCommand.Execution;
import ca.siva.orchestrator.dto.tmf.ProcessFlow.Characteristic;
import ca.siva.orchestrator.dto.tmf.TaskFlow;
import ca.siva.orchestrator.kafka.TaskCommandFactory;
import ca.siva.orchestrator.kafka.TaskCommandPublisher;
import ca.siva.orchestrator.mock.pamconsumer.MockPamConsumer;
import ca.siva.orchestrator.mock.taskrunner.config.TaskRunnerRetryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-process mock of the task-runner service.
 *
 * <p>SYNC actions -> calls downstream with retry -> publishes COMPLETED immediately.</p>
 * <p>ASYNC actions -> calls downstream -> publishes WAITING. The flow pauses here.
 * The signal must be injected manually via the {@code POST /demo/signal} endpoint
 * or by MockPamConsumer when auto-signal is enabled.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("local-dev")
public class MockTaskRunner {

    private static final String TYPE_TASK_FLOW = "TaskFlow";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_WAITING = "WAITING";
    /**
     * TMF-701 taskFlow lifecycle states written into {@code taskFlowResponse.state}.
     * Sourced from {@link ProcessFlowStateType} so the wire vocabulary is
     * defined in exactly one place across the codebase.
     */
    private static final String STATE_COMPLETED    = ProcessFlowStateType.COMPLETED.getValue();
    private static final String STATE_ACKNOWLEDGED = "acknowledged";
    private static final String UNKNOWN = "unknown";

    private final TaskCommandPublisher publisher;
    private final TaskCommandFactory taskCommandFactory;
    private final MockPamConsumer mockPamConsumer;
    private final DownstreamCaller downstreamCaller;
    private final TaskRunnerRetryProperties retryProperties;

    @KafkaListener(
            topics = "${orchestrator.topics.task-command}",
            groupId = "mock-task-runner",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMessage(TaskCommand taskCommand, Acknowledgment ack) {
        try {
            if (taskCommand == null || taskCommand.getMessageName() == null) return;

            if (MessageName.TASK_EXECUTE.getValue().equals(taskCommand.getMessageName())
                    && Sources.TASK_ORCHESTRATOR.equals(taskCommand.getSource())) {
                CompletableFuture.runAsync(() -> handleExecute(taskCommand))
                        .exceptionally(ex -> { log.error("handleExecute failed: {}", ex.getMessage(), ex); return null; });
            } else if (MessageName.TASK_SIGNAL.getValue().equals(taskCommand.getMessageName())
                    && Sources.PAMCONSUMER.equals(taskCommand.getSource())) {
                CompletableFuture.runAsync(() -> handleSignal(taskCommand))
                        .exceptionally(ex -> { log.error("handleSignal failed: {}", ex.getMessage(), ex); return null; });
            }
        } finally {
            ack.acknowledge();
        }
    }

    private void handleExecute(TaskCommand taskCommand) {
        ExecutionMode mode = Optional.ofNullable(taskCommand.getExecution())
                .map(Execution::getMode).orElse(ExecutionMode.SYNC);

        String actionName = taskCommand.getAction().getActionName();
        String actionCode = taskCommand.getAction().getActionCode();

        // Mint a new taskFlow ID (in production this comes from TMF-701 POST)
        String taskFlowId = "tf-" + UUID.randomUUID().toString().substring(0, 8);
        String taskFlowHref = "http://mock-tmf701/processFlow/" + taskCommand.getCorrelationId()
                + "/taskFlow/" + taskFlowId;

        // Call downstream with retry (retries on configurable HTTP status codes)
        Map<String, Object> downstreamResult = downstreamCaller.callWithRetry(
                actionName, "http://mock-downstream/" + actionName);

        if (ExecutionMode.SYNC.equals(mode)) {
            // SYNC: downstream call completed -> publish COMPLETED with ActionResponse
            ActionResponse result = buildActionResponse(
                    actionName, actionCode, taskFlowId, taskFlowHref, downstreamResult, STATUS_COMPLETED);
            publishCompleted(taskCommand, ExecutionMode.SYNC, result);
        } else {
            // ASYNC: publish WAITING -- flow pauses until signal is injected
            String downstreamId = "ASYNC_" + UUID.randomUUID().toString().substring(0, 12);
            String downstreamHref = "http://mock-downstream/queries/" + downstreamId;
            ActionResponse waitingResult = ActionResponse.builder()
                    .name(actionName).code(actionCode).id(taskFlowId).type(TYPE_TASK_FLOW)
                    // No downstream payload yet — the task is just acknowledged and waiting.
                    // taskFlowResponse carries the id/href so the orchestrator can still
                    // PATCH the parent processFlow.
                    .taskFlowResponse(TaskFlow.builder()
                            .id(taskFlowId)
                            .href(taskFlowHref)
                            .type(TYPE_TASK_FLOW)
                            .state(STATE_ACKNOWLEDGED)
                            .build())
                    .taskStatusCode(STATUS_WAITING)
                    .build();
            publishWaiting(taskCommand, downstreamId, waitingResult);

            // Auto-inject signal after delay (simulates external system callback).
            // In manual testing, disable this and use POST /demo/signal instead.
            CompletableFuture.runAsync(
                    () -> mockPamConsumer.publishSignalAfterDelay(
                            taskCommand, taskFlowId, taskFlowHref, downstreamId, downstreamHref));
        }
    }

    private void handleSignal(TaskCommand signal) {
        sleepRandom(50, 200);

        String taskFlowId = Optional.ofNullable(signal.getTask())
                .map(TaskCommand.Task::getId).orElse(null);
        String taskFlowHref = Optional.ofNullable(signal.getTask())
                .map(TaskCommand.Task::getHref).orElse(null);

        String actionName = Optional.ofNullable(signal.getAction())
                .map(Action::getActionName).orElse(UNKNOWN);
        String actionCode = Optional.ofNullable(signal.getAction())
                .map(Action::getActionCode).orElse(UNKNOWN);

        Map<String, Object> downstreamResult = Map.of(
                "status", "pass",
                "diagnosticSummary", "Async diagnostic completed successfully",
                "latencyMs", 1200,
                "checksRun", 5,
                "checksPassed", 5,
                "checksFailed", 0
        );

        ActionResponse result = buildActionResponse(
                actionName, actionCode, taskFlowId, taskFlowHref, downstreamResult, STATUS_COMPLETED);
        publishCompleted(signal, ExecutionMode.ASYNC, result);
    }

    // ---- envelope builders ----

    private void publishCompleted(TaskCommand taskCommand, ExecutionMode mode, ActionResponse result) {
        TaskCommand event = taskCommandFactory.buildBase(
                taskCommand.getCorrelationId(), MessageName.TASK_EVENT.getValue(),
                MessageType.EVENT, Sources.TASK_RUNNER);

        copyAction(taskCommand).ifPresent(event::setAction);
        copyBatch(taskCommand).ifPresent(event::setBatch);
        event.setStatus(TaskStatus.COMPLETED);

        Instant now = Instant.now();
        event.setExecution(Execution.builder()
                .mode(mode)
                .startedAt(now.minus(Duration.ofSeconds(1)))
                .finishedAt(now).durationMs(1000L)
                .build());

        event.setResult(result);
        publisher.publish(event);
    }

    private void publishWaiting(TaskCommand taskCommand, String downstreamId, ActionResponse result) {
        TaskCommand event = taskCommandFactory.buildBase(
                taskCommand.getCorrelationId(), MessageName.TASK_EVENT.getValue(),
                MessageType.EVENT, Sources.TASK_RUNNER);

        copyAction(taskCommand).ifPresent(event::setAction);
        copyBatch(taskCommand).ifPresent(event::setBatch);
        event.setStatus(TaskStatus.WAITING);

        event.setExecution(Execution.builder()
                .mode(ExecutionMode.ASYNC).startedAt(Instant.now())
                .build());
        event.setAwaitingSignal(AwaitingSignal.builder().downstreamTransactionId(downstreamId).build());
        event.setResult(result);

        publisher.publish(event);
    }

    // ---- result builder ----

    /**
     * Builds an {@link ActionResponse} for a task.event.
     *
     * <p>Shape of each field:</p>
     * <ul>
     *   <li>{@code taskResult} — raw downstream response as-is (here a
     *       {@code Map<String,Object>}; in production this is typically a
     *       {@code JsonNode} or a domain DTO deserialized from the downstream
     *       HTTP/Kafka response).</li>
     *   <li>{@code taskFlowResponse} — typed TMF-701 {@link TaskFlow}; domain outputs
     *       ({@code status}, {@code diagnosticSummary}, …) are emitted as
     *       {@link Characteristic} name/value pairs per TMF convention.</li>
     * </ul>
     */
    private static ActionResponse buildActionResponse(String actionName, String actionCode,
                                                       String taskFlowId, String taskFlowHref,
                                                       Map<String, Object> downstreamResult,
                                                       String taskStatusCode) {
        return ActionResponse.builder()
                .name(actionName)
                .code(actionCode)
                .id(taskFlowId)
                .type(TYPE_TASK_FLOW)
                .taskResult(downstreamResult)
                .taskFlowResponse(TaskFlow.builder()
                        .id(taskFlowId)
                        .href(taskFlowHref)
                        .type(TYPE_TASK_FLOW)
                        .state(STATE_COMPLETED)
                        .characteristic(toCharacteristics(downstreamResult))
                        .build())
                .taskStatusCode(taskStatusCode)
                .build();
    }

    /**
     * Flattens a downstream result map into TMF-701 {@link Characteristic}
     * entries. Preserves insertion order and stringifies values — matching the
     * TMF convention that characteristic values are strings with an optional
     * {@code valueType}.
     */
    private static List<Characteristic> toCharacteristics(Map<String, Object> downstreamResult) {
        if (downstreamResult == null || downstreamResult.isEmpty()) {
            return List.of();
        }
        List<Characteristic> out = new ArrayList<>(downstreamResult.size());
        for (Entry<String, Object> e : downstreamResult.entrySet()) {
            Object v = e.getValue();
            Characteristic c = new Characteristic();
            c.setName(e.getKey());
            c.setValue(v == null ? null : v.toString());
            c.setValueType(v == null ? null : v.getClass().getSimpleName());
            out.add(c);
        }
        return out;
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
