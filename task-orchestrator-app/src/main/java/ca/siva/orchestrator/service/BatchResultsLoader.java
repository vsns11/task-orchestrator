package ca.siva.orchestrator.service;

import ca.siva.orchestrator.dto.ActionResponse;
import ca.siva.orchestrator.entity.TaskExecution;
import ca.siva.orchestrator.repository.TaskExecutionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-trip loader for a batch's persisted task-execution rows.
 *
 * <p>One DB query (the per-batch index lookup) and one parse pass produce a
 * {@link BatchResults} that {@link BarrierService} hands to both
 * {@link BatchKickoutEvaluator} (for the kickout decision) and
 * {@link ProcessFlowCompletionService} (for the final-state PATCH cascade).
 * Centralising the load eliminates the previous duplicate fetch + parse on
 * every batch close — the kickout sweep used to read the per-batch rows and
 * the completion service used to read all rows for the flow, parsing both
 * sets independently.</p>
 *
 * <h3>Tolerant parsing</h3>
 * <p>Rows with null/blank {@code result_json} or unparseable JSON yield
 * {@code null} at the corresponding index of {@link BatchResults#responses()}.
 * The kickout decision treats {@code null} as "no body signal" (the row's
 * status field is authoritative); the completion cascade silently drops
 * {@code null} entries before deriving the processStatus / reason. A single
 * rotten payload must not break either path.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchResultsLoader {

    private final TaskExecutionRepository repo;
    private final ObjectMapper            mapper;

    /**
     * Loads every persisted {@link TaskExecution} for {@code (correlationId,
     * batchIndex)} and parses each row's {@code result_json} to an
     * {@link ActionResponse}. Both lists in the returned {@link BatchResults}
     * are index-aligned: position {@code i} in {@code responses} corresponds
     * to position {@code i} in {@code rows}, with {@code null} when the row
     * had no payload or its JSON failed to parse.
     */
    public BatchResults load(String correlationId, short batchIndex) {
        List<TaskExecution> rows = repo.findAllByCorrelationIdAndBatchIndex(correlationId, batchIndex);
        List<ActionResponse> responses = new ArrayList<>(rows.size());
        for (TaskExecution te : rows) {
            responses.add(parseOrNull(te));
        }
        return new BatchResults(rows, responses);
    }

    /**
     * Deserializes one row's {@code result_json} or returns {@code null} when
     * the payload is absent / malformed. The warn log preserves the action
     * name so an operator can correlate the bad row to the failing downstream
     * without having to grep raw JSON.
     */
    private ActionResponse parseOrNull(TaskExecution te) {
        String json = te.getResultJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, ActionResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("Skipping unparseable result_json for actionName={} reason={}",
                    te.getActionName(), e.toString());
            return null;
        }
    }
}
