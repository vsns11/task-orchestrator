package ca.siva.orchestrator.service;

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
import java.util.Map;
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

        var testCommand = fullTaskEvent();
        service.upsert(testCommand);

        var captor = ArgumentCaptor.forClass(TaskExecution.class);
        verify(repo).save(captor.capture());

        TaskExecution saved = captor.getValue();
        assertThat(saved.getActionName()).isEqualTo("testAction");
        assertThat(saved.getActionCode()).isEqualTo("TEST_ACTION");
        assertThat(saved.getStatus()).isEqualTo(ca.siva.orchestrator.domain.TaskStatus.COMPLETED.name());
    }

    @Test
    void upsert_existingExecution_updatesStatus() {
        var existing = new TaskExecution();
        existing.setId(new TaskExecutionId("corr-1", "tf-1", (short) 1));
        existing.setStatus("IN_PROGRESS");
        when(repo.findById(any())).thenReturn(Optional.of(existing));

        var testCommand = fullTaskEvent();
        service.upsert(testCommand);

        verify(repo).save(existing);
        assertThat(existing.getStatus()).isEqualTo(ca.siva.orchestrator.domain.TaskStatus.COMPLETED.name());
    }

    @Test
    void upsert_nullTask_returnsEarly() {
        var testCommand = new TaskCommand();
        testCommand.setCorrelationId("corr-1");
        // task is null

        service.upsert(testCommand);

        verifyNoInteractions(repo);
    }

    @Test
    void upsert_withDownstream_setsFields() {
        when(repo.findById(any())).thenReturn(Optional.empty());

        var testCommand = fullTaskEvent();
        testCommand.setDownstream(TaskCommand.Downstream.builder()
                .id("ds-1").href("http://ds/1").build());

        service.upsert(testCommand);

        var captor = ArgumentCaptor.forClass(TaskExecution.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getDownstreamId()).isEqualTo("ds-1");
        assertThat(captor.getValue().getDownstreamHref()).isEqualTo("http://ds/1");
    }

    @Test
    void upsert_withExecution_setsTimingFields() {
        when(repo.findById(any())).thenReturn(Optional.empty());

        Instant now = Instant.now();
        var testCommand = fullTaskEvent();
        testCommand.setExecution(TaskCommand.Execution.builder()
                .mode(ca.siva.orchestrator.domain.ExecutionMode.SYNC).attempt(1).startedAt(now).finishedAt(now).durationMs(500L)
                .build());

        service.upsert(testCommand);

        var captor = ArgumentCaptor.forClass(TaskExecution.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStartedAt()).isEqualTo(now);
        assertThat(captor.getValue().getDurationMs()).isEqualTo(500L);
    }

    private static TaskCommand fullTaskEvent() {
        var testCommand = new TaskCommand();
        testCommand.setCorrelationId("corr-1");
        testCommand.setStatus(ca.siva.orchestrator.domain.TaskStatus.COMPLETED);
        testCommand.setTask(TaskCommand.Task.builder().id("tf-1").build());
        testCommand.setAction(TaskCommand.Action.builder()
                .actionName("testAction").actionCode("TEST_ACTION").build());
        testCommand.setBatch(TaskCommand.Batch.builder().index(0).build());
        testCommand.setResult(Map.of("key", "value"));
        return testCommand;
    }
}
