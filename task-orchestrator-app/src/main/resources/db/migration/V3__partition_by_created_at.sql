-- =============================================================================
-- V3: batch_barrier and task_execution as range-partitioned tables.
-- =============================================================================
--
-- Single source of DB truth for the orchestrator. Any pre-existing copies of
-- these tables are dropped outright — there is no data to preserve.
--
-- Design
-- ------
--   * Native Postgres RANGE partitioning on created_at, one partition per day.
--     Retention becomes a cheap DROP PARTITION instead of a mass DELETE, and
--     partition pruning restricts scans to the relevant days.
--   * Postgres requires the partition key to be part of every unique/primary
--     key on a partitioned table, so created_at is part of the composite PK.
--   * Column name correlation_id matches the Kafka correlationId carried
--     end-to-end — the same identifier used by the application layer.
--
-- Partition lifecycle — who owns what
-- -----------------------------------
--   * Flyway (this file) — STRUCTURAL: partitioned parents, indexes,
--                          pg_partman registration, pg_cron job to invoke
--                          pg_partman maintenance. Runs once on apply.
--   * pg_partman          — partition CREATE (premake window) and DROP
--                          (retention). Driven by a single periodic call to
--                          partman.run_maintenance_proc().
--   * pg_cron             — invokes partman.run_maintenance_proc() nightly.
--                          One scheduler per cluster; no risk of N replicas
--                          racing on DDL.
--   * Application (Spring) — NOTHING. The app must not own DDL.
--
-- Prerequisites (enable on the cluster ONCE, typically via the managed DB
-- provider's console):
--   * shared_preload_libraries='pg_cron,pg_partman_bgw'  (restart required)
--   * Extensions pg_cron and pg_partman available for CREATE EXTENSION.
--
-- Why pg_partman instead of hand-rolled plpgsql?
--   * Battle-tested at scale (authored by the Crunchy Data team).
--   * Single entry point (run_maintenance_proc) handles BOTH premake and
--     retention — our cron line becomes one statement.
--   * Handles edge cases we would otherwise reinvent: partition naming,
--     default-partition repartitioning, constraint exclusion, etc.
-- =============================================================================

-- ---------- 1) Drop any prior tables (fresh install) ----------------------
DROP TABLE IF EXISTS batch_barrier  CASCADE;
DROP TABLE IF EXISTS task_execution CASCADE;

