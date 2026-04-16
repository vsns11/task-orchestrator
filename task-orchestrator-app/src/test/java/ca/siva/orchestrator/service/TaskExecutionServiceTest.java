package ca.siva.orchestrator.service;

import ca.siva.orchestrator.dto.ActionResponse;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.entity.TaskExecution;
import ca.siva.orchestrator.entity.TaskExecutionId;
import ca.siva.orchestrator.repository.TaskExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskExecutionServiceTest {

    @Mock TaskExecutionRepository repo;
    @Spy ObjectMapper mapper = new ObjectMapper();
    @InjectMocks TaskExecutionService service;

    @Test
    void upsert_newExecution_savesCorrectly() {
        when(repo.findById(any(TaskExecutionId.class))).thenReturn(Optional.empty());

        var testCommand = buildTaskCommand();
        service.upsert(testCommand);

        var captor = ArgumentCaptor.forClass(TaskExecution.class);
        verify(repo).save(captor.capture());

        TaskExecution saved = captor.getValue();
        assertThat(saved.getActionName()).isEqualTo("testAction");
        assertThat(saved.getActionCode()).isEqualTo("TEST_ACTION");
        assertThat(saved.getStatus()).isEqualTo(ca.siva.orchestrator.domain.TaskStatus.COMPLETED);
    }

    @Test
    void upsert_existingExecution_updatesStatus_withoutExplicitSave() {
        var existing = new TaskExecution();
        existing.setId(new TaskExecutionId("corr-1", "tf-1"));
        existing.setStatus(ca.siva.orchestrator.domain.TaskStatus.IN_PROGRESS);
        when(repo.findById(any())).thenReturn(Optional.of(existing));

        var testCommand = buildTaskCommand();
        service.upsert(testCommand);

        // For existing managed entities, dirty-checking handles the UPDATE at commit.
        // No explicit save() needed.
        verify(repo, never()).save(any());
        assertThat(existing.getStatus()).isEqualTo(ca.siva.orchestrator.domain.TaskStatus.COMPLETED);
    }

    @Test
    void upsert_nullResult_returnsEarly() {
        var testCommand = new TaskCommand();
        testCommand.setCorrelationId("corr-1");
        // result is null — no taskFlowId available

        service.upsert(testCommand);

        verifyNoInteractions(repo);
    }

    @Test
    void upsert_withExecution_setsTimingFields() {
        when(repo.findById(any())).thenReturn(Optional.empty());

        Instant now = Instant.now();
        var testCommand = buildTaskCommand();
        testCommand.setExecution(TaskCommand.Execution.builder()
                .mode(ca.siva.orchestrator.domain.ExecutionMode.SYNC).startedAt(now).finishedAt(now).durationMs(500L)
                .build());

        service.upsert(testCommand);

        var captor = ArgumentCaptor.forClass(TaskExecution.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStartedAt()).isEqualTo(now);
        assertThat(captor.getValue().getDurationMs()).isEqualTo(500L);
    }

    private static TaskCommand buildTaskCommand() {
        var testCommand = new TaskCommand();
        testCommand.setCorrelationId("corr-1");
        testCommand.setStatus(ca.siva.orchestrator.domain.TaskStatus.COMPLETED);
        testCommand.setAction(TaskCommand.Action.builder()
                .actionName("testAction").actionCode("TEST_ACTION").build());
        testCommand.setBatch(TaskCommand.Batch.builder().index(0).build());
        testCommand.setResult(ActionResponse.builder()
                .name("testAction").code("TEST_ACTION").id("tf-1").type("TaskFlow")
                .taskStatusCode("COMPLETED").build());
        return testCommand;
    }
}
