package ca.siva.orchestrator.service;

import ca.siva.orchestrator.domain.MessageNames;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.dto.TaskCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.*;

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
        var testCommand = buildTestCommand(MessageNames.TASK_EXECUTE, Sources.TASK_ORCHESTRATOR);
        service.handle(testCommand);
        verifyNoInteractions(barrier, taskExecution);
    }

    @Test
    void handle_processFlowInitiated_delegatesToBarrier() {
        var testCommand = buildTestCommand(MessageNames.PROCESS_FLOW_INITIATED, Sources.PAMCONSUMER);
        testCommand.setDagKey("TestDAG");
        testCommand.setInputs(TaskCommand.Inputs.builder()
                .processFlow(Map.of("id", "pf-123"))
                .build());

        service.handle(testCommand);

        verify(barrier).initiateFlow("pf-123", "TestDAG",
                Map.of("id", "pf-123"));
    }

    @Test
    void handle_processFlowInitiated_missingDagKey_logsWarning() {
        var testCommand = buildTestCommand(MessageNames.PROCESS_FLOW_INITIATED, Sources.PAMCONSUMER);
        service.handle(testCommand);
        verifyNoInteractions(barrier);
    }

    @Test
    void handle_taskEvent_delegatesToBothServices() {
        var testCommand = buildTestCommand(MessageNames.TASK_EVENT, Sources.TASK_RUNNER);
        service.handle(testCommand);
        verify(taskExecution).upsert(testCommand);
        verify(barrier).applyTaskEvent(testCommand);
    }

    @Test
    void handle_taskSignal_logsOnly() {
        var testCommand = buildTestCommand(MessageNames.TASK_SIGNAL, Sources.PAMCONSUMER);
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
