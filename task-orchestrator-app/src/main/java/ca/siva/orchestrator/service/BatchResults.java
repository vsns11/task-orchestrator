package ca.siva.orchestrator.service;

import ca.siva.orchestrator.dto.ActionResponse;
import ca.siva.orchestrator.entity.TaskExecution;

import java.util.List;

/**
 * Immutable, in-memory snapshot of a single batch's persisted task-execution
 * rows together with their already-deserialized {@link ActionResponse}
 * payloads.
 *
 * <p>Exists so a batch close pays for the DB scan + JSON parse <b>once</b> and
 * can hand the result to both the kickout decision
 * ({@link BatchKickoutEvaluator}) and the final-state PATCH derivation
 * ({@link ProcessFlowCompletionService}) without either collaborator owning
 * its own load. Before this record both paths queried the repository
 * independently — {@code findAllByCorrelationIdAndBatchIndex} for the
 * evaluator and the now-deleted {@code findAllByCorrelationId} for the
 * completion service — which doubled the row count touched per close and
 * scanned every daily partition the flow had ever lived in.</p>
 *
 * <h3>List alignment contract</h3>
 * <p>{@link #responses()} is index-aligned with {@link #rows()}: position
 * {@code i} in {@code responses} is the parsed {@code result_json} of position
 * {@code i} in {@code rows}, or {@code null} when that row's payload was
 * absent / unparseable. Callers can therefore correlate a body-level kickout
 * back to the originating row without a second lookup.</p>
 */
public record BatchResults(List<TaskExecution> rows, List<ActionResponse> responses) {

    public BatchResults {
        if (rows == null || responses == null) {
            throw new IllegalArgumentException("rows and responses must be non-null");
        }
        if (rows.size() != responses.size()) {
            throw new IllegalArgumentException(
                    "rows and responses must be the same size (rows=" + rows.size()
                            + ", responses=" + responses.size() + ")");
        }
    }
}
