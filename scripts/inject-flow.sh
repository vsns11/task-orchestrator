#!/usr/bin/env bash
# ------------------------------------------------------------------
# inject-flow.sh — drive the orchestrator end-to-end by publishing
#                  sample payloads directly onto task.command.
#
# No mocks required. Only the orchestrator app + Kafka + Postgres.
#
# Usage:
#   ./inject-flow.sh                   # default: passwordPushV2 (ASYNC)
#   ./inject-flow.sh passwordResetV2   # ASYNC variant
#   ./inject-flow.sh miscellaneous     # SYNC
#   ./inject-flow.sh reset             # truncate orchestrator tables
#   ./inject-flow.sh verify <corrId>   # print barrier + task_execution
#
# Requires: jq, docker, and Apache Kafka's console producer on PATH.
#   - Linux / macOS : kafka-console-producer.sh
#   - Windows/Git Bash : kafka-console-producer.bat
#
# One-time setup (Windows/Git Bash example):
#   1. Download Kafka tarball from https://kafka.apache.org/downloads
#   2. Extract to e.g. C:\tools\kafka
#   3. Add bin/windows to PATH:
#        echo 'export PATH="/c/tools/kafka/bin/windows:$PATH"' >> ~/.bashrc
#        source ~/.bashrc
#   4. Verify:
#        kafka-topics.bat --bootstrap-server localhost:9092 --list
# ------------------------------------------------------------------
set -euo pipefail

BROKER="${BROKER:-localhost:9092}"
TOPIC="${TOPIC:-task.command}"
SAMPLES="${SAMPLES:-task-orchestrator-app/src/main/resources/sample_payloads}"
PG_USER="${PG_USER:-orchestrator}"
PG_DB="${PG_DB:-orchestrator}"

# Resolve the Postgres container name dynamically — no dependency on
# container_name: in compose files (which may auto-generate names like
# "myproj-postgres-1" or "myproj_postgres_1"). Priority:
#   1. User-set PG_CONTAINER (explicit override wins).
#   2. Running container whose IMAGE contains "postgres" (works for
#      postgres, postgres:16-alpine, timescale/postgres-*, etc.).
#   3. Running container whose NAME contains "postgres" (catches
#      custom images where the tag doesn't mention postgres).
# If none match, ensure_pg_container() fails with a diagnostic.
resolve_pg_container() {
  [[ -n "${PG_CONTAINER:-}" ]] && return 0

  PG_CONTAINER=$(docker ps --format '{{.Names}}\t{{.Image}}' 2>/dev/null \
    | awk -F'\t' 'tolower($2) ~ /postgres/ {print $1; exit}')
  [[ -n "$PG_CONTAINER" ]] && return 0

  PG_CONTAINER=$(docker ps --format '{{.Names}}' 2>/dev/null \
    | awk 'tolower($0) ~ /postgres/ {print; exit}')
}
resolve_pg_container

# Pause (seconds) between ASYNC steps so you can inspect the DB between
# state transitions (WAITING → signal → COMPLETED). Override with
# INSPECT_PAUSE=5 for a faster run, or INSPECT_PAUSE=0 to skip entirely.
INSPECT_PAUSE="${INSPECT_PAUSE:-30}"

# Producer binary. Auto-detects .bat (Windows) vs .sh (Linux/Mac).
# Override explicitly with PRODUCER=... if needed.
if [[ -z "${PRODUCER:-}" ]]; then
  if command -v kafka-console-producer.bat >/dev/null 2>&1; then
    PRODUCER="kafka-console-producer.bat"          # Windows (Apache tarball)
  elif command -v kafka-console-producer.sh >/dev/null 2>&1; then
    PRODUCER="kafka-console-producer.sh"           # Linux/Mac (Apache tarball)
  elif command -v kafka-console-producer    >/dev/null 2>&1; then
    PRODUCER="kafka-console-producer"              # Mac (Homebrew) / Confluent
  else
    PRODUCER="kafka-console-producer.bat"          # fall through to the missing-check below
  fi
