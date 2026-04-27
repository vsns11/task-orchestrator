package ca.siva.orchestrator.service;

import ca.siva.orchestrator.client.Tmf701Client;
import ca.siva.orchestrator.dag.DagDefinition;
import ca.siva.orchestrator.dag.DagRegistry;
import ca.siva.orchestrator.domain.BarrierStatus;
import ca.siva.orchestrator.domain.ExecutionMode;
import ca.siva.orchestrator.domain.TaskStatus;
import ca.siva.orchestrator.dto.ActionResponse;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import ca.siva.orchestrator.dto.tmf.TaskFlow;
import ca.siva.orchestrator.entity.BatchBarrier;
import ca.siva.orchestrator.entity.BatchBarrierId;
import ca.siva.orchestrator.kafka.TaskCommandFactory;
import ca.siva.orchestrator.kafka.TaskCommandPublisher;
import ca.siva.orchestrator.kafka.TaskEventsPublisher;
import ca.siva.orchestrator.repository.BatchBarrierRepository;
import ca.siva.orchestrator.repository.TaskExecutionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BarrierServiceTest {

    @Mock BatchBarrierRepository repo;
    @Mock TaskExecutionRepository taskExecutionRepo;
    @Mock DagRegistry dagRegistry;
    @Mock TaskCommandFactory taskCommandFactory;
    @Mock TaskCommandPublisher publisher;
    @Mock Tmf701Client tmf701;
    @Mock TaskEventsPublisher taskEventsPublisher;
    /**
     * Final-state PATCHes (state + processStatus / statusChangeReason
     * characteristics) are dispatched through this collaborator now —
     * {@link Tmf701Client#patchProcessFlowState} is no longer used for
     * end-of-flow events, only the legacy method is verified here as a back-stop.
     */
    @Mock ProcessFlowCompletionService completion;
    /**
     * Single-responsibility owner of the kickout decision. Mocked so each
     * test can stub the decision directly with {@code Optional.of(reason)}
     * (kickout) or {@code Optional.empty()} (clean) without standing up
     * the row → JSON → ActionResponse parse pipeline.
     */
    @Mock BatchKickoutEvaluator kickoutEvaluator;
    /**
     * Single-trip loader for the per-batch persisted rows + parsed action
     * responses. Mocked so each test can hand back exactly the
     * {@link BatchResults} snapshot the SUT should see — proving by stub
     * cardinality that the load happens at most ONCE per batch close and
     * that the same snapshot drives both the kickout decision and the
     * final-state PATCH.
     */
    @Mock BatchResultsLoader batchResultsLoader;
    @InjectMocks BarrierService service;

    @Test
    void initiateFlow_seedsBarrierAndPublishesCommands() {
        var dag = dagWithBatches(2);
        when(dagRegistry.find("TestDAG")).thenReturn(Optional.of(dag));
        when(repo.existsByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(false);
        when(taskCommandFactory.buildTaskExecute(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new TaskCommand()));

        service.initiateFlow("corr-1", "TestDAG", ProcessFlow.builder().id("flow-1").build());

        verify(repo).save(any(BatchBarrier.class));
        verify(publisher, times(2)).publish(any());
    }

    @Test
    void initiateFlow_publishesLifecycleInitialBeforeTaskExecute() {
        // Contract: downstream consumers must see flow.lifecycle INITIAL BEFORE
        // any task.execute command for that flow. Post-commit hooks run in
        // registration order, so initiateFlow(...) must register the lifecycle
        // hook before seedAndPublishBatch(...) registers the task.execute hooks.
        var dag = dagWithBatches(2);
        when(dagRegistry.find("TestDAG")).thenReturn(Optional.of(dag));
        when(repo.existsByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(false);
        when(taskCommandFactory.buildTaskExecute(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new TaskCommand()));

        service.initiateFlow("corr-1", "TestDAG", ProcessFlow.builder().id("flow-1").build());

        var inOrder = inOrder(taskEventsPublisher, publisher);
        inOrder.verify(taskEventsPublisher).publishInitiated("corr-1", "TestDAG");
        inOrder.verify(publisher, times(2)).publish(any());
    }

    @Test
    void initiateFlow_alreadySeeded_skips() {
        when(dagRegistry.find("TestDAG")).thenReturn(Optional.of(dagWithBatches(2)));
        when(repo.existsByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(true);

        service.initiateFlow("corr-1", "TestDAG", ProcessFlow.builder().build());

        verify(repo, never()).save(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void initiateFlow_unknownDag_skips() {
        when(dagRegistry.find("UnknownDAG")).thenReturn(Optional.empty());
        service.initiateFlow("corr-1", "UnknownDAG", ProcessFlow.builder().build());
        verifyNoInteractions(repo, publisher);
    }

    @Test
    void applyTaskEvent_completed_closesWhenPendingZero() {
        var barrier = barrierWithTotal(1, 0);
        when(repo.findByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(Optional.of(barrier));
        // Only 1 batch in DAG — after closing batch 0, flow completes
        when(dagRegistry.find("TestDAG")).thenReturn(Optional.of(dagWithBatches(1)));
        // Clean batch: loader returns whatever rows / responses the test
        // doesn't care about, evaluator says "no kickout".
        when(batchResultsLoader.load(eq("corr-1"), eq((short) 0)))
                .thenReturn(emptyBatchResults());
        when(kickoutEvaluator.evaluate(any(BatchResults.class))).thenReturn(Optional.empty());

        var taskCommand = buildTaskCommand(TaskStatus.COMPLETED, 0);
        service.applyTaskEvent(taskCommand);

        assertThat(barrier.getStatus()).isEqualTo(BarrierStatus.CLOSED);
        verify(completion).patchCompleted(anyString(), any());
    }

    @Test
    void applyTaskEvent_completed_doesNotCloseWhenPendingRemains() {
        var barrier = barrierWithTotal(2, 0);
        when(repo.findByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(Optional.of(barrier));

        service.applyTaskEvent(buildTaskCommand(TaskStatus.COMPLETED, 0));

        assertThat(barrier.getStatus()).isEqualTo(BarrierStatus.OPEN);
        assertThat(barrier.getTaskCompleted()).isEqualTo(1);
        // Pending > 0: loader must NOT be invoked. Proves the load is gated
        // on barrier draining, not on every task.event.
        verifyNoInteractions(batchResultsLoader);
    }

    @Test
    void applyTaskEvent_completed_ignoredWhenBarrierAlreadyClosed() {
        var barrier = barrierWithTotal(1, 1);
        barrier.close(); // already CLOSED
        when(repo.findByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(Optional.of(barrier));

        service.applyTaskEvent(buildTaskCommand(TaskStatus.COMPLETED, 0));

        // Should NOT increment completed counter again
        assertThat(barrier.getTaskCompleted()).isEqualTo(1);
        verify(repo, never()).save(any());
    }

    @Test
    void applyTaskEvent_completed_ignoredWhenBarrierNotFound() {
        when(repo.findByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(Optional.empty());
        service.applyTaskEvent(buildTaskCommand(TaskStatus.COMPLETED, 0));
        verify(repo, never()).save(any());
    }

    @Test
    void applyTaskEvent_failed_nonRetryable_deferredUntilBatchCloses() {
        // Deferred-decision contract: a single non-retryable FAILED in a
        // 2-task batch must NOT fail the barrier yet — its sibling has not
        // reported in. taskFailed advances, the barrier stays OPEN, and the
        // final-state PATCH is NOT yet dispatched. (The promote-vs-kickout
        // decision is taken by closeBatchAndDecide once pending == 0.)
        var barrier = barrierWithTotal(2, 0);
        when(repo.findByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(Optional.of(barrier));

        var taskCommand = buildTaskCommand(TaskStatus.FAILED, 0);
        taskCommand.setError(TaskCommand.ErrorInfo.builder().retryable(false).build());
        service.applyTaskEvent(taskCommand);

        assertThat(barrier.getStatus()).isEqualTo(BarrierStatus.OPEN);
        assertThat(barrier.getTaskFailed()).isEqualTo(1);
        verify(completion, never()).patchFailed(anyString(), any());
    }

    @Test
    void applyTaskEvent_failed_nonRetryable_closesAndKicksOutWhenPendingZero() {
        // Counterpart of the deferred test above: a 1-task batch drains
        // immediately on the only action's failure. closeBatchAndDecide
        // loads the batch's rows once via batchResultsLoader and asks the
        // evaluator — which here returns a non-empty kickout reason. Barrier
        // flips to FAILED and the failed final-state PATCH + FLOW_LIFECYCLE
        // FAILED both go out, the latter receiving the SAME responses list
        // the kickout decision saw (zero extra DB load).
        var barrier = barrierWithTotal(1, 0);
        when(repo.findByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(Optional.of(barrier));
        BatchResults snapshot = emptyBatchResults();
        when(batchResultsLoader.load(eq("corr-1"), eq((short) 0))).thenReturn(snapshot);
        when(kickoutEvaluator.evaluate(snapshot))
                .thenReturn(Optional.of("action=testAction row.status=FAILED"));

        var taskCommand = buildTaskCommand(TaskStatus.FAILED, 0);
        taskCommand.setError(TaskCommand.ErrorInfo.builder().retryable(false).build());
        service.applyTaskEvent(taskCommand);

        assertThat(barrier.getStatus()).isEqualTo(BarrierStatus.FAILED);
        verify(completion).patchFailed(eq("corr-1"), eq(snapshot.responses()));
        verify(taskEventsPublisher).publishFailed(anyString(), anyString());
        // The load happened at most once even though it feeds two consumers.
        verify(batchResultsLoader, times(1)).load(anyString(), anyShort());
    }

    @Test
    void applyTaskEvent_failed_retryable_doesNotTouchAnyCounter() {
        // Retryable failure: no counter increment AT ALL. The task-runner
        // will redrive and we'll see the final COMPLETED/FAILED. Bumping
        // taskFailed here would make pending() lie, drain the barrier early,
        // and trigger a premature kickout the moment a single retryable
        // failure landed in a multi-task batch.
        var barrier = barrierWithTotal(2, 0);
        when(repo.findByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(Optional.of(barrier));

        var taskCommand = buildTaskCommand(TaskStatus.FAILED, 0);
        taskCommand.setError(TaskCommand.ErrorInfo.builder().retryable(true).build());
        service.applyTaskEvent(taskCommand);

        assertThat(barrier.getStatus()).isEqualTo(BarrierStatus.OPEN);
        assertThat(barrier.getTaskFailed()).isZero();
        assertThat(barrier.getTaskCompleted()).isZero();
        verify(repo, never()).save(any());
        verify(completion, never()).patchFailed(anyString(), any());
    }

    @Test
    void applyTaskEvent_waiting_doesNotPatchProcessFlow() {
        service.applyTaskEvent(buildTaskCommand(TaskStatus.WAITING, 0));
        verifyNoInteractions(tmf701);
    }

    @Test
    void applyTaskEvent_missingStatus_ignored() {
        var taskCommand = buildTaskCommand(null, 0);
        taskCommand.setStatus(null);
        service.applyTaskEvent(taskCommand);
        verifyNoInteractions(repo, tmf701);
    }

    @Test
    void applyTaskEvent_missingBatchIndex_ignored() {
        var taskCommand = new TaskCommand();
        taskCommand.setEventId("e-1");
        taskCommand.setCorrelationId("corr-1");
        taskCommand.setStatus(TaskStatus.COMPLETED);
        service.applyTaskEvent(taskCommand);
        verifyNoInteractions(repo, tmf701);
    }

    // ---- helpers ----

    private static DagDefinition dagWithBatches(int batchCount) {
        var dag = new DagDefinition();
        dag.setDagKey("TestDAG");
        var batches = new ArrayList<DagDefinition.BatchDef>();
        for (int i = 0; i < batchCount; i++) {
            var batch = new DagDefinition.BatchDef();
            batch.setIndex(i);
            var a1 = new DagDefinition.ActionDef();
            a1.setActionName("action" + (i * 2 + 1));
            a1.setExecutionMode(ExecutionMode.SYNC);
            var a2 = new DagDefinition.ActionDef();
            a2.setActionName("action" + (i * 2 + 2));
            a2.setExecutionMode(ExecutionMode.SYNC);
            batch.setActions(List.of(a1, a2));
            batches.add(batch);
        }
        dag.setBatches(batches);
        return dag;
    }

    private static BatchBarrier barrierWithTotal(int total, int completed) {
        var barrier = new BatchBarrier();
        barrier.setId(new BatchBarrierId("corr-1", (short) 0));
        barrier.setDagKey("TestDAG");
        barrier.setTaskTotal(total);
        barrier.setTaskCompleted(completed);
        barrier.setStatus(BarrierStatus.OPEN);
        return barrier;
    }

    /**
     * An empty {@link BatchResults} — the SUT only forwards {@code responses}
     * to the completion service (which tolerates an empty list as the
     * "no actionResponseList" cascade arm), so no row content is needed.
     * Body-content kickout decisions are exercised in
     * {@code BatchKickoutEvaluatorTest}.
     */
    private static BatchResults emptyBatchResults() {
        return new BatchResults(List.of(), List.of());
    }

    private static TaskCommand buildTaskCommand(TaskStatus status, int batchIndex) {
        var taskCommand = new TaskCommand();
        taskCommand.setEventId("e-" + System.nanoTime());
        taskCommand.setCorrelationId("corr-1");
        taskCommand.setStatus(status);
        taskCommand.setBatch(TaskCommand.Batch.builder().index(batchIndex).build());
        taskCommand.setAction(TaskCommand.Action.builder()
                .actionName("testAction").actionCode("TEST_ACTION").build());
        // Payload shape mirrors what task-runners emit. The taskFlowResponse
        // is left intentionally bare here — kickout body-content rules are
        // exercised in BatchKickoutEvaluatorTest, BarrierServiceTest only
        // proves the orchestration loop wires the right collaborators.
        taskCommand.setResult(ActionResponse.builder()
                .name("testAction").code("TEST_ACTION").id("tf-1").type("TaskFlow")
                .taskResult(Map.of("downstream", "ok"))
                .taskFlowResponse(TaskFlow.builder()
                        .id("tf-1")
                        .href("http://mock/tf-1")
                        .build())
                .taskStatusCode("COMPLETED").build());
        return taskCommand;
    }

    // =================================================================
    //  Kickout — BarrierService delegates the decision to
    //  BatchKickoutEvaluator (single source of truth) and reuses the
    //  same in-memory BatchResults snapshot for the final-state PATCH.
    // =================================================================

    @Test
    void applyTaskEvent_completed_kickoutFromEvaluator_dispatchesFailedFinalPatch() {
        // Evaluator says "kickout" — barrier flips to FAILED, completion
        // patches FAILED with the SAME responses list the evaluator saw,
        // FLOW_LIFECYCLE FAILED is published. Single load, two consumers.
        var barrier = barrierWithTotal(1, 0);
        when(repo.findByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(Optional.of(barrier));
        BatchResults snapshot = emptyBatchResults();
        when(batchResultsLoader.load(eq("corr-1"), eq((short) 0))).thenReturn(snapshot);
        when(kickoutEvaluator.evaluate(snapshot))
                .thenReturn(Optional.of("actionResponse.taskFlowResponse.taskStatus=Failed"));

        service.applyTaskEvent(buildTaskCommand(TaskStatus.COMPLETED, 0));

        assertThat(barrier.getStatus()).isEqualTo(BarrierStatus.FAILED);
        // taskCompleted DID advance — the evaluator (not the in-memory
        // counter) is the kickout authority.
        assertThat(barrier.getTaskCompleted()).isEqualTo(1);
        verify(completion).patchFailed(eq("corr-1"), eq(snapshot.responses()));
        verify(taskEventsPublisher).publishFailed(anyString(), anyString());
        verify(batchResultsLoader, times(1)).load(anyString(), anyShort());
    }

    @Test
    void applyTaskEvent_completed_kickout_deferredUntilBatchCloses() {
        // Deferred-decision contract: a 2-task batch sees one action come
        // back COMPLETED. handleCompleted bumps taskCompleted, the barrier
        // stays OPEN, pending stays at 1, and NEITHER the loader NOR the
        // evaluator is invoked until the sibling reports in. No final-state
        // PATCH is dispatched.
        var barrier = barrierWithTotal(2, 0);
        when(repo.findByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(Optional.of(barrier));

        service.applyTaskEvent(buildTaskCommand(TaskStatus.COMPLETED, 0));

        assertThat(barrier.getStatus()).isEqualTo(BarrierStatus.OPEN);
        assertThat(barrier.getTaskCompleted()).isEqualTo(1);
        assertThat(barrier.getTaskFailed()).isZero();
        verify(completion, never()).patchFailed(anyString(), any());
        verify(completion, never()).patchCompleted(anyString(), any());
        verifyNoInteractions(batchResultsLoader, kickoutEvaluator);
    }

    @Test
    void applyTaskEvent_completed_cleanBatch_promotesFinalPatchWithSameResponses() {
        // Clean batch closes — completion.patchCompleted receives the SAME
        // responses list the evaluator decided "no kickout" against. Asserts
        // the single-load contract: the in-memory snapshot is reused for
        // the final-state PATCH instead of triggering a second DB scan.
        var barrier = barrierWithTotal(1, 0);
        when(repo.findByCorrelationIdAndBatchIndex(anyString(), anyShort())).thenReturn(Optional.of(barrier));
        when(dagRegistry.find("TestDAG")).thenReturn(Optional.of(dagWithBatches(1)));
        BatchResults snapshot = emptyBatchResults();
        when(batchResultsLoader.load(eq("corr-1"), eq((short) 0))).thenReturn(snapshot);
        when(kickoutEvaluator.evaluate(snapshot)).thenReturn(Optional.empty());

        service.applyTaskEvent(buildTaskCommand(TaskStatus.COMPLETED, 0));

        verify(completion).patchCompleted(eq("corr-1"), eq(snapshot.responses()));
        verify(batchResultsLoader, times(1)).load(anyString(), anyShort());
    }
}
