package ca.siva.orchestrator.service;

import ca.siva.orchestrator.domain.TaskStatus;
import ca.siva.orchestrator.dto.ActionResponse;
import ca.siva.orchestrator.dto.tmf.ProcessFlow.Characteristic;
import ca.siva.orchestrator.dto.tmf.TaskFlow;
import ca.siva.orchestrator.entity.TaskExecution;
import ca.siva.orchestrator.entity.TaskExecutionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function tests for {@link BatchKickoutEvaluator}.
 *
 * <p>The evaluator no longer touches the database — it operates on a
 * pre-loaded {@link BatchResults} snapshot built by
 * {@link BatchResultsLoader}. These tests construct the snapshot in-memory,
 * pin one rule of the kickout decision per test, and never spin up a Spring
 * context or a Mockito stub.</p>
 *
 * <h4>Decision contract pinned by this class</h4>
 * <ul>
 *   <li>Empty batch → no kickout.</li>
 *   <li>Row whose persisted status is anything other than COMPLETED → kickout
 *       (without inspecting the body).</li>
 *   <li>Row whose deserialized {@link ActionResponse} carries a
 *       {@code taskFlowResponse.characteristic[name=taskStatus]} value other
 *       than {@code Passed} → kickout.</li>
 *   <li>Missing parsed response (loader couldn't parse) on a COMPLETED row →
 *       no kickout signal from the body, fall through.</li>
 *   <li>Missing {@code taskFlowResponse} or missing {@code taskStatus}
 *       characteristic on a COMPLETED row → no kickout signal from the body,
 *       fall through.</li>
 *   <li>First failing row in arrival order wins; subsequent rows are not
 *       inspected.</li>
 * </ul>
 */
class BatchKickoutEvaluatorTest {

    private static final String CORRELATION_ID = "corr-eval";

    private final BatchKickoutEvaluator evaluator = new BatchKickoutEvaluator();

    @Test
    void emptyBatch_noKickout() {
        // Defensive: a barrier that drains with zero persisted rows (should
        // not happen in production, but the evaluator must not NPE) returns
        // no kickout — the orchestration loop will then promote the next batch.
        BatchResults batch = new BatchResults(List.of(), List.of());

        assertThat(evaluator.evaluate(batch)).isEmpty();
    }

    @Test
    void rowStatusFailed_kicksOutWithoutInspectingBody() {
        // Row-status check is fail-fast: it MUST trigger the kickout before
        // the body cascade so a FAILED row with a clean (or absent) body
        // still kicks out. The reason mentions row.status so an operator
        // can grep logs to find which row fired the decision.
        TaskExecution row = newRow("badAction", TaskStatus.FAILED);
        BatchResults batch = new BatchResults(List.of(row), Arrays.asList((ActionResponse) null));

        Optional<String> reason = evaluator.evaluate(batch);

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("badAction").contains("row.status=FAILED");
    }

    @Test
    void rowStatusCancelled_kicksOut() {
        // Same arm as FAILED — anything that isn't COMPLETED is a kickout.
        // CANCELLED in particular indicates upstream backpressure / manual
        // intervention; the flow must NOT silently promote past it.
        TaskExecution row = newRow("cancelledAction", TaskStatus.CANCELLED);
        BatchResults batch = new BatchResults(List.of(row), Arrays.asList((ActionResponse) null));

        Optional<String> reason = evaluator.evaluate(batch);

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("row.status=CANCELLED");
    }

    @Test
    void completedRow_taskStatusPassed_isClean() {
        // The happy path: row is COMPLETED, taskStatus characteristic is
        // "Passed" → no kickout, evaluator returns empty.
        TaskExecution row = newRow("goodAction", TaskStatus.COMPLETED);
        ActionResponse response = responseWithTaskStatus("Passed");
        BatchResults batch = new BatchResults(List.of(row), List.of(response));

        assertThat(evaluator.evaluate(batch)).isEmpty();
    }

    @Test
    void completedRow_taskStatusOtherThanPassed_kicksOut() {
        // SOLE business-level kickout signal: a taskStatus characteristic
        // whose value is not "Passed". Body-level check runs only on
        // COMPLETED rows (FAILED rows are caught earlier by the row-status
        // arm). The reason carries the actual value so the operator sees
        // "Failed", "Errored", etc. without grepping payloads.
        TaskExecution row = newRow("flakyAction", TaskStatus.COMPLETED);
        ActionResponse response = responseWithTaskStatus("Failed");
        BatchResults batch = new BatchResults(List.of(row), List.of(response));

        Optional<String> reason = evaluator.evaluate(batch);

        assertThat(reason).isPresent();
        assertThat(reason.get())
                .contains("flakyAction")
                .contains("actionResponse.taskFlowResponse.taskStatus=Failed");
    }

    @Test
    void completedRow_taskStatusComparisonIsCaseInsensitive() {
        // Spec uses "Passed" but downstream may emit "passed" or "PASSED" —
        // a case-sensitive comparison would falsely kick out. Locking the
        // case-insensitive contract here so a future "fix" that tightens
        // the comparison fails this test loudly.
        TaskExecution row = newRow("upperAction", TaskStatus.COMPLETED);
        ActionResponse response = responseWithTaskStatus("PASSED");
        BatchResults batch = new BatchResults(List.of(row), List.of(response));

        assertThat(evaluator.evaluate(batch)).isEmpty();
    }

    @Test
    void completedRow_nullParsedResponse_noKickoutFromBody() {
        // BatchResultsLoader emits null at any index whose result_json was
        // absent or unparseable. The evaluator must treat that as "no body
        // signal" — the persisted row's status field stays authoritative.
        // Here the row is COMPLETED, so nothing kicks out.
        TaskExecution row = newRow("noBodyAction", TaskStatus.COMPLETED);
        BatchResults batch = new BatchResults(List.of(row), Arrays.asList((ActionResponse) null));

        assertThat(evaluator.evaluate(batch)).isEmpty();
    }

    @Test
    void completedRow_missingTaskFlowResponse_noKickout() {
        // No taskFlowResponse → no characteristic to inspect → no body
        // signal. Mirrors what task-runners that don't emit a TMF taskFlow
        // body would produce. The persisted row's COMPLETED status is
        // authoritative; promote.
        TaskExecution row = newRow("bareAction", TaskStatus.COMPLETED);
        ActionResponse response = ActionResponse.builder().taskFlowResponse(null).build();
        BatchResults batch = new BatchResults(List.of(row), List.of(response));

        assertThat(evaluator.evaluate(batch)).isEmpty();
    }

    @Test
    void completedRow_taskFlowResponseWithoutTaskStatusChar_noKickout() {
        // taskFlowResponse exists but carries no taskStatus characteristic
        // (only unrelated ones). Same arm as missing taskFlowResponse —
        // the evaluator skips characteristics it doesn't recognize and
        // does not synthesize a kickout from absence.
        Characteristic other = Characteristic.builder()
                .name("someOtherChar").value("anything").valueType("string").build();
        TaskExecution row = newRow("noStatusCharAction", TaskStatus.COMPLETED);
        ActionResponse response = ActionResponse.builder()
                .taskFlowResponse(TaskFlow.builder().characteristic(List.of(other)).build())
                .build();
        BatchResults batch = new BatchResults(List.of(row), List.of(response));

        assertThat(evaluator.evaluate(batch)).isEmpty();
    }

    @Test
    void completedRow_taskStatusValueNull_noKickout() {
        // taskStatus characteristic is present but its value is null —
        // we treat that as "no signal" rather than synthesizing a failure
        // out of incomplete data. If the downstream wanted to fail it,
        // it would have set the value to "Failed" (or anything != Passed).
        Characteristic ts = Characteristic.builder()
                .name("taskStatus").value(null).valueType("string").build();
        TaskExecution row = newRow("nullValueAction", TaskStatus.COMPLETED);
        ActionResponse response = ActionResponse.builder()
                .taskFlowResponse(TaskFlow.builder().characteristic(List.of(ts)).build())
                .build();
        BatchResults batch = new BatchResults(List.of(row), List.of(response));

        assertThat(evaluator.evaluate(batch)).isEmpty();
    }

    @Test
    void multipleRows_firstFailingWinsInArrivalOrder() {
        // Arrival order matters: the kickout reason must come from the
        // FIRST failing row. Two failures sandwiched between cleans —
        // the test asserts the EARLIER failure wins so logs always point
        // to the root cause that fired first.
        TaskExecution clean = newRow("cleanAction", TaskStatus.COMPLETED);
        TaskExecution firstFail = newRow("firstFailAction", TaskStatus.COMPLETED);
        TaskExecution secondFail = newRow("secondFailAction", TaskStatus.COMPLETED);

        ActionResponse cleanResp = responseWithTaskStatus("Passed");
        ActionResponse firstFailResp = responseWithTaskStatus("Failed");
        ActionResponse secondFailResp = responseWithTaskStatus("Errored");

        BatchResults batch = new BatchResults(
                List.of(clean, firstFail, secondFail),
                List.of(cleanResp, firstFailResp, secondFailResp));

        Optional<String> reason = evaluator.evaluate(batch);

        assertThat(reason).isPresent();
        assertThat(reason.get())
                .contains("firstFailAction")
                .contains("taskStatus=Failed")
                .doesNotContain("secondFailAction")
                .doesNotContain("Errored");
    }

    @Test
    void rowStatusBeatsBodySignal_evenWhenBodyClean() {
        // Defensive ordering check: if a row is FAILED at the persisted-row
        // level but somehow carries a "Passed" body (legacy data, replay,
        // etc.), the row-status arm must still win. We never let a clean
        // body mask a failed row.
        TaskExecution row = newRow("zombieAction", TaskStatus.FAILED);
        ActionResponse response = responseWithTaskStatus("Passed");
        BatchResults batch = new BatchResults(List.of(row), List.of(response));

        Optional<String> reason = evaluator.evaluate(batch);

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("row.status=FAILED");
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static TaskExecution newRow(String actionName, TaskStatus status) {
        TaskExecution te = new TaskExecution();
        te.setId(new TaskExecutionId(CORRELATION_ID, "tf-" + actionName, Instant.now()));
        te.setActionName(actionName);
        te.setStatus(status);
        return te;
    }

    private static ActionResponse responseWithTaskStatus(String value) {
        Characteristic ts = Characteristic.builder()
                .name("taskStatus").value(value).valueType("string").build();
        return ActionResponse.builder()
                .taskFlowResponse(TaskFlow.builder().characteristic(List.of(ts)).build())
                .build();
    }
}
