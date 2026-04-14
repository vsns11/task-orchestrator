package ca.siva.orchestrator.domain;

/**
 * Constants for Kafka {@code messageName} values used in the uniform envelope.
 * These drive the orchestrator's dispatch logic.
 */
public final class MessageNames {

    public static final String PROCESS_FLOW_INITIATED = "processFlow.initiated";
    public static final String TASK_EXECUTE            = "task.execute";
    public static final String TASK_EVENT              = "task.event";
    public static final String TASK_SIGNAL             = "task.signal";
    public static final String FLOW_LIFECYCLE          = "flow.lifecycle";

    private MessageNames() {}
}
