-- Batch barrier: tracks per-batch progress for lazy-seeded DAG execution.
-- One row per (process_flow_id, batch_index) at any time.
-- process_flow_id = correlationId = the processFlow UUID from TMF-701.
CREATE TABLE batch_barrier (
    process_flow_id  VARCHAR(64)  NOT NULL,
    batch_index      SMALLINT     NOT NULL,
    dag_key          VARCHAR(64)  NOT NULL,
    task_total       INT          NOT NULL,
    task_completed   INT          NOT NULL DEFAULT 0,
    task_failed      INT          NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL,
    opened_at        TIMESTAMP,
    closed_at        TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version          BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (process_flow_id, batch_index)
);

CREATE INDEX idx_batch_barrier_status ON batch_barrier (process_flow_id, status);

-- Task execution: per-action audit trail recording each task's lifecycle.
-- process_flow_id = the processFlow UUID from TMF-701 (= correlationId).
-- task_flow_id    = the taskFlow UUID minted by task-runner via POST to TMF-701.
CREATE TABLE task_execution (
    process_flow_id  VARCHAR(64)  NOT NULL,
    task_flow_id     VARCHAR(64)  NOT NULL,
    action_name      VARCHAR(64)  NOT NULL,
    action_code      VARCHAR(64)  NOT NULL,
    batch_index      SMALLINT     NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    started_at       TIMESTAMP,
    finished_at      TIMESTAMP,
    duration_ms      BIGINT,
    result_json      TEXT,
    error_json       TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version          BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (process_flow_id, task_flow_id)
);

CREATE INDEX idx_task_execution_action ON task_execution (process_flow_id, action_name);
