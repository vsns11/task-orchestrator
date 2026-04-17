#!/usr/bin/env bash
# ------------------------------------------------------------------
# pre-clear.sh — wipe Kafka topics and orchestrator DB tables BEFORE
#                starting the Spring app.
#
# WHY A SEPARATE SCRIPT?
#   Deleting a Kafka topic while the Spring consumer is subscribed to
#   it causes the consumer to error out (UnknownTopicOrPartitionException,
#   rebalance loops, logger floods). The safe order is:
#
#     1. STOP the orchestrator app (if running)
#     2. Run this script to clear topics + DB
#     3. START the orchestrator app
#     4. Run ./scripts/inject-flow.sh to ingest events
#
# Usage:
#   ./scripts/pre-clear.sh              # wipe default topics + DB
#   ./scripts/pre-clear.sh --topics-only
#   ./scripts/pre-clear.sh --db-only
#
# Requires: docker, and Apache Kafka's kafka-topics on PATH.
#   - Linux / macOS : kafka-topics (Homebrew) / kafka-topics.sh (tarball)
#   - Windows/Git Bash : kafka-topics.bat
# ------------------------------------------------------------------
set -euo pipefail

BROKER="${BROKER:-localhost:9092}"
PG_USER="${PG_USER:-orchestrator}"
PG_DB="${PG_DB:-orchestrator}"

# Topics to wipe. Override CLEAR_TOPICS to change the set (space-separated).
CLEAR_TOPICS="${CLEAR_TOPICS:-task.command notification.management}"

# Default partition count / replication factor for topics that don't yet exist
# (or whose previous describe fails). Tune if your broker config differs.
DEFAULT_PARTITIONS="${DEFAULT_PARTITIONS:-3}"
DEFAULT_REPLICATION="${DEFAULT_REPLICATION:-1}"

# --- Postgres container auto-detect (same logic as inject-flow.sh) ---
resolve_pg_container() {
  [[ -n "${PG_CONTAINER:-}" ]] && return 0

  PG_CONTAINER=$(docker ps --format '{{.Names}}\t{{.Image}}' 2>/dev/null \
    | awk -F'\t' 'tolower($2) ~ /postgres/ {print $1; exit}')
  [[ -n "$PG_CONTAINER" ]] && return 0

  PG_CONTAINER=$(docker ps --format '{{.Names}}' 2>/dev/null \
    | awk 'tolower($0) ~ /postgres/ {print; exit}')
}
resolve_pg_container

# --- kafka-topics binary auto-detect (same logic as inject-flow.sh) ---
if [[ -z "${KAFKA_TOPICS:-}" ]]; then
  if command -v kafka-topics.bat >/dev/null 2>&1; then
    KAFKA_TOPICS="kafka-topics.bat"
  elif command -v kafka-topics.sh >/dev/null 2>&1; then
    KAFKA_TOPICS="kafka-topics.sh"
  elif command -v kafka-topics    >/dev/null 2>&1; then
    KAFKA_TOPICS="kafka-topics"
  else
    KAFKA_TOPICS="kafka-topics.bat"
  fi
fi

# ---------- helpers ----------

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing on PATH: $1"; exit 1; }; }

ensure_pg_container() {
  if [[ -z "${PG_CONTAINER:-}" ]] || ! docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
    echo "error: could not locate a running Postgres container." >&2
    [[ -n "${PG_CONTAINER:-}" ]] && echo "       auto-detected / requested name: '$PG_CONTAINER'" >&2
    echo "" >&2
    echo "Running containers whose name or image mentions 'postgres':" >&2
    docker ps --format '  {{.Names}}  (image={{.Image}}, ports={{.Ports}})' \
      | grep -i postgres >&2 || echo "  (none — is Postgres actually up? try: docker ps)" >&2
    echo "" >&2
    echo "Fix: set PG_CONTAINER to the correct container name, e.g." >&2
    echo "  PG_CONTAINER=<name-from-above> $0 $*" >&2
    exit 1
  fi
}

psql_cmd() {
  docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c "$1"
}

truncate_tables() {
  ensure_pg_container
  echo "Truncating task_execution, batch_barrier ..."
  psql_cmd "TRUNCATE task_execution, batch_barrier;"
  echo "done."
}

