package ca.siva.orchestrator.service;

import ca.siva.orchestrator.dto.ActionResponse;
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

    /** Cap JSON blob size to protect the DB from an exceptionally large downstream payload. */
    private static final int MAX_JSON_LENGTH = 65_536; // 64 KB
    private static final String TRUNCATED_MARKER = "\"...TRUNCATED\"";

    private final TaskExecutionRepository repo;
    private final ObjectMapper mapper;

    /**
     * Creates or updates a task execution record from the given taskCommand.
     *
     * <p>The composite key is (processFlowId, taskFlowId) where:
     * <ul>
     *   <li>processFlowId = correlationId = the TMF-701 processFlow UUID</li>
     *   <li>taskFlowId = result.id = the TMF-701 taskFlow UUID from ActionResponse</li>
     * </ul>
     */
    @Transactional
    public void upsert(TaskCommand taskCommand) {
        // Guard: result (with id), action (with actionName), and batch (with index) must be present
        Optional<String> taskFlowId = Optional.ofNullable(taskCommand.getResult())
                .map(ActionResponse::getId);
        if (taskFlowId.isEmpty() || taskCommand.getAction() == null
                || taskCommand.getBatch() == null || taskCommand.getBatch().getIndex() == null) {
            return;
        }

        TaskExecutionId id = new TaskExecutionId(
                taskCommand.getCorrelationId(),
                taskFlowId.get());

        Optional<TaskExecution> existing = repo.findById(id);
        TaskExecution te;
        boolean isNew = existing.isEmpty();
        if (isNew) {
            te = new TaskExecution();
            te.setId(id);
        } else {
            te = existing.get();
        }

        te.setActionName(taskCommand.getAction().getActionName());
        te.setActionCode(taskCommand.getAction().getActionCode());
        te.setBatchIndex(taskCommand.getBatch().getIndex().shortValue());
        te.setStatus(taskCommand.getStatus());

        // Populate execution timing if present
        Optional.ofNullable(taskCommand.getExecution()).ifPresent(ex -> {
            te.setStartedAt(ex.getStartedAt());
            te.setFinishedAt(ex.getFinishedAt());
            Optional.ofNullable(ex.getDurationMs())
                    .ifPresent(te::setDurationMs);
        });

        te.setResultJson(serialize(taskCommand.getResult()));
        te.setErrorJson(taskCommand.getError() != null ? serialize(taskCommand.getError()) : null);

        // Only save for new entities. Existing entities are managed inside this @Transactional
        // method — Hibernate's dirty-checking flushes the UPDATE at commit automatically,
        // so calling save() here would be a redundant round trip.
        if (isNew) {
            repo.save(te);
        }
    }

    /**
     * Serializes the given object to JSON, capping the output at {@link #MAX_JSON_LENGTH}
     * bytes. Oversized payloads are truncated and tagged so investigation is possible.
     */
    private String serialize(Object o) {
        if (o == null) return null;
        try {
            String json = mapper.writeValueAsString(o);
            if (json.length() > MAX_JSON_LENGTH) {
                log.warn("Result JSON size {} exceeds cap {} — truncating",
                        json.length(), MAX_JSON_LENGTH);
                return json.substring(0, MAX_JSON_LENGTH - TRUNCATED_MARKER.length())
                        + TRUNCATED_MARKER;
            }
            return json;
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize: {}", e.getMessage());
            return null;
        }
    }
}
