package ca.siva.orchestrator.domain;

/** Lifecycle status of a task execution, carried in {@code task.event} messages. */
public enum TaskStatus {
    INITIAL,
    IN_PROGRESS,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED
}