clear_topic() {
  local t="$1"
  local desc partitions replication
  desc=$(MSYS_NO_PATHCONV=1 "$KAFKA_TOPICS" --bootstrap-server "$BROKER" \
           --describe --topic "$t" 2>/dev/null || true)

  if [[ -z "$desc" ]]; then
    echo "topic '$t' does not exist — creating fresh (partitions=$DEFAULT_PARTITIONS, rf=$DEFAULT_REPLICATION)."
    MSYS_NO_PATHCONV=1 "$KAFKA_TOPICS" --bootstrap-server "$BROKER" \
      --create --topic "$t" \
      --partitions "$DEFAULT_PARTITIONS" --replication-factor "$DEFAULT_REPLICATION" \
      2>&1 | grep -v -E '^(Warning|Option|\[)' || true
    return 0
  fi

  partitions=$(echo "$desc" | awk '/PartitionCount:/ {for (i=1;i<=NF;i++) if ($i=="PartitionCount:") print $(i+1)}' | head -1)
  replication=$(echo "$desc" | awk '/ReplicationFactor:/ {for (i=1;i<=NF;i++) if ($i=="ReplicationFactor:") print $(i+1)}' | head -1)
  partitions="${partitions:-$DEFAULT_PARTITIONS}"
  replication="${replication:-$DEFAULT_REPLICATION}"

  echo "Clearing topic '$t' (partitions=$partitions, replication=$replication)..."
  MSYS_NO_PATHCONV=1 "$KAFKA_TOPICS" --bootstrap-server "$BROKER" \
    --delete --topic "$t" 2>&1 | grep -v -E '^(Warning|Option|\[)' || true

  # Wait for the delete to settle before recreate.
  local tries=0
  while (( tries < 10 )); do
    if ! MSYS_NO_PATHCONV=1 "$KAFKA_TOPICS" --bootstrap-server "$BROKER" \
           --list 2>/dev/null | grep -qx "$t"; then
      break
    fi
    sleep 0.5
    tries=$((tries + 1))
  done

  MSYS_NO_PATHCONV=1 "$KAFKA_TOPICS" --bootstrap-server "$BROKER" \
    --create --topic "$t" --partitions "$partitions" --replication-factor "$replication" \
    2>&1 | grep -v -E '^(Warning|Option|\[)' || true
}

clear_topics() {
  for t in $CLEAR_TOPICS; do
    clear_topic "$t"
  done
}

warn_if_app_running() {
  # Best-effort check: is anything listening on 8080 locally?
  # Not fatal — just a reminder. Suppress errors on minimal Git Bash installs.
  if command -v curl >/dev/null 2>&1 \
     && curl -s -o /dev/null -w '%{http_code}' --max-time 1 http://localhost:8080/actuator/health 2>/dev/null | grep -qE '^(200|401|403|404)$'; then
    echo ""
    echo "================================================================"
    echo " WARNING: an app appears to be running on http://localhost:8080"
    echo " Deleting topics while the Spring consumer is subscribed can"
    echo " break the consumer. Stop the app first, then re-run this."
    echo " Press Ctrl+C within 5s to abort, or wait to continue..."
    echo "================================================================"
    sleep 5
  fi
}

# ---------- dispatcher ----------

MODE="all"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --topics-only) MODE="topics"; shift ;;
    --db-only)     MODE="db";     shift ;;
    -h|--help)
      cat <<EOF
Usage:
  $0                 # wipe Kafka topics AND orchestrator DB tables (default)
  $0 --topics-only   # only wipe Kafka topics
  $0 --db-only       # only truncate DB tables

Env overrides:
  BROKER               (current: $BROKER)
  CLEAR_TOPICS         (current: $CLEAR_TOPICS)
  DEFAULT_PARTITIONS   (current: $DEFAULT_PARTITIONS)
  DEFAULT_REPLICATION  (current: $DEFAULT_REPLICATION)
  KAFKA_TOPICS         (current: $KAFKA_TOPICS)
  PG_CONTAINER         (current: ${PG_CONTAINER:-<not found>})
  PG_USER              (current: $PG_USER)
  PG_DB                (current: $PG_DB)

Workflow:
  1. Stop the orchestrator app (Ctrl+C in the terminal running it)
  2. $0
  3. Start the orchestrator app (make run)
  4. ./scripts/inject-flow.sh
EOF
      exit 0
      ;;
    *) echo "unknown flag: $1"; exit 1 ;;
  esac
done

echo "=== Pre-clear starting ==="
case "$MODE" in
  all)
    need docker
    need "$KAFKA_TOPICS"
    warn_if_app_running
    clear_topics
    truncate_tables
    ;;
  topics)
    need "$KAFKA_TOPICS"
    warn_if_app_running
    clear_topics
    ;;
  db)
    need docker
    truncate_tables
    ;;
esac
echo "=== Pre-clear done ==="
echo ""
echo "Next steps:"
echo "  1. Start the orchestrator app   (e.g. 'make run')"
echo "  2. Wait for it to be healthy    (curl -s localhost:8080/actuator/health)"
echo "  3. ./scripts/inject-flow.sh     # run a scenario"