fi

# Admin binary (kafka-topics) — same auto-detect pattern as the producer.
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

# Key separator. Pipe "|" is safe because jq -c never emits literal pipes
# in the compacted sample payloads. Override if your payloads include "|".
KEY_SEP="${KEY_SEP:-|}"

# Topics to wipe before each scenario run. Override CLEAR_TOPICS to
# change the set (space-separated); override AUTO_CLEAR=0 to skip.
CLEAR_TOPICS="${CLEAR_TOPICS:-task.command notification.management}"
AUTO_CLEAR="${AUTO_CLEAR:-1}"

# ---------- helpers ----------

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing on PATH: $1"; exit 1; }; }
need jq
need docker
need "$PRODUCER"
need "$KAFKA_TOPICS"

pub() {
  local file="$1"
  [[ -f "$file" ]] || { echo "file not found: $file"; exit 1; }
  local cid
  cid=$(jq -r '.correlationId' "$file")
  echo ">> $TOPIC  key=$cid  file=$(basename "$file")"

  # Compact JSON to a single line, prepend the key and separator, pipe
  # to Kafka's console producer. --property parse.key=true tells the
  # producer to split each line on KEY_SEP into key+value.
  printf '%s%s%s\n' "$cid" "$KEY_SEP" "$(jq -c . "$file")" \
    | MSYS_NO_PATHCONV=1 "$PRODUCER" \
        --bootstrap-server "$BROKER" \
        --topic "$TOPIC" \
        --property parse.key=true \
        --property "key.separator=$KEY_SEP" \
        2>&1 | grep -v -E '^(Warning|>|Option|\[)' || true   # drop ">" prompt + deprecation noise

  sleep 0.8   # give the orchestrator time to react before the next publish
}

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

reset_db() {
  ensure_pg_container
  echo "Truncating task_execution, batch_barrier ..."
  psql_cmd "TRUNCATE task_execution, batch_barrier;"
  echo "done."
}

