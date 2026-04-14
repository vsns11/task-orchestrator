package ca.siva.orchestrator.service;

import ca.siva.orchestrator.domain.MessageNames;
import ca.siva.orchestrator.domain.Sources;
import ca.siva.orchestrator.dto.TaskCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Top-level message dispatcher for the orchestrator.
 *
 * <p>Receives every {@link TaskCommand} from the Kafka listener,
 * filters out own messages, and routes to the appropriate handler based
 * on {@code messageName}.</p>
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
     * Main entry point: filters, logs, and dispatches the message.
     * Never throws — all errors are caught by the caller (TaskCommandListener).
     */
    public void handle(TaskCommand message) {
        if (message == null || message.getMessageName() == null) {
            log.warn("Received envelope without messageName - skipping");
            return;
        }

        // Filter out our own task.execute commands — the orchestrator publishes these
        // and would otherwise re-consume them from the same topic
        if (MessageNames.TASK_EXECUTE.equals(message.getMessageName())
                && Sources.TASK_ORCHESTRATOR.equals(message.getSource())) {
            return;
        }

        log.info("HANDLE messageName={} source={} corrId={} eventId={}",
                message.getMessageName(), message.getSource(),
                message.getCorrelationId(), message.getEventId());

        switch (message.getMessageName()) {
            case MessageNames.PROCESS_FLOW_INITIATED -> handleInitiated(message);
            case MessageNames.TASK_EVENT             -> handleTaskEvent(message);
            case MessageNames.TASK_SIGNAL            -> handleTaskSignal(message);
            case MessageNames.TASK_EXECUTE           -> { /* foreign producer — ignore */ }
            case MessageNames.FLOW_LIFECYCLE         -> { /* lifecycle event — ignore (published by us) */ }
            default -> log.warn("Unknown messageName={} - ignoring", message.getMessageName());
        }
    }

    private void handleInitiated(TaskCommand message) {
        if (message.getDagKey() == null || message.getInputs() == null
                || message.getInputs().getProcessFlow() == null) {
            log.warn("processFlow.initiated missing dagKey or inputs.processFlow - eventId={}",
                    message.getEventId());
            return;
        }

        Map<String, Object> pf = message.getInputs().getProcessFlow();
        Optional<String> pfId = Optional.ofNullable(pf.get("id")).map(Object::toString);

        pfId.ifPresentOrElse(
                id -> barrier.initiateFlow(id, message.getDagKey(), pf),
                () -> log.warn("processFlow.initiated inputs.processFlow has no id - eventId={}",
                        message.getEventId())
        );
    }

    private void handleTaskEvent(TaskCommand message) {
        taskExecution.upsert(message);
        barrier.applyTaskEvent(message);
    }

    private void handleTaskSignal(TaskCommand message) {
        log.info("SIGNAL observed: task={} action={} downstream={}",
                Optional.ofNullable(message.getTask())
                        .map(TaskCommand.Task::getId).orElse(null),
                Optional.ofNullable(message.getAction())
                        .map(TaskCommand.Action::getActionName).orElse(null),
                Optional.ofNullable(message.getInputs())
                        .map(TaskCommand.Inputs::getDownstream)
                        .map(TaskCommand.Downstream::getId).orElse(null));
    }
}
