package ca.siva.orchestrator.domain;

/** How the task-runner executes an action. */
public enum ExecutionMode {
    /** Blocking — runner calls downstream and waits for the result. */
    SYNC,
    /** Non-blocking — runner starts the call, publishes WAITING, completes on signal. */
    ASYNC
}
