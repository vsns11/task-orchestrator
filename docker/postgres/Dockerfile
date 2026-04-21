# ============================================================================
# Orchestrator Postgres image
# ----------------------------------------------------------------------------
# Extends the official postgres:16 (Debian bookworm) with the two extensions
# that V3__partition_by_created_at.sql requires:
#
#   * pg_partman  — declarative daily range partitioning + retention for
#                   batch_barrier and task_execution.
#   * pg_cron     — nightly scheduler that invokes
#                   partman.run_maintenance_proc() to premake/drop partitions.
#
# Both ship as PGDG packages for Debian bookworm. apt.postgresql.org is already
# configured in the upstream postgres:16 image, so a plain `apt-get install`
# is all we need.
#
# Runtime configuration (set via `command:` in docker-compose, not baked in
# here so it stays overridable):
#   * shared_preload_libraries = pg_cron,pg_partman_bgw  (restart required;
#                                 setting it at container boot handles that)
#   * cron.database_name       = orchestrator
#
# Build:
#   docker build -t orchestrator-postgres:16 ./docker/postgres
# Or indirectly via docker-compose:
#   docker compose build postgres
# ============================================================================
FROM postgres:16

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        postgresql-16-partman \
        postgresql-16-cron \
 && rm -rf /var/lib/apt/lists/*

# Init scripts in /docker-entrypoint-initdb.d are executed by the upstream
# entrypoint on FIRST container boot only (i.e. when the data volume is
# empty). They run in alphabetical order; we prefix with 00- so any future
# seed data (10-, 20-, ...) lands after extension setup.
COPY initdb.d/ /docker-entrypoint-initdb.d/
