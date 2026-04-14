package ca.siva.orchestrator.kafka;

import ca.siva.orchestrator.domain.MessageNames;
import ca.siva.orchestrator.domain.MessageType;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.domain.TaskStatus;
import ca.siva.orchestrator.dto.TaskCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Publishes flow lifecycle events to the {@code task.command} topic.
 *
 * <p>These are high-level events that mark flow start and end:</p>
 * <ul>
 *   <li>{@code processFlow.initiated} with status INITIATED — flow started</li>
 *   <li>{@code task.event} with status COMPLETED — all batches closed, flow done</li>
 *   <li>{@code task.event} with status FAILED — terminal failure</li>
 * </ul>
 *
 * <p>Published to the same {@code task.command} topic as all other messages.
 * Consumers can filter by {@code messageName} + {@code status} to track lifecycle.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEventsPublisher {

    private final TaskCommandFactory taskCommandFactory;
    private final TaskCommandPublisher publisher;

    /**
     * Publishes an INITIATED lifecycle event when a flow starts.
     * Source is pamconsumer (since pamconsumer initiates the flow).
     */
    public void publishInitiated(String processFlowId, String dagKey) {
        TaskCommand lifecycle = taskCommandFactory.buildBase(
                processFlowId, MessageNames.FLOW_LIFECYCLE,
                MessageType.EVENT, Sources.PAMCONSUMER);
        lifecycle.setDagKey(dagKey);
        lifecycle.setStatus(TaskStatus.INITIAL);
        publisher.publish(lifecycle);
        log.info("LIFECYCLE INITIATED for flow={} dagKey={}", processFlowId, dagKey);
    }

    /**
     * Publishes a COMPLETED lifecycle event when all batches close.
     * Source is task-orchestrator (the orchestrator detected completion).
     */
    public void publishCompleted(String processFlowId) {
        TaskCommand lifecycle = taskCommandFactory.buildBase(
                processFlowId, MessageNames.FLOW_LIFECYCLE,
                MessageType.EVENT, Sources.TASK_ORCHESTRATOR);
        lifecycle.setStatus(TaskStatus.COMPLETED);
        publisher.publish(lifecycle);
        log.info("LIFECYCLE COMPLETED for flow={}", processFlowId);
    }

    /**
     * Publishes a FAILED lifecycle event on terminal failure.
     * Source is task-orchestrator.
     */
    public void publishFailed(String processFlowId, String reason) {
        TaskCommand lifecycle = taskCommandFactory.buildBase(
                processFlowId, MessageNames.FLOW_LIFECYCLE,
                MessageType.EVENT, Sources.TASK_ORCHESTRATOR);
        lifecycle.setStatus(TaskStatus.FAILED);
        lifecycle.setError(TaskCommand.ErrorInfo.builder()
                .message(reason)
                .retryable(false)
                .build());
        publisher.publish(lifecycle);
        log.info("LIFECYCLE FAILED for flow={} reason={}", processFlowId, reason);
    }
}
