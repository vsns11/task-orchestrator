package ca.siva.orchestrator.service;

import ca.siva.orchestrator.client.Tmf701Client;
import ca.siva.orchestrator.dag.DagDefinition;
import ca.siva.orchestrator.dag.DagDefinition.ActionDef;
import ca.siva.orchestrator.dag.DagDefinition.BatchDef;
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
 * <h3>Per-event side effects (mid-flow)</h3>
 * <p>Every COMPLETED / FAILED / CANCELLED task event triggers a TMF-701
 * "ref-only" PATCH on the parent processFlow ({@link #patchParentProcessFlow})
 * — appending an entry to BOTH {@code relatedEntity[]} and the typed
 * {@code taskFlow[]} array (mirrors the legacy {@code enrichProcessFlow}
 * contract that called {@code addRelatedEntityItem(...)} and
 * {@code addTaskFlowItem(...)} in lock-step). The mid-flow PATCH body
 * carries ONLY those two arrays — no {@code state}, no {@code characteristic},
 * no {@code id}. Downstream consumers can follow the link before the flow as
 * a whole has finished. Mid-flow events do NOT publish any FLOW_LIFECYCLE
 * event and do NOT send the legacy-shape final-state PATCH. Both of those
 * are reserved for end-of-flow / kickout transitions.</p>
 *
 * <h3>Kickout — single decision point at batch close</h3>
 * <p>Kickout is evaluated exactly once per batch: when the barrier drains
 * ({@code pending() == 0}) {@link BatchResultsLoader} reads every persisted
 * {@link TaskExecution} row for that {@code (correlationId, batchIndex)}
 * and parses each {@code result_json} payload into an {@link ActionResponse}.
 * The same in-memory {@link BatchResults} snapshot is then handed to
 * {@link BatchKickoutEvaluator#evaluate(BatchResults)} for the kickout
 * decision and — on the kickout / end-of-flow paths — to
 * {@link ProcessFlowCompletionService} for the final-state PATCH cascade.
 * A row whose persisted status is {@code FAILED}/{@code CANCELLED}, or whose
 * deserialized {@link ActionResponse} carries a {@code taskStatus}
 * characteristic value other than {@code Passed}, fails the batch.</p>
 * <p>The two outcomes:</p>
 * <ul>
 *   <li><b>Promote (mid-flow)</b> — clean batch with a next batch in the
 *       DAG. {@link #closeBatchAndDecide} closes the barrier and calls
 *       {@link #promoteNextBatch}, which seeds the next batch. No final-state
 *       PATCH and no FLOW_LIFECYCLE event are fired here — the
 *       {@code relatedEntity[]} / {@code taskFlow[]} entries accumulated by
 *       per-event PATCHes remain on the server (merge-patch leaves them
 *       alone).</li>
 *   <li><b>End of flow</b> — clean batch with NO next batch in the DAG.
 *       {@link #promoteNextBatch} dispatches the {@code state=completed}
 *       final-state PATCH (with derived {@code processStatus} /
 *       {@code statusChangeReason} characteristics) and publishes
 *       FLOW_LIFECYCLE COMPLETED. This is the FIRST point a final-state
 *       PATCH is sent on the happy path.</li>
 *   <li><b>Kickout</b> — drained batch contains a FAILED / CANCELLED row or
 *       a non-Passed taskStatus. Fail the barrier, dispatch the
 *       {@code state=failed} final-state PATCH (with derived
 *       {@code processStatus} / {@code statusChangeReason} characteristics),
 *       and publish FLOW_LIFECYCLE FAILED. NO further batches are seeded.</li>
 * </ul>
 * <p><b>Strict separation of PATCH shapes.</b> The mid-flow ref PATCH and
 * the final-state PATCH never overlap on the wire: the former carries only
 * {@code relatedEntity[]} + {@code taskFlow[]}, the latter only {@code id} +
 * {@code state} + {@code characteristic[]}. Each path is exclusive — a
 * mid-flow event never updates {@code processStatus} / {@code statusChangeReason}
 * and a final-state PATCH never re-touches the ref arrays.</p>
 * <p>Persisting first / inspecting second eliminates an entire category of
 * "in-memory counter says one thing, DB row says another" race conditions.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BarrierService {

    // ---- constants ----

    /** Retry budget for the JPA-conflict retries on the public entry points. */
    private static final int    RETRY_MAX_ATTEMPTS    = 5;
    private static final long   RETRY_INITIAL_DELAY_MS = 100L;
    private static final double RETRY_MULTIPLIER      = 2.0;
    private static final long   RETRY_MAX_DELAY_MS    = 2000L;

    /** Unknown identifier used in log messages when a field is absent. */
    private static final String UNKNOWN = "?";

    private final BatchBarrierRepository       repo;
    private final TaskExecutionRepository      taskExecutionRepo;
    private final DagRegistry                  dagRegistry;
    private final TaskCommandFactory           taskCommandFactory;
    private final TaskCommandPublisher         publisher;
    private final Tmf701Client                 tmf701;
    private final TaskEventsPublisher          taskCommandsPublisher;
    /**
     * Builds and dispatches the legacy-shape final-state PATCH (id + state +
     * processStatus / statusChangeReason characteristics) at flow end. The
     * mid-flow {@code relatedEntity[]} / {@code taskFlow[]} ref-only PATCHes
     * still go through {@link Tmf701Client} directly — the completion service
     * is invoked ONLY when the barrier has drained AND there is no next batch
     * (happy end-of-flow) or a kickout has been detected on the closing
     * batch. It is never invoked on a per-action basis.
     */
    private final ProcessFlowCompletionService completion;
    /**
     * Single-responsibility owner of the kickout decision. Inspects every
     * persisted {@link TaskExecution} for the batch and returns a non-empty
     * reason when the batch should fail instead of promote.
     */
    private final BatchKickoutEvaluator        kickoutEvaluator;
    /**
     * Single-trip loader for a batch's persisted task-execution rows + their
     * already-deserialized action responses. Owned at this layer so the same
     * snapshot drives the kickout decision AND the final-state PATCH cascade
     * — one DB query, one parse pass per batch close.
     */
    private final BatchResultsLoader           batchResultsLoader;

    /**
     * Seeds the first batch barrier and publishes task.execute commands
     * for all actions in batch 0 of the resolved DAG.
     *
     * @param correlationId the Kafka correlationId (= TMF-701 processFlow UUID)
     * @param dagKey        which DAG to execute
     * @param processFlow   the typed processFlow object from the initiated event
     */
    @Retryable(retryFor = {OptimisticLockingFailureException.class, DataIntegrityViolationException.class},
               maxAttempts = RETRY_MAX_ATTEMPTS,
               backoff = @Backoff(delay = RETRY_INITIAL_DELAY_MS,
                                  multiplier = RETRY_MULTIPLIER,
                                  maxDelay = RETRY_MAX_DELAY_MS))
    @Transactional
    public void initiateFlow(String correlationId, String dagKey, ProcessFlow processFlow) {
        Optional<DagDefinition> dagLookup = dagRegistry.find(dagKey);
        if (dagLookup.isEmpty()) {
            log.warn("Unknown dagKey={} for flow={} — skipping", dagKey, correlationId);
            return;
        }
        DagDefinition dag = dagLookup.get();

        Optional<BatchDef> firstBatch = dag.batch(0);
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
               maxAttempts = RETRY_MAX_ATTEMPTS,
               backoff = @Backoff(delay = RETRY_INITIAL_DELAY_MS,
                                  multiplier = RETRY_MULTIPLIER,
                                  maxDelay = RETRY_MAX_DELAY_MS))
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

    /**
     * Records a COMPLETED task event against its batch barrier. NO kickout
     * decision is taken here — the per-action {@link ActionResponse} is
     * already persisted on the {@code task_execution} row by
     * {@link TaskExecutionService}, and the kickout sweep at batch close
     * ({@link #closeBatchAndDecide}) is the single place that inspects the
     * batch's persisted responses.
     *
     * <p>Side effects of a mid-flow COMPLETED event:</p>
     * <ol>
     *   <li>Schedule a relatedEntity PATCH on the parent processFlow — runs
     *       AFTER the DB transaction commits so we never hold a HikariCP
     *       connection open across the HTTP call.</li>
     *   <li>Increment {@code taskCompleted} on the barrier. When
     *       {@code pending() == 0} the batch drains and
     *       {@link #closeBatchAndDecide} runs the kickout sweep.</li>
     * </ol>
     */
    private void handleCompleted(TaskCommand taskCommand, String correlationId, short batchIndex) {
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

        barrier.incCompleted();
        log.info("Barrier flow={} batch={}: completed={} failed={} pending={}",
                correlationId, batchIndex, barrier.getTaskCompleted(),
                barrier.getTaskFailed(), barrier.pending());

        if (barrier.pending() == 0) {
            closeBatchAndDecide(barrier, correlationId, batchIndex);
        } else {
            repo.save(barrier);
        }
    }

    /**
     * Records a FAILED / CANCELLED task event against its batch barrier.
     *
     * <p>Retryable failures bypass every counter — the task-runner will
     * redrive the action and the eventual COMPLETED/FAILED outcome is what
     * advances the barrier. Bumping {@code taskFailed} on a retryable
     * failure would fake a draining batch and trigger a premature kickout.</p>
     *
     * <p>Non-retryable failures bump {@code taskFailed} (so {@code pending()}
     * sees one fewer outstanding task) and, when the batch drains, defer to
     * {@link #closeBatchAndDecide} — which inspects the persisted rows and
     * fails the flow at that single decision point.</p>
     */
    private void handleFailed(TaskCommand taskCommand, String correlationId, short batchIndex) {
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

        boolean retryable = Optional.ofNullable(taskCommand.getError())
                .map(TaskCommand.ErrorInfo::getRetryable)
                .orElse(false);

        if (retryable) {
            log.info("Retryable failure for action={} flow={} batch={} — barrier counters unchanged",
                    actionName(taskCommand), correlationId, batchIndex);
            return;
        }

        barrier.incFailed();
        log.error("Non-retryable failure recorded flow={} batch={} action={} (deferred until batch closes): completed={} failed={} pending={}",
                correlationId, batchIndex, actionName(taskCommand),
                barrier.getTaskCompleted(), barrier.getTaskFailed(), barrier.pending());

        if (barrier.pending() == 0) {
            closeBatchAndDecide(barrier, correlationId, batchIndex);
        } else {
            repo.save(barrier);
        }
    }

    /**
     * Resolves a fully-drained batch (pending == 0) into one of two outcomes
     * by inspecting every persisted {@link TaskExecution} row in the batch:
     * <ul>
     *   <li><b>Promote</b> — every action ended cleanly (status COMPLETED
     *       AND no kickout signal in the persisted ActionResponse). Marks
     *       the barrier CLOSED and calls {@link #promoteNextBatch}, which
     *       seeds the next batch (mid-flow: relatedEntity PATCHes only, no
     *       lifecycle event) or, when no next batch exists, dispatches the
     *       {@code completed} final-state PATCH and publishes the FLOW_
     *       LIFECYCLE COMPLETED event.</li>
     *   <li><b>Kickout</b> — at least one persisted row reports a failure
     *       (status FAILED/CANCELLED, or a deserialized ActionResponse with
     *       a non-COMPLETED taskStatusCode / non-pass status characteristic).
     *       Marks the barrier FAILED, dispatches the {@code failed}
     *       final-state PATCH, and publishes FLOW_LIFECYCLE FAILED. No
     *       further batches are seeded.</li>
     * </ul>
     *
     * <p>Single decision point + single source of truth (persisted rows)
     * eliminates the in-memory-counter-vs-DB-row split-brain class of bugs
     * the per-action approach was prone to.</p>
     */
    private void closeBatchAndDecide(BatchBarrier barrier, String correlationId, short batchIndex) {
        // SINGLE DB hit + SINGLE parse pass per batch close. The same snapshot
        // is handed to both the kickout decision and the final-state PATCH
        // cascade so neither collaborator re-queries the rows.
        BatchResults batch = batchResultsLoader.load(correlationId, batchIndex);
        Optional<String> kickoutReason = kickoutEvaluator.evaluate(batch);

        if (kickoutReason.isPresent()) {
            barrier.fail();
            repo.save(barrier);
            String reason = "batch " + batchIndex + " kickout: " + kickoutReason.get();
            log.error("Kickout (batch closed) flow={} batch={} reason={}",
                    correlationId, batchIndex, reason);
            runAfterCommit(() -> {
                completion.patchFailed(correlationId, batch.responses());
                taskCommandsPublisher.publishFailed(correlationId, reason);
            });
            return;
        }

        barrier.close();
        repo.save(barrier);
        log.info("Batch {} closed cleanly for flow={} — promoting next batch", batchIndex, correlationId);
        promoteNextBatch(correlationId, barrier.getDagKey(), batchIndex, batch.responses());
    }

    private void promoteNextBatch(String correlationId, String dagKey, short closedIndex,
                                  List<ActionResponse> closingBatchResponses) {
        // Kickout sweep already ran in closeBatchAndDecide() — by the time we
        // reach here the barrier has been authoritatively decided as CLOSED
        // (clean). promoteNextBatch is purely the "happy path follow-up":
        // look up the DAG, find the next batch, seed it (or fire the
        // completed final-state PATCH when no next batch exists).

        Optional<DagDefinition> dagLookup = dagRegistry.find(dagKey);
        if (dagLookup.isEmpty()) {
            log.warn("DAG {} not found during promotion for flow={}", dagKey, correlationId);
            return;
        }
        DagDefinition dag = dagLookup.get();
        int nextIndex = closedIndex + 1;

        Optional<BatchDef> nextBatch = dag.batch(nextIndex);
        if (nextBatch.isEmpty()) {
            // True end-of-flow: send the legacy-shape final-state completed PATCH
            // (state + processStatus / reason characteristics) so downstream
            // consumers see the same payload they did under the Bonita
            // workflow. The relatedEntity refs accumulated across the batch
            // remain on the server (merge-patch leaves them untouched).
            log.info("All batches closed for flow={} — marking processFlow completed", correlationId);
            runAfterCommit(() -> {
                completion.patchCompleted(correlationId, closingBatchResponses);
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

    private void seedAndPublishBatch(String correlationId, DagDefinition dag,
                                      BatchDef batch, ProcessFlow processFlow) {
        // Guard against a batch with null/empty actions — a DAG YAML that got
        // through the loader but carries an empty batch would NPE on
        // getActions().size() or publish zero task.execute commands (infinite
        // wait). Fail the flow cleanly instead.
        List<ActionDef> actions = batch.getActions();
        if (actions == null || actions.isEmpty()) {
            log.error("DAG {} batch {} has no actions — marking flow {} failed",
                    dag.getDagKey(), batch.getIndex(), correlationId);
            // Empty responses list correctly drives the cascade to
            // PROCESS_STATUS_FAILED + REASON_NO_TASK — the legacy
            // "no actionResponseList" arm. We have no rows to load because
            // the batch never produced any work.
            runAfterCommit(() -> {
                completion.patchFailed(correlationId, List.of());
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
    private Map<String, String> batchResolveDependencies(String correlationId, BatchDef batch) {
        List<ActionDef> actions = batch.getActions();
        if (actions == null || actions.isEmpty()) {
            return Map.of();
        }
        List<String> allDeps = actions.stream()
                .filter(Objects::nonNull)
                .map(ActionDef::getDependsOn)
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
    private Map<String, Object> dependencyResultsFor(ActionDef action, Map<String, String> batchResolved) {
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
     * PATCHes the parent processFlow's {@code relatedEntity[]} AND
     * {@code taskFlow[]} arrays with the just-completed task's id + href pair
     * — mirrors the upstream {@code enrichProcessFlow} contract, which calls
     * {@code addRelatedEntityItem(...)} and {@code addTaskFlowItem(...)} in
     * lock-step so both untyped (TMF-630) and typed (TMF-701) consumers see
     * the new taskFlow.
     *
     * <p><b>Per-action only.</b> This method is the ONLY place that
     * mutates the ref arrays. It runs once per COMPLETED / FAILED /
     * CANCELLED task event and never touches {@code state} or
     * {@code processStatus} / {@code statusChangeReason} characteristics —
     * those are the exclusive domain of the final-state PATCH dispatched
     * by {@link ProcessFlowCompletionService} when the barrier drains and
     * either no next batch exists or a kickout is detected.</p>
     *
     * <p>Skip conditions (any one of these aborts the PATCH and writes
     * neither array):
     * <ul>
     *   <li>{@code result} or {@code action} missing — nothing to patch with</li>
     *   <li>{@code result.id} null/blank — the {@code relatedEntity.id} /
     *       {@code taskFlow.id} slot</li>
     *   <li>{@code taskFlowResponse.href} null/blank — the
     *       {@code relatedEntity.href} / {@code taskFlow.href} slot</li>
     * </ul>
     * Both slots are required by the TMF-701 schema; sending one without
     * the other produces a reference the consumer cannot follow.</p>
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
