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

    private final TaskExecutionRepository repo;
    private final ObjectMapper mapper;

    /**
     * Creates or updates a task execution record from the given taskCommand.
     *
     * <p>The flow-task coordinate is {@code (correlationId, taskFlowId)} where:
     * <ul>
     *   <li>correlationId = the Kafka correlationId = the TMF-701 processFlow UUID</li>
     *   <li>taskFlowId = result.id = the TMF-701 taskFlow UUID from ActionResponse</li>
     * </ul>
     * The table is date-partitioned, so lookups use the coordinate query
     * (two-phase: today-partition first, cross-partition fallback) instead of
     * {@code findById} — the partition column ({@code createdAt}) is not
     * known when upserting from a Kafka event.</p>
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

        String correlationId = taskCommand.getCorrelationId();
        String taskFlowIdValue = taskFlowId.get();

        Optional<TaskExecution> existing = repo.findByCorrelationIdAndTaskFlowId(correlationId, taskFlowIdValue);
        TaskExecution taskExecution;
        boolean isNew = existing.isEmpty();
        if (isNew) {
            taskExecution = new TaskExecution();
            taskExecution.setId(new TaskExecutionId(correlationId, taskFlowIdValue));
        } else {
            taskExecution = existing.get();
        }

        taskExecution.setActionName(taskCommand.getAction().getActionName());
        taskExecution.setActionCode(taskCommand.getAction().getActionCode());
        taskExecution.setBatchIndex(taskCommand.getBatch().getIndex().shortValue());
        taskExecution.setStatus(taskCommand.getStatus());

        // Populate execution timing if present
        Optional.ofNullable(taskCommand.getExecution()).ifPresent(execution -> {
            taskExecution.setStartedAt(execution.getStartedAt());
            taskExecution.setFinishedAt(execution.getFinishedAt());
            Optional.ofNullable(execution.getDurationMs())
                    .ifPresent(taskExecution::setDurationMs);
        });

        taskExecution.setResultJson(serialize(taskCommand.getResult()));
        taskExecution.setErrorJson(taskCommand.getError() != null ? serialize(taskCommand.getError()) : null);

        // Only save for new entities. Existing entities are managed inside this @Transactional
        // method — Hibernate's dirty-checking flushes the UPDATE at commit automatically,
        // so calling save() here would be a redundant round trip.
        if (isNew) {
            repo.save(taskExecution);
        }
    }

    /**
     * Serializes the given object to JSON. The target column ({@code result_json})
     * is a Postgres {@code TEXT} column with no length limit, so the full payload
     * is persisted as-is. If you need a hard ceiling for a specific environment,
     * enforce it at the DB level (e.g. a column type cap) rather than silently
     * truncating here.
     */
    private String serialize(Object payload) {
        if (payload == null) return null;
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Don't silently persist NULL — a downstream query reading
            // result_json can't distinguish "no result" from "serialization
            // failed". Store a tiny JSON marker so forensics have the failure
            // reason and the payload class visible in the row.
            log.warn("Could not serialize {} — persisting error marker: exception={}",
                    payload.getClass().getSimpleName(), e.toString(), e);
            return "{\"_serializationError\":\"" + escapeJson(e.getMessage())
                    + "\",\"_class\":\"" + payload.getClass().getName() + "\"}";
        }
    }

    /** Minimal JSON-string escape for the serialization-failure marker. */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
