package ca.siva.orchestrator.service;

import ca.siva.orchestrator.domain.MessageName;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class OrchestratorServiceTest {

    @Mock BarrierService barrier;
    @Mock TaskExecutionService taskExecution;
    @InjectMocks OrchestratorService service;

    @Test
    void handle_nullEnvelope_doesNothing() {
        service.handle(null);
        verifyNoInteractions(barrier, taskExecution);
    }

    @Test
    void handle_nullMessageName_doesNothing() {
        service.handle(new TaskCommand());
        verifyNoInteractions(barrier, taskExecution);
    }

    @Test
    void handle_ownTaskExecute_filtered() {
        var testCommand = buildTestCommand(MessageName.TASK_EXECUTE.getValue(), Sources.TASK_ORCHESTRATOR);
        service.handle(testCommand);
        verifyNoInteractions(barrier, taskExecution);
    }

    @Test
    void handle_processFlowInitiated_delegatesToBarrier() {
        var testCommand = buildTestCommand(MessageName.PROCESS_FLOW_INITIATED.getValue(), Sources.PAMCONSUMER);
        testCommand.setDagKey("TestDAG");
        testCommand.setInputs(TaskCommand.Inputs.builder()
                .processFlow(ProcessFlow.builder().id("pf-123").build())
                .build());

        service.handle(testCommand);

        verify(barrier).initiateFlow(eq("pf-123"), eq("TestDAG"),
                any(ProcessFlow.class));
    }

    @Test
    void handle_processFlowInitiated_missingDagKey_logsWarning() {
        var testCommand = buildTestCommand(MessageName.PROCESS_FLOW_INITIATED.getValue(), Sources.PAMCONSUMER);
        service.handle(testCommand);
        verifyNoInteractions(barrier);
    }

    @Test
    void handle_taskEvent_delegatesToBothServices() {
        var testCommand = buildTestCommand(MessageName.TASK_EVENT.getValue(), Sources.TASK_RUNNER);
        service.handle(testCommand);
        verify(taskExecution).upsert(testCommand);
        verify(barrier).applyTaskEvent(testCommand);
    }

    @Test
    void handle_taskSignal_logsOnly() {
        var testCommand = buildTestCommand(MessageName.TASK_SIGNAL.getValue(), Sources.PAMCONSUMER);
        service.handle(testCommand);
        verifyNoInteractions(barrier, taskExecution);
    }

    @Test
    void handle_unknownMessageName_ignored() {
        var testCommand = buildTestCommand("unknown.message", "unknown-source");
        service.handle(testCommand);
        verifyNoInteractions(barrier, taskExecution);
    }

    private static TaskCommand buildTestCommand(String messageName, String source) {
        var testCommand = new TaskCommand();
        testCommand.setEventId("test-event-" + System.nanoTime());
        testCommand.setCorrelationId("corr-1");
        testCommand.setMessageName(messageName);
        testCommand.setSource(source);
        return testCommand;
    }
}
