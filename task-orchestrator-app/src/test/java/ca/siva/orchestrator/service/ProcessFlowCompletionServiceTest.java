package ca.siva.orchestrator.service;

import ca.siva.orchestrator.client.Tmf701Client;
import ca.siva.orchestrator.domain.ProcessFlowStateType;
import ca.siva.orchestrator.dto.ActionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ProcessFlowCompletionService} — the legacy
 * {@code processFlowEntryAction} cascade ported into the orchestrator. Each
 * test pins one rule of the cascade so an accidental refactor of the
 * derivation logic produces a failing test, not a silent payload regression.
 *
 * <h4>Coverage</h4>
 * <ul>
 *   <li>{@code deriveProcessStatus} — every priority tier (empty, null
 *       taskStatusCode, ERROR, FAILED, ERROR_701, PASSED).</li>
 *   <li>{@code deriveStatusChangeReason} — every priority tier (empty,
 *       first non-null per-action reason, default SUCCESS / FAILURE based
 *       on processStatus).</li>
 *   <li>{@code patchCompleted} / {@code patchFailed} — derived values are
 *       forwarded to {@link Tmf701Client#patchProcessFlowFinalState} from
 *       the supplied {@link ActionResponse} list. There is no DB access and
 *       no JSON parsing in the service any more — the load is owned by
 *       {@link BatchResultsLoader} so the cost is paid once per batch close
 *       in {@link BarrierService}.</li>
 *   <li>Tolerant input — {@code null} entries in the responses list are
 *       silently dropped by the cascade, mirroring the old behaviour where
 *       a single unparseable {@code result_json} was skipped instead of
 *       failing the whole final-state PATCH.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProcessFlowCompletionServiceTest {

    private static final String CORRELATION_ID = "pf-derive";

    @Mock Tmf701Client tmf701;

    private ProcessFlowCompletionService service;

    @BeforeEach
    void setUp() {
        service = new ProcessFlowCompletionService(tmf701);
    }

    // ─── deriveProcessStatus cascade ──────────────────────────────────────

    @Test
    void deriveProcessStatus_emptyList_isFailed() {
        // Legacy: "no actionResponseList" → PROCESS_STATUS_FAILED. Mirrors the
        // upstream getProcessStatus pre-cascade guard.
        assertThat(service.deriveProcessStatus(List.of()))
                .isEqualTo(ProcessFlowCompletionService.PROCESS_STATUS_FAILED);
    }

    @Test
    void deriveProcessStatus_anyTaskStatusNull_isError() {
        // Legacy: any ar.getTaskStatus() == null short-circuits to ERROR
        // BEFORE any of the value-based filters. Locking the order here so
        // a refactor that flattens the cascade can't accidentally let a
        // null-status response slip through to PASSED.
        var responses = List.of(
                ActionResponse.builder().taskStatusCode("PASSED").build(),
                ActionResponse.builder().taskStatusCode(null).build());
        assertThat(service.deriveProcessStatus(responses))
                .isEqualTo(ProcessFlowCompletionService.PROCESS_STATUS_ERROR);
    }

    @Test
    void deriveProcessStatus_errorBeatsFailed() {
        var responses = List.of(
                ActionResponse.builder().taskStatusCode("FAILED").build(),
                ActionResponse.builder().taskStatusCode("ERROR").build());
        assertThat(service.deriveProcessStatus(responses))
                .isEqualTo(ProcessFlowCompletionService.PROCESS_STATUS_ERROR);
    }

    @Test
    void deriveProcessStatus_failedBeatsError701() {
        var responses = List.of(
                ActionResponse.builder().taskStatusCode("ERROR_701").build(),
                ActionResponse.builder().taskStatusCode("FAILED").build());
        assertThat(service.deriveProcessStatus(responses))
                .isEqualTo(ProcessFlowCompletionService.PROCESS_STATUS_FAILED);
    }

    @Test
    void deriveProcessStatus_error701BeatsPassed() {
        var responses = List.of(
                ActionResponse.builder().taskStatusCode("PASSED").build(),
                ActionResponse.builder().taskStatusCode("ERROR_701").build());
        assertThat(service.deriveProcessStatus(responses))
                .isEqualTo(ProcessFlowCompletionService.PROCESS_STATUS_ERROR_701);
    }

    @Test
    void deriveProcessStatus_allPassed_isPassed() {
        var responses = List.of(
                ActionResponse.builder().taskStatusCode("PASSED").build(),
                ActionResponse.builder().taskStatusCode("passed").build()); // case-insensitive
        assertThat(service.deriveProcessStatus(responses))
                .isEqualTo(ProcessFlowCompletionService.PROCESS_STATUS_PASSED);
    }

    // ─── deriveStatusChangeReason cascade ─────────────────────────────────

    @Test
    void deriveStatusChangeReason_emptyList_isNoTaskPerformed() {
        assertThat(service.deriveStatusChangeReason(List.of(), "anything"))
                .isEqualTo(ProcessFlowCompletionService.REASON_NO_TASK);
    }

    @Test
    void deriveStatusChangeReason_firstNonNullReasonWins() {
        // Legacy: stream().filter(non-null reason).findFirst() — order matters,
        // so the test feeds reasons in arrival order and asserts the first
        // wins regardless of which downstream actually produced it.
        var responses = List.of(
                ActionResponse.builder().statusChangeReason(null).build(),
                ActionResponse.builder().statusChangeReason("LATENCY_TIMEOUT").build(),
                ActionResponse.builder().statusChangeReason("OTHER_REASON").build());
        assertThat(service.deriveStatusChangeReason(responses, "FAILED"))
                .isEqualTo("LATENCY_TIMEOUT");
    }

    @Test
    void deriveStatusChangeReason_blankReasonsTreatedAsAbsent() {
        // Empty / whitespace strings should NOT win the cascade — they would
        // produce a meaningless characteristic value. Skip them just like
        // null and fall through to the default.
        var responses = List.of(
                ActionResponse.builder().statusChangeReason("").build(),
                ActionResponse.builder().statusChangeReason("   ").build());
        assertThat(service.deriveStatusChangeReason(responses,
                ProcessFlowCompletionService.PROCESS_STATUS_PASSED))
                .isEqualTo(ProcessFlowCompletionService.REASON_SUCCESS);
    }

    @Test
    void deriveStatusChangeReason_defaultsToSuccessOnPassed() {
        var responses = List.of(ActionResponse.builder().build());
        assertThat(service.deriveStatusChangeReason(responses,
                ProcessFlowCompletionService.PROCESS_STATUS_PASSED))
                .isEqualTo(ProcessFlowCompletionService.REASON_SUCCESS);
    }

    @Test
    void deriveStatusChangeReason_defaultsToFailureOnAnythingElse() {
        var responses = List.of(ActionResponse.builder().build());
        assertThat(service.deriveStatusChangeReason(responses, "ERROR"))
                .isEqualTo(ProcessFlowCompletionService.REASON_FAILURE);
    }

    // ─── patchCompleted / patchFailed end-to-end ──────────────────────────

    @Test
    void patchCompleted_forwardsDerivedValuesFromSuppliedResponses() {
        // Two passed actions, one carrying a per-action reason. The cascade
        // resolves to PROCESS_STATUS_PASSED + the per-action reason — proving
        // the supplied list flows straight into the wire call without the
        // service re-querying the DB or re-parsing JSON.
        var responses = List.of(
                ActionResponse.builder().taskStatusCode("PASSED").build(),
                ActionResponse.builder().taskStatusCode("PASSED").statusChangeReason("ALL_GOOD").build());

        service.patchCompleted(CORRELATION_ID, responses);

        verify(tmf701).patchProcessFlowFinalState(
                eq(CORRELATION_ID),
                eq(ProcessFlowStateType.COMPLETED),
                eq(ProcessFlowCompletionService.PROCESS_STATUS_PASSED),
                eq("ALL_GOOD"));
    }

    @Test
    void patchFailed_mixedBatchUsesCascadedFailure() {
        // Passed + Failed in same batch. processStatus must be FAILED (legacy
        // priority), reason must come from the first non-null per-action
        // reason — here "RETRY_BUDGET_EXHAUSTED". This is the kickout path:
        // BarrierService loads the batch once via BatchResultsLoader and
        // hands the same responses to BatchKickoutEvaluator AND patchFailed,
        // so the wire payload reflects exactly what the kickout decision saw.
        var responses = List.of(
                ActionResponse.builder().taskStatusCode("PASSED").build(),
                ActionResponse.builder()
                        .taskStatusCode("FAILED")
                        .statusChangeReason("RETRY_BUDGET_EXHAUSTED")
                        .build());

        service.patchFailed(CORRELATION_ID, responses);

        verify(tmf701).patchProcessFlowFinalState(
                eq(CORRELATION_ID),
                eq(ProcessFlowStateType.FAILED),
                eq(ProcessFlowCompletionService.PROCESS_STATUS_FAILED),
                eq("RETRY_BUDGET_EXHAUSTED"));
    }

    @Test
    void patchFailed_emptyResponseList_sendsNoTaskPerformedDefaults() {
        // Empty list maps to PROCESS_STATUS_FAILED + REASON_NO_TASK per the
        // legacy guard. This is the empty-batch defensive path in
        // BarrierService.seedAndPublishBatch — a DAG batch with zero actions
        // produces no rows, so the responses list is empty and the operator
        // sees the meaningful "No Task/Action Performed" reason.
        service.patchFailed(CORRELATION_ID, List.of());

        verify(tmf701).patchProcessFlowFinalState(
                eq(CORRELATION_ID),
                eq(ProcessFlowStateType.FAILED),
                eq(ProcessFlowCompletionService.PROCESS_STATUS_FAILED),
                eq(ProcessFlowCompletionService.REASON_NO_TASK));
    }

    @Test
    void finalStatePatch_skipsNullEntriesInResponseList() {
        // BatchResultsLoader emits null at any index whose result_json was
        // absent or unparseable. The cascade must silently drop those
        // entries instead of NPEing — losing one task's contribution is
        // preferable to never marking the flow completed.
        var responses = Arrays.asList(
                ActionResponse.builder().taskStatusCode("PASSED").build(),
                null,                                                          // loader couldn't parse this row
                ActionResponse.builder().taskStatusCode("PASSED").build());

        service.patchCompleted(CORRELATION_ID, responses);

        // Two valid responses, both PASSED → PROCESS_STATUS_PASSED, default SUCCESS reason.
        verify(tmf701).patchProcessFlowFinalState(
                eq(CORRELATION_ID),
                eq(ProcessFlowStateType.COMPLETED),
                eq(ProcessFlowCompletionService.PROCESS_STATUS_PASSED),
                eq(ProcessFlowCompletionService.REASON_SUCCESS));
    }

    @Test
    void finalStatePatch_invokesTmf701ExactlyOnce_perCall() {
        // The service must produce exactly ONE wire call per public method
        // invocation. A regression that double-invoked would generate
        // duplicate state changes downstream — silent and nasty. Mockito's
        // bare verify(mock) (no times() qualifier) implicitly asserts
        // times(1), so this captures both the cardinality and the state value.
        service.patchCompleted(CORRELATION_ID, List.of());

        ArgumentCaptor<ProcessFlowStateType> stateCaptor = ArgumentCaptor.forClass(ProcessFlowStateType.class);
        verify(tmf701).patchProcessFlowFinalState(
                eq(CORRELATION_ID), stateCaptor.capture(), any(), any());
        assertThat(stateCaptor.getValue())
                .isEqualTo(ProcessFlowStateType.COMPLETED);
    }
}
