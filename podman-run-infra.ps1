# ============================================================================
#  podman-run-infra.ps1 — start / stop the dev stack without any compose tool
# ============================================================================
#
#  For Windows users who have Podman Desktop installed but NEITHER of:
#    - the Podman Desktop "Compose" extension
#    - a standalone docker-compose.exe on PATH
#    - Python / podman-compose
#
#  Boots exactly what podman-compose.yml does (postgres + kafka) via plain
#  `podman run` commands. No Python, no extensions, no wrappers.
#
#  Usage (from repo root, in PowerShell):
#    .\podman-run-infra.ps1 up         # start both containers
#    .\podman-run-infra.ps1 down       # stop and remove both containers
#    .\podman-run-infra.ps1 status     # show running containers + ports
#    .\podman-run-infra.ps1 logs       # tail both container logs
#
#  Connection info after `up`:
#    Postgres   localhost:5433   user=orchestrator  pass=orchestrator  db=orchestrator
#    Kafka      localhost:9092   PLAINTEXT, no auth
# ============================================================================

param(
    [Parameter(Position=0)]
    [ValidateSet("up","down","status","logs")]
    [string]$Action = "up"
)

$ErrorActionPreference = "Stop"

$PostgresName  = "orchestrator-postgres"
$PostgresImage = "docker.io/postgres:16-alpine"
$KafkaName     = "orchestrator-kafka"
$KafkaImage    = "docker.io/confluentinc/cp-kafka:7.6.0"
$VolumeName    = "orchestrator-pgdata"

function Check-Podman {
    try { podman version | Out-Null }
    catch {
        Write-Host "podman is not on PATH. Install Podman Desktop and ensure 'podman machine start' was run." -ForegroundColor Red
        exit 1
    }
}

function Up {
    Check-Podman

    Write-Host "Creating volume $VolumeName (idempotent)..."
    podman volume create $VolumeName | Out-Null

    # --- Postgres ----------------------------------------------------------
    $running = podman ps --filter "name=^$PostgresName$" --format "{{.Names}}"
    if ($running -eq $PostgresName) {
        Write-Host "Postgres already running — skipping."
    } else {
        # Remove stopped container with the same name, if any.
        podman rm -f $PostgresName 2>$null | Out-Null

        Write-Host "Starting Postgres ($PostgresImage) on localhost:5433..."
        podman run -d `
            --name $PostgresName `
            -p 5433:5432 `
            -e POSTGRES_DB=orchestrator `
            -e POSTGRES_USER=orchestrator `
            -e POSTGRES_PASSWORD=orchestrator `
            -v "${VolumeName}:/var/lib/postgresql/data" `
            --health-cmd="pg_isready -U orchestrator" `
            --health-interval=5s `
            --health-timeout=3s `
            --health-retries=5 `
            $PostgresImage | Out-Null
    }

    # --- Kafka -------------------------------------------------------------
    $running = podman ps --filter "name=^$KafkaName$" --format "{{.Names}}"
    if ($running -eq $KafkaName) {
        Write-Host "Kafka already running — skipping."
    } else {
        podman rm -f $KafkaName 2>$null | Out-Null

        Write-Host "Starting Kafka ($KafkaImage) on localhost:9092..."
        podman run -d `
            --name $KafkaName `
            -p 9092:9092 `
            -e KAFKA_NODE_ID=1 `
            -e KAFKA_PROCESS_ROLES=broker,controller `
            -e "KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093" `
            -e "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092" `
            -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER `
            -e "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT" `
            -e "KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093" `
            -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT `
            -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 `
            -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 `
            -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 `
            -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk `
            $KafkaImage | Out-Null
    }

    Write-Host ""
    Write-Host "Infrastructure started:" -ForegroundColor Green
    podman ps --filter "name=$PostgresName" --filter "name=$KafkaName"
}

function Down {
    Check-Podman
    Write-Host "Stopping and removing containers..."
    podman rm -f $PostgresName 2>$null | Out-Null
    podman rm -f $KafkaName    2>$null | Out-Null
    Write-Host "Done. Volume '$VolumeName' preserved (delete with 'podman volume rm $VolumeName')." -ForegroundColor Green
}

function Status {
    Check-Podman
    podman ps --filter "name=$PostgresName" --filter "name=$KafkaName"
}

function Logs {
    Check-Podman
    Write-Host "---- Postgres ----" -ForegroundColor Cyan
    podman logs --tail 40 $PostgresName
    Write-Host ""
    Write-Host "---- Kafka ----" -ForegroundColor Cyan
    podman logs --tail 40 $KafkaName
}

switch ($Action) {
    "up"     { Up }
    "down"   { Down }
    "status" { Status }
    "logs"   { Logs }
}
