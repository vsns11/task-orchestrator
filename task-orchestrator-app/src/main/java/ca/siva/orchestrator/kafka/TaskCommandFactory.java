package ca.siva.orchestrator.kafka;

import ca.siva.orchestrator.actionregistry.ActionRegistry;
import ca.siva.orchestrator.dag.DagDefinition;
import ca.siva.orchestrator.domain.ExecutionMode;
import ca.siva.orchestrator.domain.Intent;
import ca.siva.orchestrator.domain.MessageName;
import ca.siva.orchestrator.domain.MessageType;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.TaskCommand.Action;
import ca.siva.orchestrator.dto.TaskCommand.Batch;
import ca.siva.orchestrator.dto.TaskCommand.Execution;
import ca.siva.orchestrator.dto.TaskCommand.Inputs;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static ca.siva.orchestrator.actionregistry.ActionNames.DEFAULT;

/**
 * Factory for constructing {@link TaskCommand} messages.
 *
 * <p>The DAG defines actions by {@code actionName} with execution parameters.
 * This factory looks up the {@code actionCode} and {@code dcxActionCode} from
 * the {@link ActionRegistry} (loaded at startup from the external API) and
 * hydrates the full action triplet into the task.execute command.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskCommandFactory {

    private final ActionRegistry actionRegistry;

    /** Creates a base envelope with metadata fields populated. */
    public TaskCommand buildBase(String correlationId, String messageName,
                                 MessageType messageType, String source) {
        TaskCommand taskCommand = new TaskCommand();
        taskCommand.setEventId(UUID.randomUUID().toString());
        taskCommand.setCorrelationId(correlationId);
        taskCommand.setEventTime(Instant.now());
        taskCommand.setMessageType(messageType);
        taskCommand.setMessageName(messageName);
        taskCommand.setSource(source);
        return taskCommand;
    }

    /**
     * Builds a {@code task.execute} command for one action in a batch.
     *
     * <p>Looks up {@code actionCode} and {@code dcxActionCode} from the registry
     * using the DAG's {@code actionName}. Execution params (mode, timeout, retries)
     * come from the DAG definition.</p>
     *
     * @return the envelope, or empty if the actionName is unknown in the registry
     */
    /**
     * @param correlationId     processFlow UUID
     * @param dagKey            DAG identifier
     * @param batch             the batch this action belongs to
     * @param actionDef         action definition from the DAG
     * @param processFlow       the TMF-701 processFlow object (fetched via GET API)
     * @param dependencyResults results from actions this task depends on (keyed by actionName), or null
     */
    public Optional<TaskCommand> buildTaskExecute(String correlationId, String dagKey,
                                                           DagDefinition.BatchDef batch,
                                                           DagDefinition.ActionDef actionDef,
                                                           ProcessFlow processFlow,
                                                           Map<String, Object> dependencyResults) {
        // Defensive guards: a malformed DAG YAML (missing actionName, empty
        // actions list on a batch) must not explode with NPE here — return
        // empty so the caller treats the slot as "nothing to publish" and logs
        // a warning instead of failing the whole batch seed.
        if (actionDef == null || actionDef.getActionName() == null || actionDef.getActionName().isBlank()) {
            log.warn("Skipping task.execute build for DAG {} — actionDef is null or missing actionName", dagKey);
            return Optional.empty();
        }
        if (batch == null || batch.getActions() == null) {
            log.warn("Skipping task.execute build for DAG {} — batch is null or has no actions list", dagKey);
            return Optional.empty();
        }
        String actionName = actionDef.getActionName();

        // Step 1 (ActionBuilder.getActionCode): actionName → actionCode.
        // We short-circuit the command if the name is unknown — publishing a
        // task.execute without an actionCode would cause the task-runner to
        // reject it anyway.
        String actionCode = actionRegistry.getActionCodeByName(actionName);
        if (actionCode == null) {
            log.warn("Unknown actionName={} in DAG {} - skipping (not in action-code registry)",
                    actionName, dagKey);
            return Optional.empty();
        }

        // Step 2 (ActionBuilder.getDcxCodeByActionName): actionName + flowType
        // + modemType → dcxActionCode, with codeStartsWith0 branching and
        // DEFAULT/DEFAULT fallback inside the registry. flowType/modemType come
        // from the DAG action when present; otherwise they default to DEFAULT,
        // matching the single-row case that covers most real data.
        String flowType  = nonBlankOrDefault(actionDef.getFlowType());
        String modemType = nonBlankOrDefault(actionDef.getModemType());
        String dcxActionCode = actionRegistry.getDcxActionCodeByName(actionName, flowType, modemType);
        if (dcxActionCode == null) {
            log.warn("No dcxActionCode for actionName={} actionCode={} flowType={} modemType={}"
                            + " — publishing task.execute with null dcxActionCode",
                    actionName, actionCode, flowType, modemType);
        }

        TaskCommand taskCommand = buildBase(correlationId, MessageName.TASK_EXECUTE.getValue(),
                MessageType.COMMAND, Sources.TASK_ORCHESTRATOR);
        taskCommand.setIntent(Intent.EXECUTE);
        taskCommand.setDagKey(dagKey);

        // Action identity hydrated from the registry via the two direct calls above.
        taskCommand.setAction(Action.builder()
                .actionName(actionName)
                .actionCode(actionCode)
                .dcxActionCode(dcxActionCode)
                .build());

        taskCommand.setBatch(Batch.builder()
                .index(batch.getIndex())
                .total(batch.getActions().size())
                .build());

        // Only execution mode comes from the DAG. Retry config (maxAttempts, timeoutMs)
        // is the task-runner's responsibility — not set by the orchestrator.
        taskCommand.setExecution(Execution.builder()
                .mode(actionDef.getExecutionMode())
                .build());

        TaskCommand.Inputs.InputsBuilder inputsBuilder = Inputs.builder()
                .processFlow(processFlow);
        if (dependencyResults != null && !dependencyResults.isEmpty()) {
            inputsBuilder.dependencyResults(dependencyResults);
        }
        taskCommand.setInputs(inputsBuilder.build());

        return Optional.of(taskCommand);
    }

    /**
     * Coerces blank/null DAG discriminators to {@link ActionNames#DEFAULT} so
     * the composite DCX key has a concrete value in every slot — matches the
     * ActionBuilder convention where empty kcontext values resolve to DEFAULT
     * before {@code buildKeyForDcxActionCode} runs.
     */
    private static String nonBlankOrDefault(String value) {
        return (value == null || value.isBlank()) ? DEFAULT : value;
    }
}
