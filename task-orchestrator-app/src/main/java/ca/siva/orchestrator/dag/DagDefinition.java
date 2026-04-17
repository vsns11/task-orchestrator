package ca.siva.orchestrator.dag;

import ca.siva.orchestrator.domain.ExecutionMode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Optional;

/**
 * YAML-deserializable definition of a directed acyclic graph (DAG)
 * that drives multi-batch orchestration.
 *
 * <p>Each action is identified by {@code actionName} — this is the only key
 * the DAG uses. At startup, the orchestrator loads the action registry API
 * and builds maps from actionName → actionCode and actionName → dcxActionCode.
 * When publishing task.execute commands, it hydrates the full triplet from those maps.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DagDefinition {

    private String dagKey;
    private Match match;
    private List<BatchDef> batches;

    /** Finds a batch by its index. */
    public Optional<BatchDef> batch(int index) {
        return Optional.ofNullable(batches)
                .flatMap(bs -> bs.stream()
                        .filter(b -> b.getIndex() == index)
                        .findFirst());
    }

    /** Returns true if a batch with the given index exists. */
    public boolean hasBatch(int index) {
        return batch(index).isPresent();
    }

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Match {
        private String processFlowSpecification;
    }

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BatchDef {
        private int index;
        private List<ActionDef> actions;
    }

    /**
     * Action definition within the DAG.
     *
     * <ul>
     *   <li>{@code actionName} — the primary key, used to look up actionCode and
     *       dcxActionCode from the action registry at command-build time</li>
     *   <li>{@code executionMode} — SYNC (blocking) or ASYNC (callback-based)</li>
     *   <li>{@code dependsOn} — list of actionNames whose results this action needs</li>
     * </ul>
     *
     * <p>Retry config (maxAttempts, timeoutMs) is NOT defined here — it's the
     * task-runner's responsibility via its own configuration.</p>
     */
    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ActionDef {
        private String        actionName;
        private ExecutionMode executionMode;

        /**
         * Optional discriminator for the DCX lookup's composite key
         * {@code (actionCode,flowType,modemType)}. When omitted, the
         * registry falls back to the DEFAULT/DEFAULT row — matching the
         * ActionBuilder convention where flows that don't differentiate
         * by business flow type hit the single shared row.
         *
         * <p>Corresponds to {@code bpmnType} on the upstream
         * {@code ActionBuilder.getDcxCodeByActionName} call.</p>
         */
        private String flowType;

        /**
         * Optional modem-type discriminator for the DCX lookup's composite
         * key {@code (actionCode,flowType,modemType)}. When omitted, the
         * registry falls back to the DEFAULT slot (or the kickout branch
         * when the actionCode starts with {@code '0'}).
         */
        private String modemType;

        /**
         * List of actionNames whose results this action needs.
         * At command-build time, the orchestrator loads the latest COMPLETED
         * result for each listed action from the task_execution table and
         * passes it in {@code inputs.dependencyResults}.
         *
         * <p>Example: if sendNotification depends on runVoiceDiagnostic's result,
         * set {@code dependsOn: [runVoiceDiagnostic]}.</p>
         */
        private List<String> dependsOn;
    }
}
