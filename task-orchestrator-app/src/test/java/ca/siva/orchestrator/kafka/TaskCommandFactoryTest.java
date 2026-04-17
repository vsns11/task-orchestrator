package ca.siva.orchestrator.kafka;

import ca.siva.orchestrator.actionregistry.ActionRegistry;
import ca.siva.orchestrator.dag.DagDefinition;
import ca.siva.orchestrator.domain.ExecutionMode;
import ca.siva.orchestrator.domain.MessageName;
import ca.siva.orchestrator.domain.MessageType;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskCommandFactoryTest {

    @Mock ActionRegistry actionRegistry;
    @InjectMocks TaskCommandFactory factory;

    @Test
    void buildBase_setsAllMetadataFields() {
        var testCommand = factory.buildBase("corr-1", MessageName.TASK_EVENT.getValue(), MessageType.EVENT, Sources.TASK_RUNNER);

        assertThat(testCommand.getEventId()).isNotBlank();
        assertThat(testCommand.getCorrelationId()).isEqualTo("corr-1");
        assertThat(testCommand.getMessageName()).isEqualTo(MessageName.TASK_EVENT.getValue());
        assertThat(testCommand.getMessageType()).isEqualTo(MessageType.EVENT);
        assertThat(testCommand.getSource()).isEqualTo(Sources.TASK_RUNNER);
        assertThat(testCommand.getEventTime()).isNotNull();
    }

    @Test
    void buildTaskExecute_populatesActionCodeAndDcxFromRegistryLookups() {
        // Factory must call the two ActionBuilder-equivalent methods directly —
        // no reliance on the merged-ActionDefinition convenience path.
        when(actionRegistry.getActionCodeByName("runTest")).thenReturn("TEST_CODE");
        when(actionRegistry.getDcxActionCodeByName("runTest", "DEFAULT", "DEFAULT"))
                .thenReturn("DCX-TST-01");

        var ad = new DagDefinition.ActionDef();
        ad.setActionName("runTest");
        ad.setExecutionMode(ExecutionMode.SYNC);

        var batch = new DagDefinition.BatchDef();
        batch.setIndex(0);
        batch.setActions(List.of(ad));

        var result = factory.buildTaskExecute("corr-1", "TestDAG", batch, ad,
                ProcessFlow.builder().id("pf-1").build(), null);

        assertThat(result).isPresent();
        var testCommand = result.get();

        assertThat(testCommand.getAction().getActionName()).isEqualTo("runTest");
        assertThat(testCommand.getAction().getActionCode()).isEqualTo("TEST_CODE");
        assertThat(testCommand.getAction().getDcxActionCode()).isEqualTo("DCX-TST-01");
        assertThat(testCommand.getExecution().getMode()).isEqualTo(ExecutionMode.SYNC);
    }

    @Test
    void buildTaskExecute_forwardsDagFlowTypeAndModemTypeToDcxLookup() {
        // When the DAG action carries flowType/modemType, those must flow
        // through to getDcxActionCodeByName so the composite key lookup can
        // pick a non-DEFAULT row.
        when(actionRegistry.getActionCodeByName("runVsd")).thenReturn("VOICE_SERVICE_DIAGNOSTIC");
        when(actionRegistry.getDcxActionCodeByName("runVsd", "COPPER", "HITRON"))
                .thenReturn("DCX-VSD-COPPER-HITRON");

        var ad = new DagDefinition.ActionDef();
        ad.setActionName("runVsd");
        ad.setExecutionMode(ExecutionMode.SYNC);
        ad.setFlowType("COPPER");
        ad.setModemType("HITRON");

        var batch = new DagDefinition.BatchDef();
        batch.setIndex(0);
        batch.setActions(List.of(ad));

        var result = factory.buildTaskExecute("corr-1", "TestDAG", batch, ad, null, null);

        assertThat(result).isPresent();
        assertThat(result.get().getAction().getDcxActionCode()).isEqualTo("DCX-VSD-COPPER-HITRON");
    }

    @Test
    void buildTaskExecute_unknownActionName_returnsEmpty() {
        when(actionRegistry.getActionCodeByName("unknownAction")).thenReturn(null);

        var ad = new DagDefinition.ActionDef();
        ad.setActionName("unknownAction");
        ad.setExecutionMode(ExecutionMode.SYNC);

        var batch = new DagDefinition.BatchDef();
        batch.setIndex(0);
        batch.setActions(List.of(ad));

        assertThat(factory.buildTaskExecute("corr-1", "TestDAG", batch, ad, null, null)).isEmpty();
    }
}