-- ---------- 2) batch_barrier: partitioned by created_at (daily) ----------
CREATE TABLE batch_barrier (
    correlation_id  VARCHAR(64)  NOT NULL,
    batch_index     SMALLINT     NOT NULL,
    dag_key         VARCHAR(64)  NOT NULL,
    task_total      INT          NOT NULL,
    task_completed  INT          NOT NULL DEFAULT 0,
    task_failed     INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL,
    opened_at       TIMESTAMP,
    closed_at       TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (correlation_id, batch_index, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_batch_barrier_status      ON batch_barrier (correlation_id, status);
CREATE INDEX idx_batch_barrier_correlation ON batch_barrier (correlation_id, batch_index);

-- ---------- 3) task_execution: partitioned by created_at (daily) ---------
CREATE TABLE task_execution (
    correlation_id  VARCHAR(64)  NOT NULL,
    task_flow_id    VARCHAR(64)  NOT NULL,
    action_name     VARCHAR(64)  NOT NULL,
    action_code     VARCHAR(64)  NOT NULL,
    batch_index     SMALLINT     NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    duration_ms     BIGINT,
    result_json     TEXT,
    error_json      TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (correlation_id, task_flow_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_task_execution_action     ON task_execution (correlation_id, action_name);
CREATE INDEX idx_task_execution_dep_lookup ON task_execution (correlation_id, action_name, status, created_at DESC);
CREATE INDEX idx_task_execution_batch      ON task_execution (correlation_id, batch_index);

-- ---------- 4) Extensions --------------------------------------------------
-- pg_partman is conventionally installed into its own schema (not public).
-- We create the schema here (rather than relying on the image's initdb.d
-- script) so this migration is self-sufficient against any Postgres that
-- has the pg_partman + pg_cron .so files available — whether it's our
-- custom image, a stale pgdata volume that pre-dates the image change, a
-- managed DB instance, or a Terraform-provisioned cluster.
--
-- CREATE EXTENSION ... SCHEMA partman is idempotent when the extension is
-- already installed.
CREATE SCHEMA IF NOT EXISTS partman;
CREATE EXTENSION IF NOT EXISTS pg_partman SCHEMA partman;
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- ---------- 5) Register parents with pg_partman ---------------------------
--
-- create_parent arguments:
--   p_parent_table — schema-qualified parent (public.<name>)
--   p_control      — partition key column (must match PARTITION BY RANGE(...))
--   p_type         — 'range' for native Postgres range partitioning
--   p_interval     — '1 day' for daily buckets
--   p_premake      — how many future partitions to keep pre-created (14 days
--                    of headroom — survives a weekend pg_cron outage).
--
-- Idempotency strategy:
--   Step 1 above unconditionally dropped the parent tables (if they existed),
--   which also drops all their child partitions. But partman.part_config is
--   NOT linked by FK to the parent tables — stale rows survive. So we
--   explicitly DELETE them here, then register parents unconditionally.
--   That way a re-run (after e.g. flyway repair + flyway migrate) lands on a
--   clean partman state instead of a half-registered one where the parents
--   have no children and run_maintenance_proc errors with
--   "Child table given does not exist".

DELETE FROM partman.part_config
 WHERE parent_table IN ('public.batch_barrier', 'public.task_execution');

DO $$
BEGIN
    PERFORM partman.create_parent(
        p_parent_table := 'public.batch_barrier',
        p_control      := 'created_at',
        p_type         := 'range',
        p_interval     := '1 day',
        p_premake      := 14
    );

    PERFORM partman.create_parent(
        p_parent_table := 'public.task_execution',
        p_control      := 'created_at',
        p_type         := 'range',
        p_interval     := '1 day',
        p_premake      := 14
    );
END;
$$;

-- ---------- 6) Retention policy -------------------------------------------
-- 60 days online. Partitions older than this are DROPPED (retention_keep_table
-- = false) on the next run_maintenance cycle, releasing disk immediately.
-- Tuning retention later is a single UPDATE — no migration required.
UPDATE partman.part_config
SET    retention             = '60 days',
       retention_keep_table  = false,
       retention_keep_index  = false,
       infinite_time_partitions = true
WHERE  parent_table IN ('public.batch_barrier', 'public.task_execution');

-- ---------- 7) pg_cron: run pg_partman maintenance nightly ---------------
-- partman.run_maintenance_proc() does BOTH: extends the premake window AND
-- drops partitions past retention. One job, one statement.
--
-- Idempotent: cron.unschedule + cron.schedule lets a re-apply of this file
-- update the schedule expression without leaving duplicates behind.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'orchestrator-partman-maintenance') THEN
        PERFORM cron.unschedule('orchestrator-partman-maintenance');
    END IF;
END;
$$;

SELECT cron.schedule(
    'orchestrator-partman-maintenance',
    '0 1 * * *',
    $$ CALL partman.run_maintenance_proc(); $$
);

-- ---------- 8) First-run bootstrap ----------------------------------------
-- create_parent already seeded p_premake partitions, but we still run
-- maintenance once so retention state is initialised and any drift between
-- the parent tables' initial partition and p_premake is reconciled
-- immediately rather than at 01:00 tomorrow.
--
-- pg_partman ships this work in two forms:
--   * run_maintenance_proc() — PROCEDURE that COMMITs internally between
--     batches (designed for long-running manual runs and for pg_cron, which
--     runs each job in its own autonomous transaction).
--   * run_maintenance()      — FUNCTION equivalent with no internal commits.
--
-- Flyway wraps each migration in a single transaction, so CALL on the
-- procedure raises "invalid transaction termination" (SQLSTATE 2D000).
-- We use the function form here because it is transaction-safe. The cron
-- schedule in step 7 continues to use the procedure, which is correct
-- because pg_cron starts a fresh transaction for each job invocation.
SELECT partman.run_maintenance(p_parent_table := 'public.batch_barrier', p_analyze := false);
SELECT partman.run_maintenance(p_parent_table := 'public.task_execution', p_analyze := false);
