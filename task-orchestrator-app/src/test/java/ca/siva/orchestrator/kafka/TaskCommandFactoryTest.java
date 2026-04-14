package ca.siva.orchestrator.kafka;

import ca.siva.orchestrator.actionregistry.ActionDefinition;
import ca.siva.orchestrator.actionregistry.ActionRegistry;
import ca.siva.orchestrator.dag.DagDefinition;
import ca.siva.orchestrator.domain.MessageNames;
import ca.siva.orchestrator.domain.ExecutionMode;
import ca.siva.orchestrator.domain.MessageType;
import ca.siva.orchestrator.domain.Sources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskCommandFactoryTest {

    @Mock ActionRegistry actionRegistry;
    @InjectMocks TaskCommandFactory factory;

    @Test
    void buildBase_setsAllMetadataFields() {
        var testCommand = factory.buildBase("corr-1", MessageNames.TASK_EVENT, MessageType.EVENT, Sources.TASK_RUNNER);

        assertThat(testCommand.getEventId()).isNotBlank();
        assertThat(testCommand.getCorrelationId()).isEqualTo("corr-1");
        assertThat(testCommand.getMessageName()).isEqualTo(MessageNames.TASK_EVENT);
        assertThat(testCommand.getMessageType()).isEqualTo(MessageType.EVENT);
        assertThat(testCommand.getSource()).isEqualTo(Sources.TASK_RUNNER);
        ;
        assertThat(testCommand.getEventTime()).isNotNull();
    }

    @Test
    void buildTaskExecute_identityFromRegistry_executionFromDag() {
        // Registry provides identity triplet only
        var actionDef = new ActionDefinition("TEST_CODE", "runTest", "DCX-TST-01");
        when(actionRegistry.resolve("runTest")).thenReturn(Optional.of(actionDef));

        // DAG provides actionName + execution params
        var ad = new DagDefinition.ActionDef();
        ad.setActionName("runTest");
        ad.setExecutionMode(ExecutionMode.SYNC);

        var batch = new DagDefinition.BatchDef();
        batch.setIndex(0);
        batch.setActions(List.of(ad));

        var result = factory.buildTaskExecute("corr-1", "TestDAG", batch, ad, Map.of("id", "pf-1"), null);

        assertThat(result).isPresent();
        var testCommand = result.get();

        // actionName from DAG, actionCode + dcxActionCode from registry
        assertThat(testCommand.getAction().getActionName()).isEqualTo("runTest");
        assertThat(testCommand.getAction().getActionCode()).isEqualTo("TEST_CODE");
        assertThat(testCommand.getAction().getDcxActionCode()).isEqualTo("DCX-TST-01");

        // Execution from DAG
        assertThat(testCommand.getExecution().getMode()).isEqualTo(ExecutionMode.SYNC);
    }

    @Test
    void buildTaskExecute_unknownActionName_returnsEmpty() {
        when(actionRegistry.resolve("unknownAction")).thenReturn(Optional.empty());

        var ad = new DagDefinition.ActionDef();
        ad.setActionName("unknownAction");
        ad.setExecutionMode(ExecutionMode.SYNC);

        var batch = new DagDefinition.BatchDef();
        batch.setIndex(0);
        batch.setActions(List.of(ad));

        assertThat(factory.buildTaskExecute("corr-1", "TestDAG", batch, ad, Map.of(), null)).isEmpty();
    }
}
