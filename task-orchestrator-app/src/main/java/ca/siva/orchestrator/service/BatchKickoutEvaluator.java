package ca.siva.orchestrator.service;

import ca.siva.orchestrator.domain.TaskStatus;
import ca.siva.orchestrator.dto.ActionResponse;
import ca.siva.orchestrator.dto.tmf.ProcessFlow.Characteristic;
import ca.siva.orchestrator.dto.tmf.TaskFlow;
import ca.siva.orchestrator.entity.TaskExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Single-responsibility owner of the batch kickout decision.
 *
 * <p>Pure function over a pre-loaded {@link BatchResults} — no DB access of
 * its own. {@link BarrierService} is the integration point that loads the
 * batch via {@link BatchResultsLoader} and hands the same snapshot to this
 * evaluator and to {@link ProcessFlowCompletionService}, so each batch close
 * pays for the row scan + JSON parse exactly once.</p>
 *
 * <h3>Two layers of inspection per row</h3>
 * <ol>
 *   <li><b>Row status</b> — anything that is not {@link TaskStatus#COMPLETED}
 *       (FAILED, CANCELLED, or a stuck IN_PROGRESS / WAITING that somehow
 *       drained the barrier counter) is a kickout. We fail fast on this
 *       without inspecting the body.</li>
 *   <li><b>Body content</b> — for COMPLETED rows we apply
 *       {@link #kickoutReasonFor(ActionResponse)}: the sole business-level
 *       check is the {@code taskStatus} characteristic on the embedded
 *       {@code taskFlowResponse}; anything other than {@code Passed} is a
 *       kickout.</li>
 * </ol>
 *
 * <p>A {@code null} parsed response (empty / unparseable {@code result_json})
 * is treated as "no body signal" — the row's status field remains
 * authoritative. A genuinely rotten payload for one action shouldn't block
 * the kickout decision for the rest of the batch.</p>
 */
@Slf4j
@Component
public class BatchKickoutEvaluator {

    /** Characteristic name carrying the pass/fail status on the TMF-701 TaskFlow response. */
    private static final String TASK_STATUS_CHARACTERISTIC = "taskStatus";
    /** Expected value of the {@code taskStatus} characteristic for a passing action. */
    private static final String TASK_STATUS_PASSED         = "Passed";

    /**
     * Walks every row in the batch and returns the first kickout reason
     * found, or empty if the batch is clean.
     *
     * <p>{@link BatchResults#rows()} and {@link BatchResults#responses()} are
     * index-aligned — the parsed response at position {@code i} belongs to
     * the row at position {@code i}, with {@code null} when that row's
     * payload was absent or unparseable.</p>
     */
    public Optional<String> evaluate(BatchResults batch) {
        List<TaskExecution> rows = batch.rows();
        List<ActionResponse> responses = batch.responses();
        for (int i = 0; i < rows.size(); i++) {
            TaskExecution te = rows.get(i);
            TaskStatus rowStatus = te.getStatus();
            if (rowStatus != null && rowStatus != TaskStatus.COMPLETED) {
                return Optional.of("action=" + te.getActionName()
                        + " row.status=" + rowStatus);
            }
            ActionResponse response = responses.get(i);
            if (response != null) {
                Optional<String> bodyReason = kickoutReasonFor(response);
                if (bodyReason.isPresent()) {
                    return Optional.of("action=" + te.getActionName() + " " + bodyReason.get());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Returns a non-empty reason if the {@link ActionResponse} payload
     * indicates a business-level failure under what was reported as a
     * COMPLETED task event.
     *
     * <p>Sole kickout trigger: {@code taskFlowResponse.characteristic[name=taskStatus]}
     * is present and its value is anything other than {@code Passed}. A
     * missing payload — or a missing {@code taskStatus} characteristic — is
     * NOT a kickout; the persisted row's status field remains authoritative.</p>
     */
    private static Optional<String> kickoutReasonFor(ActionResponse response) {
        if (response == null) {
            return Optional.empty();
        }
        TaskFlow taskFlowResponse = response.getTaskFlowResponse();
        if (taskFlowResponse == null || taskFlowResponse.getCharacteristic() == null) {
            return Optional.empty();
        }
        for (Characteristic c : taskFlowResponse.getCharacteristic()) {
            if (c == null || !TASK_STATUS_CHARACTERISTIC.equalsIgnoreCase(c.getName())) {
                continue;
            }
            String status = c.getValue();
            if (status != null && !TASK_STATUS_PASSED.equalsIgnoreCase(status)) {
                return Optional.of("actionResponse.taskFlowResponse.taskStatus=" + status);
            }
            return Optional.empty();
        }
        return Optional.empty();
    }
}
