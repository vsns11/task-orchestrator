package ca.siva.orchestrator.service;

import ca.siva.orchestrator.client.Tmf701Client;
import ca.siva.orchestrator.domain.ProcessFlowStateType;
import ca.siva.orchestrator.dto.ActionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds the legacy-shape final-state PATCH body for the TMF-701 processFlow
 * resource and delegates the wire call to {@link Tmf701Client}. "Final state"
 * means the PATCH the orchestrator sends exactly once per flow when the flow
 * has reached its end — either every batch closed cleanly (success) or a
 * kickout / non-retryable failure has stopped further promotion (failure).
 *
 * <p>Body shape (mirrors the upstream Bonita {@code processFlowEntryAction}):</p>
 * <pre>
 * {
 *   "state": "completed" | "failed",
 *   "characteristic": [
 *     ...preserved characteristics from the server...,
 *     { "name": "processStatus",       "value": &lt;derived&gt; },
 *     { "name": "statusChangeReason",  "value": &lt;derived&gt; }   // when non-null
 *   ]
 * }
 * </pre>
 *
 * <p>This service exists so {@link BarrierService} stays focused on
 * orchestration. The cascade rules (priority over taskStatusCode values,
 * "no task performed" guard, success/failure default) are an exact port of
 * the legacy {@code processFlowEntryAction} → {@code getProcessStatus} →
 * {@code getStatusChangeReason} chain so downstream consumers see the same
 * payload they did under the Bonita workflow.</p>
 *
 * <p><b>Source of the {@link ActionResponse} list.</b> Callers pass the
 * already-loaded responses for the closing batch — typically the same
 * {@link BatchResults#responses()} the {@link BatchKickoutEvaluator}
 * inspected on this batch close. This service performs no DB access and no
 * JSON parsing of its own; the load is centralised in
 * {@link BatchResultsLoader} so the cost is paid once per close. Behavioural
 * note: an end-of-flow PATCH cascades over only the closing batch's
 * responses — every prior batch was promoted on a clean kickout sweep, so
 * its taskStatusCodes were PASSED and contribute nothing to the priority
 * cascade. A kickout PATCH likewise sees the failing batch's responses,
 * which is where the failing reason lives.</p>
 *
 * <p><b>STATUS_DETAIL override.</b> The legacy code also honoured a
 * {@code STATUS_DETAIL} workflow variable that, if present, short-circuited
 * both cascades. The orchestrator has no equivalent variable today; if/when
 * that gap is closed, the override should be wired in front of
 * {@link #deriveProcessStatus} and {@link #deriveStatusChangeReason}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessFlowCompletionService {

    /**
     * Values the {@code processStatus} characteristic can take on the
     * final-state PATCH, in legacy cascade priority order (highest first).
     * Constant prefix mirrors the characteristic name {@link #CHAR_PROCESS_STATUS}
     * and disambiguates from {@code BarrierService.TASK_STATUS_PASSED}, which
     * is a different concept (taskStatus characteristic on the embedded
     * taskFlowResponse).
     */
    static final String PROCESS_STATUS_ERROR     = "ERROR";
    static final String PROCESS_STATUS_FAILED    = "FAILED";
    static final String PROCESS_STATUS_ERROR_701 = "ERROR_701";
    /** Human-readable "Passed" form per spec — same priority slot as the legacy PASSED enum. */
    static final String PROCESS_STATUS_PASSED    = "Passed";

    /**
     * Default reasons surfaced on the final-state PATCH when no per-action
     * reason is available. Values are the human-readable strings downstream
     * consumers display verbatim — DO NOT change without updating consumer
     * dashboards / NOC reporting.
     */
    static final String REASON_SUCCESS  = "Process Flow Success";
    static final String REASON_FAILURE  = "Process Flow Failure";
    static final String REASON_NO_TASK  = "No Task/Action Performed";

    /** Characteristic names sent on the final-state PATCH (case-insensitive on read-back). */
    static final String CHAR_PROCESS_STATUS        = "processStatus";
    static final String CHAR_STATUS_CHANGE_REASON  = "statusChangeReason";

    private final Tmf701Client tmf701;

    /**
     * Sends a {@code state=completed} final-state PATCH. processStatus and
     * the statusChangeReason characteristic are derived from the supplied
     * action responses (typically the closing batch's
     * {@link BatchResults#responses()}).
     */
    public void patchCompleted(String correlationId, List<ActionResponse> responses) {
        applyFinalState(correlationId, ProcessFlowStateType.COMPLETED, responses);
    }

    /**
     * Sends a {@code state=failed} final-state PATCH. processStatus and the
     * statusChangeReason characteristic are derived from the supplied action
     * responses — so a flow that fails after some actions passed still
     * surfaces the per-action reason that caused the failure.
     */
    public void patchFailed(String correlationId, List<ActionResponse> responses) {
        applyFinalState(correlationId, ProcessFlowStateType.FAILED, responses);
    }

    /**
     * Shared body of {@link #patchCompleted} / {@link #patchFailed}. Runs the
     * legacy cascade against the supplied responses to derive the
     * processStatus and statusChangeReason characteristic values, then
     * dispatches the wire call. The {@code finalState} parameter is the
     * typed lifecycle target — {@link ProcessFlowStateType#COMPLETED} or
     * {@link ProcessFlowStateType#FAILED} — kept as the enum end-to-end so
     * the wire string is materialised exactly once, inside
     * {@link Tmf701Client}.
     */
    private void applyFinalState(String correlationId,
                                 ProcessFlowStateType finalState,
                                 List<ActionResponse> responses) {
        String processStatus      = deriveProcessStatus(responses);
        String statusChangeReason = deriveStatusChangeReason(responses, processStatus);
        log.info("processFlow {} final-state patch: state={} processStatus={} statusChangeReason={} responses={}",
                correlationId, finalState, processStatus, statusChangeReason,
                responses == null ? 0 : responses.size());
        tmf701.patchProcessFlowFinalState(
                correlationId, finalState, processStatus, statusChangeReason);
    }

    /**
     * Legacy {@code getProcessStatus} priority cascade ported verbatim:
     * <ol>
     *   <li>empty list → {@link #PROCESS_STATUS_FAILED} (== legacy "no actionResponseList")</li>
     *   <li>any taskStatusCode null → {@link #PROCESS_STATUS_ERROR}</li>
     *   <li>any taskStatusCode == ERROR → {@link #PROCESS_STATUS_ERROR}</li>
     *   <li>any taskStatusCode == FAILED → {@link #PROCESS_STATUS_FAILED}</li>
     *   <li>any taskStatusCode == ERROR_701 → {@link #PROCESS_STATUS_ERROR_701}</li>
     *   <li>otherwise → {@link #PROCESS_STATUS_PASSED}</li>
     * </ol>
     * <p>Mapping note: the orchestrator's {@link ActionResponse#getTaskStatusCode()}
     * is the equivalent of the legacy {@code ar.getTaskStatus()} — both carry
     * the workflow-level status string the downstream emits.</p>
     */
    String deriveProcessStatus(List<ActionResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return PROCESS_STATUS_FAILED;
        }
        boolean anyNullStatus = responses.stream()
                .anyMatch(ar -> ar != null && ar.getTaskStatusCode() == null);
        if (anyNullStatus) {
            return PROCESS_STATUS_ERROR;
        }
        if (responses.stream().anyMatch(ar -> matches(ar, PROCESS_STATUS_ERROR))) {
            return PROCESS_STATUS_ERROR;
        }
        if (responses.stream().anyMatch(ar -> matches(ar, PROCESS_STATUS_FAILED))) {
            return PROCESS_STATUS_FAILED;
        }
        if (responses.stream().anyMatch(ar -> matches(ar, PROCESS_STATUS_ERROR_701))) {
            return PROCESS_STATUS_ERROR_701;
        }
        return PROCESS_STATUS_PASSED;
    }

    /**
     * Legacy {@code getStatusChangeReason} priority cascade ported verbatim:
     * <ol>
     *   <li>empty list → {@link #REASON_NO_TASK}</li>
     *   <li>first non-null/non-blank {@code actionResponse.statusChangeReason}</li>
     *   <li>otherwise → {@link #REASON_SUCCESS} when processStatus == PASSED,
     *       else {@link #REASON_FAILURE}</li>
     * </ol>
     */
    String deriveStatusChangeReason(List<ActionResponse> responses, String processStatus) {
        if (responses == null || responses.isEmpty()) {
            return REASON_NO_TASK;
        }
        for (ActionResponse ar : responses) {
            if (ar == null) continue;
            String reason = ar.getStatusChangeReason();
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
        }
        return PROCESS_STATUS_PASSED.equalsIgnoreCase(processStatus) ? REASON_SUCCESS : REASON_FAILURE;
    }

    private static boolean matches(ActionResponse ar, String expected) {
        return ar != null && expected.equalsIgnoreCase(ar.getTaskStatusCode());
    }
}
