package ca.siva.orchestrator.service;

import ca.siva.orchestrator.domain.MessageName;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.dto.TaskCommand;
import ca.siva.orchestrator.dto.tmf.ProcessFlow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Top-level dispatcher for the orchestrator.
 *
 * <p>Receives every {@link TaskCommand} from the Kafka listener,
 * filters out its own commands, and routes to the appropriate handler based
 * on {@link MessageName}.</p>
 *
 * <p>Duplicate protection is handled at the business level — BarrierService
 * checks the current task/barrier status before making changes, so re-processing
 * the same event is a safe no-op.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestratorService {

    private final BarrierService       barrier;
    private final TaskExecutionService taskExecution;

    /**
     * Main entry point: filters, logs, and dispatches the command.
     * Never throws — all errors are caught by the caller (TaskCommandListener).
     */
    public void handle(TaskCommand taskCommand) {
        if (taskCommand == null || taskCommand.getMessageName() == null) {
            log.warn("Received envelope without messageName - skipping");
            return;
        }

        // Filter out our own task.execute commands — the orchestrator publishes these
        // and would otherwise re-consume them from the same topic
        if (MessageName.TASK_EXECUTE.getValue().equals(taskCommand.getMessageName())
                && Sources.TASK_ORCHESTRATOR.equals(taskCommand.getSource())) {
            return;
        }

        Optional<MessageName> parsed = MessageName.fromValue(taskCommand.getMessageName());
        if (parsed.isEmpty()) {
            log.warn("Unknown messageName={} - ignoring eventId={}",
                    taskCommand.getMessageName(), taskCommand.getEventId());
            return;
        }

        log.info("HANDLE messageName={} source={} corrId={} eventId={}",
                taskCommand.getMessageName(), taskCommand.getSource(),
                taskCommand.getCorrelationId(), taskCommand.getEventId());

        switch (parsed.get()) {
            case PROCESS_FLOW_INITIATED -> handleInitiated(taskCommand);
            case TASK_EVENT             -> handleTaskEvent(taskCommand);
            case TASK_SIGNAL            -> handleTaskSignal(taskCommand);
            case TASK_EXECUTE           -> log.info(
                    "Skipping task.execute from foreign producer source={} corrId={} eventId={}",
                    taskCommand.getSource(), taskCommand.getCorrelationId(), taskCommand.getEventId());
            case FLOW_LIFECYCLE         -> log.info(
                    "Skipping flow.lifecycle event (published by us) status={} corrId={} eventId={}",
                    taskCommand.getStatus(), taskCommand.getCorrelationId(), taskCommand.getEventId());
        }
    }

    private void handleInitiated(TaskCommand taskCommand) {
        if (taskCommand.getDagKey() == null || taskCommand.getInputs() == null
                || taskCommand.getInputs().getProcessFlow() == null) {
            log.warn("processFlow.initiated missing dagKey or inputs.processFlow - eventId={}",
                    taskCommand.getEventId());
            return;
        }

        ProcessFlow processFlow = taskCommand.getInputs().getProcessFlow();
        String processFlowId = processFlow.getId();

        if (processFlowId == null) {
            log.warn("processFlow.initiated inputs.processFlow has no id - eventId={}",
                    taskCommand.getEventId());
            return;
        }

        barrier.initiateFlow(processFlowId, taskCommand.getDagKey(), processFlow);
    }

    private void handleTaskEvent(TaskCommand taskCommand) {
        taskExecution.upsert(taskCommand);
        barrier.applyTaskEvent(taskCommand);
    }

    private void handleTaskSignal(TaskCommand taskCommand) {
        log.info("SIGNAL observed: action={} downstream={}",
                Optional.ofNullable(taskCommand.getAction())
                        .map(TaskCommand.Action::getActionName).orElse(null),
                Optional.ofNullable(taskCommand.getInputs())
                        .map(TaskCommand.Inputs::getDownstream)
                        .map(TaskCommand.Downstream::getId).orElse(null));
    }
}
