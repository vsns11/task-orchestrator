package ca.siva.orchestrator.kafka;

import ca.siva.orchestrator.actionregistry.ActionDefinition;
import ca.siva.orchestrator.actionregistry.ActionRegistry;
import ca.siva.orchestrator.dag.DagDefinition;
import ca.siva.orchestrator.domain.MessageNames;
import ca.siva.orchestrator.domain.ExecutionMode;
import ca.siva.orchestrator.domain.Intent;
import ca.siva.orchestrator.domain.MessageType;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.TaskCommand.Action;
import ca.siva.orchestrator.dto.TaskCommand.Batch;
import ca.siva.orchestrator.dto.TaskCommand.Execution;
import ca.siva.orchestrator.dto.TaskCommand.Inputs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
     * @param processFlow       original processFlow payload
     * @param dependencyResults results from actions this task depends on (keyed by actionName), or null
     */
    public Optional<TaskCommand> buildTaskExecute(String correlationId, String dagKey,
                                                           DagDefinition.BatchDef batch,
                                                           DagDefinition.ActionDef actionDef,
                                                           Map<String, Object> processFlow,
                                                           Map<String, Object> dependencyResults) {
        // Chained lookup: actionName → actionCode → dcxActionCode
        Optional<ActionDefinition> resolvedOpt = actionRegistry.resolve(actionDef.getActionName());
        if (resolvedOpt.isEmpty()) {
            log.warn("Unknown actionName={} in DAG {} - skipping", actionDef.getActionName(), dagKey);
            return Optional.empty();
        }
        ActionDefinition actionIdentity = resolvedOpt.get();

        TaskCommand taskCommand = buildBase(correlationId, MessageNames.TASK_EXECUTE,
                MessageType.COMMAND, Sources.TASK_ORCHESTRATOR);
        taskCommand.setIntent(Intent.EXECUTE);
        taskCommand.setDagKey(dagKey);

        // Action identity from registry, actionName from DAG
        taskCommand.setAction(Action.builder()
                .actionName(actionDef.getActionName())
                .actionCode(actionIdentity.actionCode())
                .dcxActionCode(actionIdentity.dcxActionCode())
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
}
