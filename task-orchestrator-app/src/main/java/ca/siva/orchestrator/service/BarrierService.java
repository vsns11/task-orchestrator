package ca.siva.orchestrator.service;

import ca.siva.orchestrator.client.Tmf701Client;
import ca.siva.orchestrator.dag.DagDefinition;
import ca.siva.orchestrator.dag.DagRegistry;
import ca.siva.orchestrator.domain.BarrierStatus;
import ca.siva.orchestrator.domain.TaskStatus;
import ca.siva.orchestrator.dto.ActionResponse;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import ca.siva.orchestrator.dto.tmf.TaskFlow;
import ca.siva.orchestrator.entity.BatchBarrier;
import ca.siva.orchestrator.entity.BatchBarrierId;
import ca.siva.orchestrator.entity.TaskExecution;
import ca.siva.orchestrator.kafka.TaskCommandFactory;
import ca.siva.orchestrator.kafka.TaskCommandPublisher;
import ca.siva.orchestrator.kafka.TaskEventsPublisher;
import ca.siva.orchestrator.repository.BatchBarrierRepository;
import ca.siva.orchestrator.repository.TaskExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Core orchestration logic: manages batch barriers, advances DAG execution,
 * and coordinates TMF-701 state updates.
 *
 * <p>The processFlow object is NOT cached in memory. When promoting to the
 * next batch, the orchestrator fetches it from TMF-701 via GET API call.
 * This approach scales to any number of concurrent flows.</p>
 *
 * <p><b>Kickout</b> — the orchestrator validates every completed action's
 * {@link ActionResponse}. A response that reports a non-completed taskStatusCode
 * or a non-{@code pass} business outcome "kicks out" the whole flow:
 * the current barrier is marked failed, the TMF-701 processFlow is PATCHed
 * to {@code failed}, and a {@code flow.lifecycle} failed event is published.
 * Other open barriers on the same flow are left untouched — each one will
 * naturally close or time out under its own barrier lifecycle. Kickout runs
 * at two checkpoints — per-action inside {@link #handleCompleted} (primary)
 * and per-batch inside {@link #promoteNextBatch} (defensive sweep right
 * before promotion).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BarrierService {

    // ---- constants ----

    /** TMF-701 processFlow state values used in PATCH state updates. */
    private static final String STATE_COMPLETED = "completed";
    private static final String STATE_FAILED    = "failed";

    /** ActionResponse taskStatusCode values — anything other than COMPLETED is treated as a failure. */
    private static final String TASK_STATUS_COMPLETED = "COMPLETED";

    /** Characteristic name carrying the pass/fail status on the TMF-701 TaskFlow response. */
    private static final String STATUS_CHARACTERISTIC = "status";
    /** Expected value of the {@code status} characteristic for a passing action. */
    private static final String STATUS_PASS           = "pass";

    /** Unknown identifier used in log messages when a field is absent. */
    private static final String UNKNOWN = "?";

    private final BatchBarrierRepository  repo;
    private final TaskExecutionRepository taskExecutionRepo;
    private final DagRegistry             dagRegistry;
    private final TaskCommandFactory      taskCommandFactory;
    private final TaskCommandPublisher    publisher;
    private final Tmf701Client            tmf701;
    private final TaskEventsPublisher     taskCommandsPublisher;

    /**
     * Seeds the first batch barrier and publishes task.execute commands
     * for all actions in batch 0 of the resolved DAG.
     *
     * @param correlationId the Kafka correlationId (= TMF-701 processFlow UUID)
     * @param dagKey        which DAG to execute
     * @param processFlow   the typed processFlow object from the initiated event
     */
    @Retryable(retryFor = {OptimisticLockingFailureException.class, DataIntegrityViolationException.class},
               maxAttempts = 5, backoff = @Backoff(delay = 100, multiplier = 2, maxDelay = 2000))
    @Transactional
    public void initiateFlow(String correlationId, String dagKey,
                             ProcessFlow processFlow) {
        Optional<DagDefinition> dagLookup = dagRegistry.find(dagKey);
        if (dagLookup.isEmpty()) {
            log.warn("Unknown dagKey={} for flow={} — skipping", dagKey, correlationId);
            return;
        }
        DagDefinition dag = dagLookup.get();

        Optional<DagDefinition.BatchDef> firstBatch = dag.batch(0);
        if (firstBatch.isEmpty()) {
            log.warn("DAG {} has no batch 0 — skipping flow={}", dagKey, correlationId);
            return;
        }

        if (repo.existsByCorrelationIdAndBatchIndex(correlationId, (short) 0)) {
            log.info("Barrier batch 0 already seeded for flow={} — skipping", correlationId);
            return;
        }

        // Register the flow.lifecycle INITIAL hook FIRST so it fires before
        // any task.execute commands. Post-commit synchronizations run in the
        // order they were registered; seedAndPublishBatch(...) internally
        // registers task.execute publish hooks via publishAfterCommit(...),
        // so if we call it before registering the lifecycle hook the
        // task.execute commands would go out ahead of the INITIAL lifecycle
        // event — breaking the contract that downstream consumers see a
        // flow started notification before any of its task commands.
        runAfterCommit(() -> taskCommandsPublisher.publishInitiated(correlationId, dagKey));
        seedAndPublishBatch(correlationId, dag, firstBatch.get(), processFlow);
    }

    /**
     * Processes a task.event by updating barrier counters and advancing
     * the DAG when a batch completes.
     */
    @Retryable(retryFor = {OptimisticLockingFailureException.class, DataIntegrityViolationException.class},
               maxAttempts = 5, backoff = @Backoff(delay = 100, multiplier = 2, maxDelay = 2000))
    @Transactional
    public void applyTaskEvent(TaskCommand taskCommand) {
        TaskStatus status = taskCommand.getStatus();
        if (status == null) {
            log.warn("task.event {} missing status - ignoring", taskCommand.getEventId());
            return;
        }
        if (taskCommand.getBatch() == null || taskCommand.getBatch().getIndex() == null) {
            log.warn("task.event {} missing batch.index - ignoring", taskCommand.getEventId());
            return;
        }

        String correlationId = taskCommand.getCorrelationId();
        short batchIndex = taskCommand.getBatch().getIndex().shortValue();

        switch (status) {
            case INITIAL, IN_PROGRESS ->
                    log.debug("Status {} for action={} task={} (no barrier change)",
                            status, actionName(taskCommand), taskId(taskCommand));

            case WAITING ->
                    log.info("Action {} waiting on downstream={} — no barrier change, no PATCH",
                            actionName(taskCommand), awaitingSignalId(taskCommand));

            case COMPLETED ->
                    handleCompleted(taskCommand, correlationId, batchIndex);

            case FAILED, CANCELLED ->
                    handleFailed(taskCommand, correlationId, batchIndex);
        }
    }

    // ---- internal handlers ----

    private void handleCompleted(TaskCommand taskCommand, String correlationId, short batchIndex) {
        // Defer the TMF-701 PATCH (blocking HTTP) until after the DB transaction commits.
        // Holding a DB connection during an HTTP call can exhaust HikariCP under load.
        runAfterCommit(() -> patchParentProcessFlow(taskCommand, correlationId));

        Optional<BatchBarrier> barrierLookup = repo.findByCorrelationIdAndBatchIndex(correlationId, batchIndex);
        if (barrierLookup.isEmpty()) {
            log.warn("No barrier for flow={} batch={} — ignoring completed event", correlationId, batchIndex);
            return;
        }
        BatchBarrier barrier = barrierLookup.get();

        if (barrier.getStatus() != BarrierStatus.OPEN) {
            log.info("Barrier flow={} batch={} already {} — ignoring duplicate completed event",
                    correlationId, batchIndex, barrier.getStatus());
            return;
        }

        // Kickout (per-action): even though the task-runner reports completed,
        // the business status inside actionResponse may indicate failure. Kick
        // out the whole flow so no further batches are seeded for a flow that
        // already produced a bad downstream response.
        Optional<String> kickoutReason = detectKickout(taskCommand);
        if (kickoutReason.isPresent()) {
            kickoutFlow(correlationId, barrier,
                    "action " + actionName(taskCommand) + " kicked out: " + kickoutReason.get());
            return;
        }

        barrier.incCompleted();
        log.info("Barrier flow={} batch={}: completed={} failed={} pending={}",
                correlationId, batchIndex, barrier.getTaskCompleted(),
                barrier.getTaskFailed(), barrier.pending());

        if (barrier.pending() == 0) {
            barrier.close();
            repo.save(barrier);
            log.info("Batch {} closed for flow={}", batchIndex, correlationId);
            promoteNextBatch(correlationId, barrier.getDagKey(), batchIndex);
        } else {
            repo.save(barrier);
        }
    }

    private void handleFailed(TaskCommand taskCommand, String correlationId, short batchIndex) {
        // Defer the TMF-701 PATCH (blocking HTTP) until after the DB transaction commits.
        runAfterCommit(() -> patchParentProcessFlow(taskCommand, correlationId));

        Optional<BatchBarrier> barrierLookup = repo.findByCorrelationIdAndBatchIndex(correlationId, batchIndex);
        if (barrierLookup.isEmpty()) {
            log.warn("No barrier for flow={} batch={} — ignoring failed event", correlationId, batchIndex);
            return;
        }
        BatchBarrier barrier = barrierLookup.get();

        if (barrier.getStatus() != BarrierStatus.OPEN) {
            log.info("Barrier flow={} batch={} already {} — ignoring duplicate failed event",
                    correlationId, batchIndex, barrier.getStatus());
            return;
        }

        barrier.incFailed();
        boolean retryable = Optional.ofNullable(taskCommand.getError())
                .map(TaskCommand.ErrorInfo::getRetryable)
                .orElse(false);

        if (!retryable) {
            barrier.fail();
            repo.save(barrier);
            log.error("Batch {} failed for flow={} (action={} non-retryable)",
                    batchIndex, correlationId, actionName(taskCommand));
            runAfterCommit(() -> {
                tmf701.patchProcessFlowState(correlationId, STATE_FAILED);
                taskCommandsPublisher.publishFailed(correlationId,
                        "action " + actionName(taskCommand) + " non-retryable");
            });
        } else {
            repo.save(barrier);
        }
    }

    private void promoteNextBatch(String correlationId, String dagKey, short closedIndex) {
        // Kickout (per-batch defensive sweep): re-check every task_execution
        // row in the batch that just closed. A rogue response that slipped
        // past handleCompleted's per-action check (e.g. validation logic
        // tightened between writes) gets caught here before we seed the next
        // batch. Cheap: single indexed query on (correlation_id, batch_index).
        Optional<String> batchKickout = detectBatchKickout(correlationId, closedIndex);
        if (batchKickout.isPresent()) {
            log.error("Kickout (batch) flow={} batch={} reason={}",
                    correlationId, closedIndex, batchKickout.get());
            runAfterCommit(() -> {
                tmf701.patchProcessFlowState(correlationId, STATE_FAILED);
                taskCommandsPublisher.publishFailed(correlationId, batchKickout.get());
            });
            return;
        }

        Optional<DagDefinition> dagLookup = dagRegistry.find(dagKey);
        if (dagLookup.isEmpty()) {
            log.warn("DAG {} not found during promotion for flow={}", dagKey, correlationId);
            return;
        }
        DagDefinition dag = dagLookup.get();
        int nextIndex = closedIndex + 1;

        Optional<DagDefinition.BatchDef> nextBatch = dag.batch(nextIndex);
        if (nextBatch.isEmpty()) {
            log.info("All batches closed for flow={} — marking processFlow completed", correlationId);
            runAfterCommit(() -> {
                tmf701.patchProcessFlowState(correlationId, STATE_COMPLETED);
                taskCommandsPublisher.publishCompleted(correlationId);
            });
            return;
        }

        // Fetch the processFlow from TMF-701 — no in-memory cache needed
        ProcessFlow processFlow = tmf701.getProcessFlow(correlationId).orElse(null);
        if (processFlow == null) {
            log.warn("Could not fetch processFlow {} from TMF-701 — promoting with null processFlow",
                    correlationId);
        }

        seedAndPublishBatch(correlationId, dag, nextBatch.get(), processFlow);
    }

    private void seedAndPublishBatch(final String correlationId, final DagDefinition dag,
                                      final DagDefinition.BatchDef batch,
                                      final ProcessFlow processFlow) {
        // Guard against a batch with null/empty actions — a DAG YAML that got
        // through the loader but carries an empty batch would NPE on
        // getActions().size() or publish zero task.execute commands (infinite
        // wait). Fail the flow cleanly instead.
        List<DagDefinition.ActionDef> actions = batch.getActions();
        if (actions == null || actions.isEmpty()) {
            log.error("DAG {} batch {} has no actions — marking flow {} failed",
                    dag.getDagKey(), batch.getIndex(), correlationId);
            runAfterCommit(() -> {
                tmf701.patchProcessFlowState(correlationId, STATE_FAILED);
                taskCommandsPublisher.publishFailed(correlationId,
                        "DAG " + dag.getDagKey() + " batch " + batch.getIndex() + " has no actions");
            });
            return;
        }

        BatchBarrier barrier = new BatchBarrier();
        barrier.setId(new BatchBarrierId(correlationId, (short) batch.getIndex()));
        barrier.setDagKey(dag.getDagKey());
        barrier.setTaskTotal(actions.size());
        barrier.open();
        repo.save(barrier);

        log.info("Seeded barrier batch {} for flow {} (total={})",
                batch.getIndex(), correlationId, actions.size());

        // Build commands and resolve dependencies inside the transaction (needs DB reads),
        // but defer the Kafka publishes until AFTER the transaction commits. This guarantees
        // that task.execute messages are never published for a flow that rolled back.
        Map<String, String> depResults = batchResolveDependencies(correlationId, batch);
        List<TaskCommand> commandsToPublish = actions.stream()
                .flatMap(action -> {
                    Map<String, Object> depsForAction = dependencyResultsFor(action, depResults);
                    return taskCommandFactory.buildTaskExecute(
                            correlationId, dag.getDagKey(), batch, action,
                            processFlow, depsForAction).stream();
                })
                .toList();

        publishAfterCommit(commandsToPublish);
    }

    /**
     * Registers a post-commit callback that publishes the given commands to Kafka.
     * If no transaction is active, publishes synchronously (e.g. in tests).
     */
    private void publishAfterCommit(List<TaskCommand> commands) {
        if (commands.isEmpty()) {
            return;
        }
        runAfterCommit(() -> commands.forEach(publisher::publish));
    }

    /**
     * Runs the given action after the current DB transaction commits. If no transaction
     * is active, runs it immediately (e.g. in tests). This keeps blocking operations
     * (HTTP calls, Kafka publishes) out of the DB transaction so we don't hold the
     * connection for the duration of a remote call.
     *
     * <p>Exceptions from the post-commit hook are caught and logged at ERROR
     * level: the transaction has already committed, so rethrowing would only
     * produce a Spring-internal stack trace buried under
     * {@code TransactionSynchronizationUtils.invokeAfterCommit} without any
     * business context. Catching here lets us log the processFlow/action
     * coordinates the caller would otherwise lose. The downstream system
     * eventually re-synchronizes via the next event or a manual retry.</p>
     */
    private void runAfterCommit(Runnable action) {
        Runnable guarded = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.error("Post-commit hook failed (TMF-701 / Kafka publish): exception={}", e.toString(), e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            guarded.run();
                        }
                    });
        } else {
            guarded.run();
        }
    }

    /**
     * Batch-loads dependency results for every action in a batch in a single query.
     * Returns a map: actionName -> latest COMPLETED resultJson.
     *
     * <p>Replaces the previous N+1 pattern (one query per dependency per action).
     * For a batch with K total unique dependencies across its actions, this issues
     * exactly 1 SQL query instead of up to K.</p>
     */
    private Map<String, String> batchResolveDependencies(final String correlationId,
                                                          final DagDefinition.BatchDef batch) {
        List<DagDefinition.ActionDef> actions = batch.getActions();
        if (actions == null || actions.isEmpty()) {
            return Map.of();
        }
        List<String> allDeps = actions.stream()
                .filter(Objects::nonNull)
                .map(DagDefinition.ActionDef::getDependsOn)
                .filter(deps -> deps != null && !deps.isEmpty())
                .flatMap(List::stream)
                .distinct()
                .toList();

        if (allDeps.isEmpty()) {
            return Map.of();
        }

        // Single query ordered by createdAt DESC — first occurrence per actionName is the latest.
        // Always scans across partitions: a dependency from a prior batch may have been written
        // on an earlier day than this batch is being seeded on, and a partial today-only result
        // would silently drop legitimate dependencies.
        Map<String, String> latestByAction = new HashMap<>();
        taskExecutionRepo.findCompletedByCorrelationIdAndActionNames(correlationId, allDeps)
                .forEach(te -> latestByAction.putIfAbsent(te.getActionName(), te.getResultJson()));
        return latestByAction;
    }

    /**
     * Extracts dependency results for a single action from the pre-loaded batch map.
     * Logs a warning for any declared dependency that has no COMPLETED row.
     */
    private Map<String, Object> dependencyResultsFor(DagDefinition.ActionDef action,
                                                      Map<String, String> batchResolved) {
        List<String> dependsOn = action.getDependsOn();
        if (dependsOn == null || dependsOn.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> results = new HashMap<>();
        for (String dep : dependsOn) {
            String json = batchResolved.get(dep);
            if (json != null) {
                results.put(dep, json);
            } else {
                log.warn("Dependency {} not found for action {} in flow (no completed row)",
                        dep, action.getActionName());
            }
        }
        return results;
    }

    /**
     * PATCHes the parent processFlow's {@code relatedEntity} array with the
     * completed task's id + href pair — mirrors the upstream
     * {@code ActionBuilder.enrichProcessFlowWithRelatedEntity} contract,
     * which fails closed when either identifier is missing rather than
     * writing a half-populated entity the downstream can't resolve.
     *
     * <p>Skip conditions (any one of these aborts the PATCH):
     * <ul>
     *   <li>{@code result} or {@code action} missing — nothing to patch with</li>
     *   <li>{@code result.id} null/blank — the {@code relatedEntity.id} slot</li>
     *   <li>{@code taskFlowResponse.href} null/blank — the {@code relatedEntity.href} slot</li>
     * </ul>
     * Both slots are required by the TMF-701 RelatedEntity schema; sending
     * one without the other produces a reference the consumer cannot follow.</p>
     */
    private void patchParentProcessFlow(TaskCommand taskCommand, String correlationId) {
        ActionResponse result = taskCommand.getResult();
        if (result == null || taskCommand.getAction() == null) {
            return;
        }
        String taskId = result.getId();
        if (taskId == null || taskId.isBlank()) {
            log.warn("Skipping processFlow {} patch — missing relatedEntity.id (actionName={})",
                    correlationId, taskCommand.getAction().getActionName());
            return;
        }
        String taskFlowHref = extractTaskFlowHref(result);
        if (taskFlowHref == null || taskFlowHref.isBlank()) {
            log.warn("Skipping processFlow {} patch — missing relatedEntity.href for task id={} actionName={}",
                    correlationId, taskId, taskCommand.getAction().getActionName());
            return;
        }
        tmf701.patchProcessFlowAddTaskFlowRef(
                correlationId,
                taskId,
                taskFlowHref,
                taskCommand.getAction().getActionName());
    }

    /**
     * Returns a non-empty reason if the {@link ActionResponse} attached to a
     * COMPLETED task.event qualifies as a <b>kickout</b>. A task-runner can
     * report COMPLETED at the envelope level while the embedded actionResponse
     * indicates a business failure — that mismatch must not advance the flow.
     *
     * <p>Kickout triggers:</p>
     * <ul>
     *   <li>{@code actionResponse.taskStatusCode} is present and not COMPLETED</li>
     *   <li>{@code taskFlowResponse.characteristic[name=status]} is present and not "pass"</li>
     * </ul>
     * A missing result is NOT a kickout — the envelope-level COMPLETED is trusted.
     *
     * @return reason string if the result is a kickout, empty if it's valid
     */
    private Optional<String> detectKickout(TaskCommand taskCommand) {
        ActionResponse result = taskCommand.getResult();
        if (result == null) {
            return Optional.empty();
        }

        String taskStatusCode = result.getTaskStatusCode();
        if (taskStatusCode != null && !TASK_STATUS_COMPLETED.equalsIgnoreCase(taskStatusCode)) {
            return Optional.of("actionResponse.taskStatusCode=" + taskStatusCode);
        }

        TaskFlow taskFlowResponse = result.getTaskFlowResponse();
        if (taskFlowResponse != null && taskFlowResponse.getCharacteristic() != null) {
            for (ProcessFlow.Characteristic c : taskFlowResponse.getCharacteristic()) {
                if (c == null || !STATUS_CHARACTERISTIC.equalsIgnoreCase(c.getName())) {
                    continue;
                }
                String status = c.getValue();
                if (status != null && !STATUS_PASS.equalsIgnoreCase(status)) {
                    return Optional.of("actionResponse.taskFlowResponse.status=" + status);
                }
                break;
            }
        }

        return Optional.empty();
    }

    /**
     * Re-scans every TaskExecution row in the just-closed batch for a kickout
     * signal, using the same rules as {@link #detectKickout}. Returns the
     * first non-empty reason. Designed as a defensive check — the per-action
     * path in {@link #handleCompleted} is the primary gate.
     */
    private Optional<String> detectBatchKickout(final String correlationId, final short batchIndex) {
        List<TaskExecution> rows = taskExecutionRepo
                .findAllByCorrelationIdAndBatchIndex(correlationId, batchIndex);
        for (TaskExecution te : rows) {
            if (te.getStatus() == TaskStatus.FAILED || te.getStatus() == TaskStatus.CANCELLED) {
                return Optional.of("batch " + batchIndex + " had " + te.getStatus()
                        + " action=" + te.getActionName());
            }
        }
        return Optional.empty();
    }

    /**
     * Short-circuits an in-flight flow: marks the current barrier failed,
     * PATCHes the TMF-701 processFlow to {@code failed}, and publishes a
     * {@code flow.lifecycle} failed event. Called from the per-action kickout
     * check in {@link #handleCompleted}.
     *
     * <p>Other open barriers on the same flow are intentionally left alone.
     * Two reasons:</p>
     * <ul>
     *   <li>The flow-level signal has already been sent (processFlow=failed
     *       plus {@code flow.lifecycle} FAILED) — downstream consumers stop
     *       reacting to this flow immediately.</li>
     *   <li>Sibling barriers either naturally close on their own completed
     *       event stream or get trimmed by the pg_cron retention job. Forcing
     *       them closed here would mask whatever state their in-flight tasks
     *       eventually produce and make diagnosis harder.</li>
     * </ul>
     */
    private void kickoutFlow(final String correlationId, final BatchBarrier currentBarrier,
                              final String reason) {
        log.error("Kickout flow={} reason={}", correlationId, reason);
        currentBarrier.fail();
        repo.save(currentBarrier);
        runAfterCommit(() -> {
            tmf701.patchProcessFlowState(correlationId, STATE_FAILED);
            taskCommandsPublisher.publishFailed(correlationId, reason);
        });
    }

    /**
     * Pulls the taskFlow href from the typed TMF-701 {@link TaskFlow} carried
     * in {@link ActionResponse#getTaskFlowResponse()}. The raw
     * {@code taskResult} field is intentionally ignored here — it's the
     * downstream's business payload, not TMF resource metadata.
     *
     * @return the href when populated, or {@code null} when the caller should
     *         skip the relatedEntity PATCH entirely (never an empty string —
     *         that would produce an invalid TMF relatedEntity reference)
     */
    private static String extractTaskFlowHref(ActionResponse result) {
        TaskFlow taskFlowResponse = result.getTaskFlowResponse();
        if (taskFlowResponse == null) {
            return null;
        }
        String href = taskFlowResponse.getHref();
        return (href != null && !href.isBlank()) ? href : null;
    }

    // ---- null-safe field extractors for logging ----

    private static String actionName(TaskCommand taskCommand) {
        return Optional.ofNullable(taskCommand.getAction())
                .map(TaskCommand.Action::getActionName).orElse(UNKNOWN);
    }

    private static String taskId(TaskCommand taskCommand) {
        return Optional.ofNullable(taskCommand.getResult())
                .map(ActionResponse::getId).orElse(UNKNOWN);
    }

    private static String awaitingSignalId(TaskCommand taskCommand) {
        return Optional.ofNullable(taskCommand.getAwaitingSignal())
                .map(TaskCommand.AwaitingSignal::getDownstreamTransactionId).orElse(UNKNOWN);
    }
}