# Wipe a single Kafka topic by deleting and recreating it with the same
# partition count (so the orchestrator's consumer-group assignment still
# makes sense). Works across Apache tarball and Homebrew/Confluent CLIs.
clear_topic() {
  local t="$1"
  local desc partitions replication
  desc=$(MSYS_NO_PATHCONV=1 "$KAFKA_TOPICS" --bootstrap-server "$BROKER" \
           --describe --topic "$t" 2>/dev/null || true)

  if [[ -z "$desc" ]]; then
    echo "topic '$t' does not exist — skipping delete, creating fresh."
    MSYS_NO_PATHCONV=1 "$KAFKA_TOPICS" --bootstrap-server "$BROKER" \
      --create --topic "$t" --partitions 3 --replication-factor 1 \
      2>&1 | grep -v -E '^(Warning|Option|\[)' || true
    return 0
  fi

  partitions=$(echo "$desc" | awk '/PartitionCount:/ {for (i=1;i<=NF;i++) if ($i=="PartitionCount:") print $(i+1)}' | head -1)
  replication=$(echo "$desc" | awk '/ReplicationFactor:/ {for (i=1;i<=NF;i++) if ($i=="ReplicationFactor:") print $(i+1)}' | head -1)
  partitions="${partitions:-3}"
  replication="${replication:-1}"

  echo "Clearing topic '$t' (partitions=$partitions, replication=$replication)..."
  MSYS_NO_PATHCONV=1 "$KAFKA_TOPICS" --bootstrap-server "$BROKER" \
    --delete --topic "$t" 2>&1 | grep -v -E '^(Warning|Option|\[)' || true

  # Brief wait for the delete to settle before recreate.
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

# Combined pre-run wipe: every CLEAR_TOPICS entry + the orchestrator tables.
# Called at the start of every run unless AUTO_CLEAR=0 or --no-reset.
clear_all() {
  echo "=== Pre-run cleanup (AUTO_CLEAR=1) ==="
  for t in $CLEAR_TOPICS; do
    clear_topic "$t"
  done
  reset_db
  echo "=== Cleanup done ==="
  echo ""
}

verify() {
  ensure_pg_container
  local cid="${1:-}"
  local where="WHERE process_flow_id = '${cid}'"
  [[ -z "$cid" ]] && where=""
  echo "--- batch_barrier ---"
  psql_cmd "SELECT process_flow_id, batch_index, task_total, task_completed, task_failed, status
            FROM batch_barrier ${where} ORDER BY process_flow_id, batch_index;"
  echo "--- task_execution ---"
  psql_cmd "SELECT process_flow_id, action_name, batch_index, status, downstream_id
            FROM task_execution ${where} ORDER BY process_flow_id, batch_index, action_name;"
}

# ---------- scenario runners ----------

run_async() {
  local dir="$SAMPLES/$1"
  [[ -d "$dir" ]] || { echo "scenario dir not found: $dir"; exit 1; }
  local cid
  cid=$(jq -r '.correlationId' "$dir/02_processflow_initiated.json")

  [[ "$AUTO_CLEAR" == "1" ]] && clear_all

  pub "$dir/02_processflow_initiated.json"   # START → barrier OPEN, flow.lifecycle INITIAL
  pub "$dir/05_task_event_waiting.json"      # WAITING → task_execution WAITING

  inspect_pause "$cid" "WAITING" "signal"

  pub "$dir/07_task_signal.json"             # SIGNAL → logged only

  inspect_pause "$cid" "after signal" "COMPLETED"

  pub "$dir/08_task_event_completed.json"    # COMPLETED → barrier CLOSED, flow.lifecycle COMPLETED
}

inspect_pause() {
  local cid="$1" phase="$2" next="$3"
  [[ "$INSPECT_PAUSE" == "0" ]] && return 0
  echo ""
  echo "------------------------------------------------------------------"
  echo " Inspection pause: ${INSPECT_PAUSE}s before publishing ${next}"
  echo " Current state (${phase}) for processFlowId=${cid}:"
  echo "------------------------------------------------------------------"
  verify "$cid"
  echo ""
  echo " >> Sleeping ${INSPECT_PAUSE}s — inspect the DB now, or press Ctrl+C to abort..."
  for i in $(seq "$INSPECT_PAUSE" -1 1); do
    printf "\r   %2ds remaining... " "$i"
    sleep 1
  done
  printf "\r   resuming.              \n"
}

run_sync() {
  local dir="$SAMPLES/$1"
  [[ -d "$dir" ]] || { echo "scenario dir not found: $dir"; exit 1; }
  [[ "$AUTO_CLEAR" == "1" ]] && clear_all
  pub "$dir/02_processflow_initiated.json"
  pub "$dir/05_task_event_completed.json"
}

# ---------- dispatcher ----------

# Parse optional flags that can appear before or after the action name.
#   -p | --pause <seconds>   override INSPECT_PAUSE
#   --no-reset               skip the pre-run topic/table wipe (AUTO_CLEAR=0)
#   -h | --help              usage
POSITIONAL=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -p|--pause)
      [[ -z "${2:-}" ]] && { echo "error: $1 requires a value (seconds)"; exit 1; }
      [[ "$2" =~ ^[0-9]+$ ]] || { echo "error: $1 value must be a non-negative integer, got: $2"; exit 1; }
      INSPECT_PAUSE="$2"
      shift 2
      ;;
    --no-reset)
      AUTO_CLEAR=0
      shift
      ;;
    -h|--help)
      POSITIONAL+=("help")
      shift
      ;;
    --)
      shift
      while [[ $# -gt 0 ]]; do POSITIONAL+=("$1"); shift; done
      ;;
    -*)
      echo "error: unknown flag: $1"
      exit 1
      ;;
    *)
      POSITIONAL+=("$1")
      shift
      ;;
  esac
done
set -- "${POSITIONAL[@]}"

ACTION="${1:-passwordPushV2}"

