package ca.siva.orchestrator.service;

import ca.siva.orchestrator.client.Tmf701Client;
import ca.siva.orchestrator.dag.DagDefinition;
import ca.siva.orchestrator.dag.DagRegistry;
import ca.siva.orchestrator.domain.BarrierStatus;
import ca.siva.orchestrator.domain.TaskStatus;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.entity.BatchBarrier;
import ca.siva.orchestrator.entity.BatchBarrierId;
import ca.siva.orchestrator.kafka.TaskCommandFactory;
import ca.siva.orchestrator.kafka.TaskCommandPublisher;
import ca.siva.orchestrator.repository.BatchBarrierRepository;
import ca.siva.orchestrator.repository.TaskExecutionRepository;
import ca.siva.orchestrator.entity.TaskExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core orchestration logic: manages batch barriers, advances DAG execution,
 * and coordinates TMF-701 state updates.
 *
 * <p>All operations are keyed by {@code processFlowId} (= Kafka correlationId
 * = TMF-701 processFlow UUID).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BarrierService {

    private final BatchBarrierRepository  repo;
    private final TaskExecutionRepository taskExecutionRepo;
    private final DagRegistry             dagRegistry;
    private final TaskCommandFactory      taskCommandFactory;
    private final TaskCommandPublisher    publisher;
    private final Tmf701Client            tmf701;
    private final ca.siva.orchestrator.kafka.TaskEventsPublisher taskEventsPublisher;

    /** Cache of processFlow payloads keyed by processFlowId — needed to pass to subsequent batches. */
    private final Map<String, Map<String, Object>> processFlowCache = new ConcurrentHashMap<>();

    /**
     * Seeds the first batch barrier and publishes task.execute commands
     * for all actions in batch 0 of the resolved DAG.
     *
     * @param processFlowId the TMF-701 processFlow UUID (= correlationId)
     * @param dagKey        which DAG to execute
     * @param processFlow   the full processFlow payload from the initiated event
     */
    @Retryable(retryFor = OptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(50))
    @Transactional
    public void initiateFlow(String processFlowId, String dagKey,
                             Map<String, Object> processFlow) {
        Optional<DagDefinition> dagOpt = dagRegistry.find(dagKey);
        if (dagOpt.isEmpty()) {
            log.warn("Unknown dagKey={} for flow {} - skipping", dagKey, processFlowId);
            return;
        }
        DagDefinition dag = dagOpt.get();

        Optional<DagDefinition.BatchDef> firstBatch = dag.batch(0);
        if (firstBatch.isEmpty()) {
            log.warn("DAG {} has no batch 0 - skipping flow {}", dagKey, processFlowId);
            return;
        }

        // Status-based duplicate guard: if barrier already exists, skip
        BatchBarrierId id = new BatchBarrierId(processFlowId, (short) 0);
        if (repo.existsById(id)) {
            log.info("Barrier batch 0 already seeded for {} - skipping", processFlowId);
            return;
        }

        // Cache the processFlow so subsequent batches can access it
        processFlowCache.put(processFlowId, processFlow);
        seedAndPublishBatch(processFlowId, dag, firstBatch.get());
    }

    /**
     * Processes a task.event by updating barrier counters and advancing
     * the DAG when a batch completes.
     */
    @Retryable(retryFor = OptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(50))
    @Transactional
    public void applyTaskEvent(TaskCommand taskEvent) {
        TaskStatus status = taskEvent.getStatus();
        if (status == null) {
            log.warn("task.event {} missing status - ignoring", taskEvent.getEventId());
            return;
        }
        if (taskEvent.getBatch() == null || taskEvent.getBatch().getIndex() == null) {
            log.warn("task.event {} missing batch.index - ignoring", taskEvent.getEventId());
            return;
        }

        String processFlowId = taskEvent.getCorrelationId();
        short batchIndex = taskEvent.getBatch().getIndex().shortValue();

        switch (status) {
            case INITIAL, IN_PROGRESS ->
                    log.debug("Status {} for action={} task={} (no barrier change)",
                            status, actionName(taskEvent), taskId(taskEvent));

            case WAITING ->
                    log.info("Task {} ({}) WAITING on downstream {} — no barrier change, no PATCH",
                            taskId(taskEvent), actionName(taskEvent), downstreamId(taskEvent));

            case COMPLETED ->
                    handleCompleted(taskEvent, processFlowId, batchIndex);

            case FAILED, CANCELLED ->
                    handleFailed(taskEvent, processFlowId, batchIndex);
        }
    }

    // ---- internal handlers ----

    private void handleCompleted(TaskCommand taskEvent, String processFlowId, short batchIndex) {
        // Register taskFlow reference on the parent processFlow in TMF-701
        patchParentProcessFlow(taskEvent, processFlowId);

        Optional<BatchBarrier> barrierOpt = repo.findById(new BatchBarrierId(processFlowId, batchIndex));
        if (barrierOpt.isEmpty()) {
            log.warn("No barrier for flow={} batch={} - ignoring COMPLETED", processFlowId, batchIndex);
            return;
        }
        BatchBarrier barrier = barrierOpt.get();

        // Status-based guard: if already CLOSED/FAILED, skip duplicate
        if (barrier.getStatus() != BarrierStatus.OPEN) {
            log.info("Barrier {}/{} already {} - ignoring duplicate COMPLETED",
                    processFlowId, batchIndex, barrier.getStatus());
            return;
        }

        barrier.incCompleted();
        log.info("Barrier {}/{}: completed={} failed={} pending={}",
                processFlowId, batchIndex, barrier.getTaskCompleted(),
                barrier.getTaskFailed(), barrier.pending());

        if (barrier.pending() == 0) {
            barrier.close();
            repo.save(barrier);
            log.info("Batch {} CLOSED for {}", batchIndex, processFlowId);
            promoteNextBatch(processFlowId, barrier.getDagKey(), batchIndex);
        } else {
            repo.save(barrier);
        }
    }

    private void handleFailed(TaskCommand taskEvent, String processFlowId, short batchIndex) {
        patchParentProcessFlow(taskEvent, processFlowId);

        Optional<BatchBarrier> barrierOpt = repo.findById(new BatchBarrierId(processFlowId, batchIndex));
        if (barrierOpt.isEmpty()) {
            log.warn("No barrier for flow={} batch={} - ignoring FAILED", processFlowId, batchIndex);
            return;
        }
        BatchBarrier barrier = barrierOpt.get();

        if (barrier.getStatus() != BarrierStatus.OPEN) {
            log.info("Barrier {}/{} already {} - ignoring duplicate FAILED",
                    processFlowId, batchIndex, barrier.getStatus());
            return;
        }

        barrier.incFailed();
        boolean retryable = Optional.ofNullable(taskEvent.getError())
                .map(TaskCommand.ErrorInfo::getRetryable)
                .orElse(false);

        if (!retryable) {
            barrier.fail();
            repo.save(barrier);
            log.error("Batch {} FAILED for flow {} (action {} non-retryable)",
                    batchIndex, processFlowId, actionName(taskEvent));
            tmf701.patchProcessFlowState(processFlowId, "failed");
            taskEventsPublisher.publishFailed(processFlowId, "action " + actionName(taskEvent) + " non-retryable");
        } else {
            repo.save(barrier);
        }
    }

    private void promoteNextBatch(String processFlowId, String dagKey, short closedIndex) {
        Optional<DagDefinition> dagOpt = dagRegistry.find(dagKey);
        if (dagOpt.isEmpty()) {
            log.warn("DAG {} not found during promotion for flow {}", dagKey, processFlowId);
            return;
        }
        DagDefinition dag = dagOpt.get();
        int nextIndex = closedIndex + 1;

        Optional<DagDefinition.BatchDef> nextBatch = dag.batch(nextIndex);
        if (nextBatch.isEmpty()) {
            log.info("All batches CLOSED for flow {} - marking processFlow completed", processFlowId);
            tmf701.patchProcessFlowState(processFlowId, "completed");
            taskEventsPublisher.publishCompleted(processFlowId);
            processFlowCache.remove(processFlowId); // cleanup
            return;
        }

        seedAndPublishBatch(processFlowId, dag, nextBatch.get());
    }

    private void seedAndPublishBatch(String processFlowId, DagDefinition dag,
                                      DagDefinition.BatchDef batch) {
        BatchBarrier barrier = new BatchBarrier();
        barrier.setId(new BatchBarrierId(processFlowId, (short) batch.getIndex()));
        barrier.setDagKey(dag.getDagKey());
        barrier.setTaskTotal(batch.getActions().size());
        barrier.open();
        repo.save(barrier);

        log.info("Seeded barrier batch {} for flow {} (total={})",
                batch.getIndex(), processFlowId, batch.getActions().size());

        // Get the original processFlow payload from cache
        Map<String, Object> processFlow = processFlowCache.getOrDefault(processFlowId, Map.of());

        // For each action, resolve its declared dependencies and build the command
        batch.getActions().stream()
                .flatMap(action -> {
                    Map<String, Object> dependencyResults = resolveDependencies(processFlowId, action);
                    return taskCommandFactory.buildTaskExecute(
                            processFlowId, dag.getDagKey(), batch, action,
                            processFlow, dependencyResults).stream();
                })
                .forEach(publisher::publish);
    }

    /**
     * Resolves dependency results for a given action.
     *
     * <p>If the action declares {@code dependsOn: [actionA, actionB]}, loads the
     * latest COMPLETED result for each from the task_execution table. Returns
     * a Map keyed by actionName with the parsed result JSON as the value.</p>
     *
     * @return dependency results map, or empty map if no dependencies declared
     */
    private Map<String, Object> resolveDependencies(String processFlowId,
                                                     DagDefinition.ActionDef action) {
        List<String> dependsOn = action.getDependsOn();
        if (dependsOn == null || dependsOn.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> results = new HashMap<>();
        for (String dependencyActionName : dependsOn) {
            // Single indexed query — not findAll().stream().filter()
            Optional<TaskExecution> completed = taskExecutionRepo
                    .findLatestCompletedByProcessFlowAndAction(processFlowId, dependencyActionName);

            completed.ifPresentOrElse(
                    te -> results.put(dependencyActionName, te.getResultJson()),
                    () -> log.warn("Dependency {} not found for action {} in flow {}",
                            dependencyActionName, action.getActionName(), processFlowId)
            );
        }

        return results;
    }

    private void patchParentProcessFlow(TaskCommand taskEvent, String processFlowId) {
        Optional<TaskCommand.Task> task = Optional.ofNullable(taskEvent.getTask());
        if (task.map(TaskCommand.Task::getId).isEmpty() || taskEvent.getAction() == null) {
            return;
        }
        tmf701.patchProcessFlowAddTaskFlowRef(
                processFlowId,
                taskEvent.getTask().getId(),
                taskEvent.getTask().getHref(),
                taskEvent.getAction().getActionName());
    }

    // ---- null-safe field extractors for logging ----

    private static String actionName(TaskCommand taskEvent) {
        return Optional.ofNullable(taskEvent.getAction())
                .map(TaskCommand.Action::getActionName).orElse("?");
    }

    private static String taskId(TaskCommand taskEvent) {
        return Optional.ofNullable(taskEvent.getTask())
                .map(TaskCommand.Task::getId).orElse("?");
    }

    private static String downstreamId(TaskCommand taskEvent) {
        return Optional.ofNullable(taskEvent.getDownstream())
                .map(TaskCommand.Downstream::getId).orElse("?");
    }
}
