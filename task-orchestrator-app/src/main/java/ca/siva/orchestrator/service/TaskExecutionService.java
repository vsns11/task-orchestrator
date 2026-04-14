package ca.siva.orchestrator.service;

import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.entity.TaskExecution;
import ca.siva.orchestrator.entity.TaskExecutionId;
import ca.siva.orchestrator.repository.TaskExecutionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Manages the {@code task_execution} audit trail.
 * Upserts execution records from inbound {@code task.event} messages.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutionService {

    private final TaskExecutionRepository repo;
    private final ObjectMapper mapper;

    /**
     * Creates or updates a task execution record from the given message.
     *
     * <p>The composite key is (processFlowId, taskFlowId, attempt) where:
     * <ul>
     *   <li>processFlowId = correlationId = the TMF-701 processFlow UUID</li>
     *   <li>taskFlowId = task.id = the TMF-701 taskFlow UUID minted by the runner</li>
     *   <li>attempt = execution attempt number</li>
     * </ul>
     */
    @Transactional
    public void upsert(TaskCommand message) {
        // Guard: both task and action must be present
        Optional<String> taskFlowId = Optional.ofNullable(message.getTask())
                .map(TaskCommand.Task::getId);
        if (taskFlowId.isEmpty() || message.getAction() == null || message.getBatch() == null) {
            return;
        }

        short attempt = Optional.ofNullable(message.getExecution())
                .map(TaskCommand.Execution::getAttempt)
                .map(Integer::shortValue)
                .orElse((short) 1);

        // processFlowId = correlationId, taskFlowId = task.id (minted by runner)
        TaskExecutionId id = new TaskExecutionId(
                message.getCorrelationId(),
                taskFlowId.get(),
                attempt);

        TaskExecution te = repo.findById(id).orElseGet(() -> {
            TaskExecution n = new TaskExecution();
            n.setId(id);
            return n;
        });

        te.setActionName(message.getAction().getActionName());
        te.setActionCode(message.getAction().getActionCode());
        te.setBatchIndex(message.getBatch().getIndex().shortValue());
        te.setStatus(message.getStatus() != null ? message.getStatus().name() : null);

        // Populate downstream fields if present
        Optional.ofNullable(message.getDownstream()).ifPresent(ds -> {
            te.setDownstreamId(ds.getId());
            te.setDownstreamHref(ds.getHref());
        });

        // Populate execution timing if present
        Optional.ofNullable(message.getExecution()).ifPresent(ex -> {
            te.setStartedAt(ex.getStartedAt());
            te.setFinishedAt(ex.getFinishedAt());
            Optional.ofNullable(ex.getDurationMs())
                    .ifPresent(te::setDurationMs);
        });

        te.setResultJson(serialize(message.getResult()));
        te.setErrorJson(message.getError() != null ? serialize(message.getError()) : null);

        repo.save(te);
    }

    private String serialize(Object o) {
        if (o == null) return null;
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize: {}", e.getMessage());
            return null;
        }
    }
}
