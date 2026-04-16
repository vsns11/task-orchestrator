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
import ca.siva.orchestrator.entity.BatchBarrier;
import ca.siva.orchestrator.entity.BatchBarrierId;
import ca.siva.orchestrator.kafka.TaskCommandFactory;
import ca.siva.orchestrator.kafka.TaskCommandPublisher;
import ca.siva.orchestrator.repository.BatchBarrierRepository;
import ca.siva.orchestrator.repository.TaskExecutionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock ca.siva.orchestrator.kafka.TaskEventsPublisher taskEventsPublisher;
    @InjectMocks BarrierService service;

    @Test
    void initiateFlow_seedsBarrierAndPublishesCommands() {
        var dag = dagWithBatches(2);
        when(dagRegistry.find("TestDAG")).thenReturn(Optional.of(dag));
        when(repo.existsById(any())).thenReturn(false);
        when(taskCommandFactory.buildTaskExecute(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new TaskCommand()));

        service.initiateFlow("corr-1", "TestDAG", ProcessFlow.builder().id("flow-1").build());

        verify(repo).save(any(BatchBarrier.class));
        verify(publisher, times(2)).publish(any());
    }

    @Test
    void initiateFlow_alreadySeeded_skips() {
        when(dagRegistry.find("TestDAG")).thenReturn(Optional.of(dagWithBatches(2)));
        when(repo.existsById(any())).thenReturn(true);

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
        when(repo.findById(any(BatchBarrierId.class))).thenReturn(Optional.of(barrier));
        // Only 1 batch in DAG — after closing batch 0, flow completes
        when(dagRegistry.find("TestDAG")).thenReturn(Optional.of(dagWithBatches(1)));

        var taskCommand =buildTaskCommand(TaskStatus.COMPLETED, 0);
        service.applyTaskEvent(taskCommand);

        assertThat(barrier.getStatus()).isEqualTo(BarrierStatus.CLOSED);
        verify(tmf701).patchProcessFlowState(anyString(), eq("completed"));
    }

    @Test
    void applyTaskEvent_completed_doesNotCloseWhenPendingRemains() {
        var barrier = barrierWithTotal(2, 0);
        when(repo.findById(any(BatchBarrierId.class))).thenReturn(Optional.of(barrier));

        service.applyTaskEvent(buildTaskCommand(TaskStatus.COMPLETED, 0));

        assertThat(barrier.getStatus()).isEqualTo(BarrierStatus.OPEN);
        assertThat(barrier.getTaskCompleted()).isEqualTo(1);
    }

    @Test
    void applyTaskEvent_completed_ignoredWhenBarrierAlreadyClosed() {
        var barrier = barrierWithTotal(1, 1);
        barrier.close(); // already CLOSED
        when(repo.findById(any(BatchBarrierId.class))).thenReturn(Optional.of(barrier));

        service.applyTaskEvent(buildTaskCommand(TaskStatus.COMPLETED, 0));

        // Should NOT increment completed counter again
        assertThat(barrier.getTaskCompleted()).isEqualTo(1);
        verify(repo, never()).save(any());
    }

    @Test
    void applyTaskEvent_completed_ignoredWhenBarrierNotFound() {
        when(repo.findById(any(BatchBarrierId.class))).thenReturn(Optional.empty());
        service.applyTaskEvent(buildTaskCommand(TaskStatus.COMPLETED, 0));
        verify(repo, never()).save(any());
    }

    @Test
    void applyTaskEvent_failed_nonRetryable_failsBarrier() {
        var barrier = barrierWithTotal(2, 0);
        when(repo.findById(any(BatchBarrierId.class))).thenReturn(Optional.of(barrier));

        var taskCommand =buildTaskCommand(TaskStatus.FAILED, 0);
        taskCommand.setError(TaskCommand.ErrorInfo.builder().retryable(false).build());
        service.applyTaskEvent(taskCommand);

        assertThat(barrier.getStatus()).isEqualTo(BarrierStatus.FAILED);
        verify(tmf701).patchProcessFlowState(anyString(), eq("failed"));
    }

    @Test
    void applyTaskEvent_failed_retryable_doesNotFailBarrier() {
        var barrier = barrierWithTotal(2, 0);
        when(repo.findById(any(BatchBarrierId.class))).thenReturn(Optional.of(barrier));

        var taskCommand =buildTaskCommand(TaskStatus.FAILED, 0);
        taskCommand.setError(TaskCommand.ErrorInfo.builder().retryable(true).build());
        service.applyTaskEvent(taskCommand);

        assertThat(barrier.getStatus()).isEqualTo(BarrierStatus.OPEN);
    }

    @Test
    void applyTaskEvent_waiting_doesNotPatchProcessFlow() {
        service.applyTaskEvent(buildTaskCommand(TaskStatus.WAITING, 0));
        verifyNoInteractions(tmf701);
    }

    @Test
    void applyTaskEvent_missingStatus_ignored() {
        var taskCommand =buildTaskCommand((TaskStatus) null, 0);
        taskCommand.setStatus(null);
        service.applyTaskEvent(taskCommand);
        verifyNoInteractions(repo, tmf701);
    }

    @Test
    void applyTaskEvent_missingBatchIndex_ignored() {
        var taskCommand =new TaskCommand();
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
        var batches = new java.util.ArrayList<DagDefinition.BatchDef>();
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

    private static TaskCommand buildTaskCommand(TaskStatus status, int batchIndex) {
        var taskCommand =new TaskCommand();
        taskCommand.setEventId("e-" + System.nanoTime());
        taskCommand.setCorrelationId("corr-1");
        taskCommand.setStatus(status);
        taskCommand.setBatch(TaskCommand.Batch.builder().index(batchIndex).build());
        taskCommand.setAction(TaskCommand.Action.builder()
                .actionName("testAction").actionCode("TEST_ACTION").build());
        taskCommand.setResult(ActionResponse.builder()
                .name("testAction").code("TEST_ACTION").id("tf-1").type("TaskFlow")
                .taskFlowResult(Map.of("id", "tf-1", "href", "http://mock/tf-1"))
                .taskStatusCode("COMPLETED").build());
        return taskCommand;
    }
}