case "$ACTION" in
  reset)
    reset_db
    ;;
  clear)
    # Explicit manual wipe — force it on regardless of --no-reset.
    AUTO_CLEAR=1
    clear_all
    ;;
  verify)
    verify "${2:-}"
    ;;
  miscellaneous)
    run_sync miscellaneous
    cid=$(jq -r '.correlationId' "$SAMPLES/miscellaneous/02_processflow_initiated.json")
    sleep 1; verify "$cid"
    ;;
  passwordPushV2|passwordResetV2)
    run_async "$ACTION"
    cid=$(jq -r '.correlationId' "$SAMPLES/$ACTION/02_processflow_initiated.json")
    sleep 1; verify "$cid"
    ;;
  help)
    cat <<EOF
Usage:
  $0 [flags] <action> [args]

Actions:
  passwordPushV2            ASYNC flow (default if no action given)
  passwordResetV2           ASYNC flow
  miscellaneous             SYNC flow
  reset                     truncate orchestrator tables only
  clear                     wipe Kafka topics + truncate tables (manual pre-run)
  verify <correlationId>    inspect DB state for one flow

Flags (can appear before OR after the action):
  -p, --pause <seconds>     override INSPECT_PAUSE for this run (e.g. -p 10)
      --no-reset            skip the automatic pre-run wipe (AUTO_CLEAR=0)
  -h, --help                show this help

Pre-run cleanup:
  By default every scenario run first wipes the Kafka topics listed in
  CLEAR_TOPICS and truncates task_execution + batch_barrier so each run
  starts from a clean slate. Use --no-reset (or AUTO_CLEAR=0) to skip.

Examples:
  $0                                     # passwordPushV2 with 30s pauses, auto-clear
  $0 passwordPushV2 -p 10                # pause 10 seconds
  $0 -p 0 passwordResetV2                # no pauses (fastest)
  $0 --no-reset passwordPushV2           # keep prior topic+db state
  $0 miscellaneous                       # SYNC (pause ignored — no WAITING step)
  $0 reset                               # truncate tables, don't touch topics
  $0 clear                               # explicit: wipe topics AND tables
  $0 verify 11111111-aaaa-4bbb-8ccc-000000000001

Env overrides:
  BROKER         (current: $BROKER)
  TOPIC          (current: $TOPIC)
  SAMPLES        (current: $SAMPLES)
  PRODUCER       (current: $PRODUCER)
  KAFKA_TOPICS   (current: $KAFKA_TOPICS)
  KEY_SEP        (current: $KEY_SEP)
  PG_CONTAINER   (current: $PG_CONTAINER)
  INSPECT_PAUSE  (current: ${INSPECT_PAUSE}s — pause between ASYNC steps; 0 = no pause)
  AUTO_CLEAR     (current: $AUTO_CLEAR — 1=wipe topics+DB before each run, 0=skip)
  CLEAR_TOPICS   (current: $CLEAR_TOPICS)
EOF
    ;;
  *)
    cat <<EOF
Unknown action: $ACTION

Usage:
  $0 [-p <seconds>] [--no-reset] <action>

Actions:     passwordPushV2 | passwordResetV2 | miscellaneous | reset | clear | verify <cid>

Env overrides:
  BROKER         (current: $BROKER)
  TOPIC          (current: $TOPIC)
  SAMPLES        (current: $SAMPLES)
  PRODUCER       (current: $PRODUCER)
  KAFKA_TOPICS   (current: $KAFKA_TOPICS)
  KEY_SEP        (current: $KEY_SEP)
  PG_CONTAINER   (current: $PG_CONTAINER)
  INSPECT_PAUSE  (current: ${INSPECT_PAUSE}s — pause between ASYNC steps; 0 = no pause)
  AUTO_CLEAR     (current: $AUTO_CLEAR — 1=wipe topics+DB before each run, 0=skip)
  CLEAR_TOPICS   (current: $CLEAR_TOPICS)
EOF
    exit 1
    ;;
esac
