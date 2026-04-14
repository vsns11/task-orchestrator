package ca.siva.orchestrator.domain;

/**
 * Constants for Kafka {@code source} values identifying which component
 * produced a message on the {@code task.command} topic.
 */
public final class Sources {

    public static final String PAMCONSUMER      = "pamconsumer";
    public static final String TASK_ORCHESTRATOR = "task-orchestrator";
    public static final String TASK_RUNNER       = "task-runner";
    public static final String TMF_701           = "tmf-701";

    private Sources() {}
}
