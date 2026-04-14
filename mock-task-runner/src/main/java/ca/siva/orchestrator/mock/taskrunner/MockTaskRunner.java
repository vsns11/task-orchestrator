package ca.siva.orchestrator.mock.taskrunner;

import ca.siva.orchestrator.domain.ExecutionMode;
import ca.siva.orchestrator.domain.MessageNames;
import ca.siva.orchestrator.domain.MessageType;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.domain.TaskStatus;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.TaskCommand.Action;
import ca.siva.orchestrator.dto.TaskCommand.AwaitingSignal;
import ca.siva.orchestrator.dto.TaskCommand.Batch;
import ca.siva.orchestrator.dto.TaskCommand.Downstream;
import ca.siva.orchestrator.dto.TaskCommand.Execution;
import ca.siva.orchestrator.dto.TaskCommand.Inputs;
import ca.siva.orchestrator.kafka.TaskCommandFactory;
import ca.siva.orchestrator.kafka.TaskCommandPublisher;
import ca.siva.orchestrator.mock.pamconsumer.MockPamConsumer;
import ca.siva.orchestrator.mock.taskrunner.config.TaskRunnerRetryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-process mock of the task-runner service.
 *
 * <p>SYNC actions → calls downstream with retry → publishes COMPLETED immediately.</p>
 * <p>ASYNC actions → calls downstream → publishes WAITING. The flow pauses here.
 * The signal must be injected manually via the {@code POST /demo/signal} endpoint
 * or by MockPamConsumer when auto-signal is enabled.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockTaskRunner {

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
    public void onMessage(TaskCommand message, Acknowledgment ack) {
        try {
            if (message == null || message.getMessageName() == null) return;

            if (MessageNames.TASK_EXECUTE.equals(message.getMessageName())
                    && Sources.TASK_ORCHESTRATOR.equals(message.getSource())) {
                CompletableFuture.runAsync(() -> handleExecute(message));
            } else if (MessageNames.TASK_SIGNAL.equals(message.getMessageName())
                    && Sources.PAMCONSUMER.equals(message.getSource())) {
                CompletableFuture.runAsync(() -> handleSignal(message));
            }
        } finally {
            ack.acknowledge();
        }
    }

    private void handleExecute(TaskCommand cmd) {
        ExecutionMode mode = Optional.ofNullable(cmd.getExecution())
                .map(Execution::getMode).orElse(ExecutionMode.SYNC);

        String actionName = cmd.getAction().getActionName();

        // Mint a new taskFlow ID (in production this comes from TMF-701 POST)
        String taskId = "tf-" + UUID.randomUUID().toString().substring(0, 8);
        String taskHref = "http://mock-tmf701/processFlow/" + cmd.getCorrelationId()
                + "/taskFlow/" + taskId;

        // Call downstream with retry (retries on configurable HTTP status codes)
        Map<String, Object> downstreamResult = downstreamCaller.callWithRetry(
                actionName, "http://mock-downstream/" + actionName);

        if (ExecutionMode.SYNC.equals(mode)) {
            // SYNC: downstream call completed → publish COMPLETED
            Map<String, Object> result = new java.util.HashMap<>(downstreamResult);
            result.put("actionName", actionName);
            result.put("completedAt", Instant.now().toString());
            publishCompleted(cmd, taskId, taskHref, ExecutionMode.SYNC, null, null, result);
        } else {
            // ASYNC: publish WAITING — flow pauses until signal is injected
            String dsId = "ASYNC_" + UUID.randomUUID().toString().substring(0, 12);
            String dsHref = "http://mock-downstream/queries/" + dsId;
            publishWaiting(cmd, taskId, taskHref, dsId, dsHref);

            // Auto-inject signal after delay (simulates external system callback)
            // In manual testing, disable this and use POST /demo/signal instead
            CompletableFuture.runAsync(
                    () -> mockPamConsumer.publishSignalAfterDelay(cmd, taskId, taskHref, dsId, dsHref));
        }
    }

    private void handleSignal(TaskCommand sig) {
        sleepRandom(50, 200);

        String taskId = Optional.ofNullable(sig.getTask())
                .map(TaskCommand.Task::getId).orElse(null);
        String taskHref = Optional.ofNullable(sig.getTask())
                .map(TaskCommand.Task::getHref).orElse(null);
        String dsId = Optional.ofNullable(sig.getInputs())
                .map(Inputs::getDownstream).map(Downstream::getId).orElse(null);
        String dsHref = Optional.ofNullable(sig.getInputs())
                .map(Inputs::getDownstream).map(Downstream::getHref).orElse(null);

        publishCompleted(sig, taskId, taskHref, ExecutionMode.ASYNC, dsId, dsHref,
                Map.of("asyncResult", "PASS",
                       "fetchedFrom", dsHref == null ? "" : dsHref,
                       "completedAt", Instant.now().toString()));
    }

    // ---- envelope builders ----

    private void publishCompleted(TaskCommand cmd, String taskId, String taskHref,
                                   ExecutionMode mode, String dsId, String dsHref,
                                   Map<String, Object> result) {
        TaskCommand ev = taskCommandFactory.buildBase(
                cmd.getCorrelationId(), MessageNames.TASK_EVENT, MessageType.EVENT, Sources.TASK_RUNNER);

        ev.setTask(TaskCommand.Task.builder().id(taskId).href(taskHref).build());
        copyAction(cmd).ifPresent(ev::setAction);
        copyBatch(cmd).ifPresent(ev::setBatch);
        ev.setStatus(TaskStatus.COMPLETED);

        Instant now = Instant.now();
        ev.setExecution(Execution.builder()
                .mode(mode)
                .startedAt(now.minus(Duration.ofSeconds(1)))
                .finishedAt(now).durationMs(1000L)
                .build());

        if (dsId != null) {
            ev.setDownstream(Downstream.builder().id(dsId).href(dsHref).build());
        }
        ev.setResult(result);
        publisher.publish(ev);
    }

    private void publishWaiting(TaskCommand cmd, String taskId, String taskHref,
                                 String downstreamId, String downstreamHref) {
        TaskCommand ev = taskCommandFactory.buildBase(
                cmd.getCorrelationId(), MessageNames.TASK_EVENT, MessageType.EVENT, Sources.TASK_RUNNER);

        ev.setTask(TaskCommand.Task.builder().id(taskId).href(taskHref).build());
        copyAction(cmd).ifPresent(ev::setAction);
        copyBatch(cmd).ifPresent(ev::setBatch);
        ev.setStatus(TaskStatus.WAITING);

        ev.setExecution(Execution.builder()
                .mode(ExecutionMode.ASYNC).startedAt(Instant.now())
                .build());
        ev.setDownstream(Downstream.builder().id(downstreamId).href(downstreamHref).build());
        ev.setAwaitingSignal(AwaitingSignal.builder().downstreamTransactionId(downstreamId).build());

        publisher.publish(ev);
    }

    // ---- helpers ----

    private static Optional<Action> copyAction(TaskCommand src) {
        return Optional.ofNullable(src.getAction())
                .map(a -> Action.builder()
                        .actionName(a.getActionName()).actionCode(a.getActionCode())
                        .dcxActionCode(a.getDcxActionCode())
                        .build());
    }

    private static Optional<Batch> copyBatch(TaskCommand src) {
        return Optional.ofNullable(src.getBatch())
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
