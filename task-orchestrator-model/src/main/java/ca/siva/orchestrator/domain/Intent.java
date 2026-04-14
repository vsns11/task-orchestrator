package ca.siva.orchestrator.domain;

/** What the orchestrator wants the task-runner to do with this command. */
public enum Intent {
    EXECUTE,
    CANCEL,
    RETRY
}
