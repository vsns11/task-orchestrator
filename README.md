# task-orchestrator

A Spring Boot service that orchestrates multi-batch, multi-action process flows
on top of TMF-701 over a single Kafka topic.

For the full payload contract, see `FLOW.md`.

---

## Prerequisites

- **Java 21+** — `java -version`
- **Docker OR Podman** — for PostgreSQL, Kafka, Kafka UI
- **Gradle** — wrapper included, no install needed

---

## Quick Start

### Option A: Docker

```bash
make docker-up          # start PostgreSQL + Kafka + Kafka UI
make run                # start the app (auto-runs docker-up first)
```

### Option B: Podman (Windows / Linux)

Install [Podman Desktop](https://podman-desktop.io/) — it includes `podman compose` built-in.
No Python or pip needed.

```bash
make podman-up          # start PostgreSQL + Kafka + Kafka UI
make run-podman         # start the app (auto-runs podman-up first)
```

### Option C: Auto-detect

```bash
make start              # detects Docker or Podman automatically
```

### Trigger a flow

```bash
curl -s -X POST http://localhost:8080/demo/start | jq
```

### Verify it worked (wait 5 seconds after starting)

```bash
curl -s http://localhost:8080/demo/barriers | jq '.[].status'
# Expected: "CLOSED", "CLOSED"

curl -s http://localhost:8080/demo/tasks | jq '.[].status'
# Expected: "COMPLETED", "COMPLETED", "COMPLETED"
```

### Stop everything

```bash
make docker-down        # Docker
make podman-down        # Podman
make clean-all          # stop containers + clean build artifacts
```

### See all available commands

```bash
make help
```

---

## Running Tests

```bash
make unit-test          # unit tests only — no containers needed
make test               # all tests including E2E (needs Docker/Podman for Testcontainers)
make build              # clean build + all tests
```

### Testcontainers with Podman

Testcontainers needs to know where the Podman socket is. Set these before running tests:

```bash
# Windows (PowerShell)
$env:DOCKER_HOST="npipe:////./pipe/podman-machine-default"
$env:TESTCONTAINERS_RYUK_DISABLED="true"

# Linux
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true

# macOS
export DOCKER_HOST=unix://$HOME/.local/share/containers/podman/machine/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

Then run:
```bash
make test
```

---

## Inspect Running System

| Tool | URL / Connection | What to look at |
|------|-----------------|-----------------|
| **Kafka UI** | http://localhost:9091 | Topics, messages, consumer groups, lag |
| **DBeaver** | `localhost:5433` / `orchestrator` / `orchestrator` | `batch_barrier` and `task_execution` tables |
| **Insomnia** | Import `insomnia-collection.json` | All HTTP endpoints |

---

## Project Structure

```
task-orchestrator/
├── task-orchestrator-model/        Shared DTOs and domain constants
│   └── dto/TaskCommand             Uniform Kafka message DTO
│   └── dto/tmf/                    Real TMF-701 native payload DTOs
│   └── domain/                     Enums: MessageType, Intent, TaskStatus, ExecutionMode
│
├── task-orchestrator-app/          Core orchestrator application
│   └── service/                    OrchestratorService, BarrierService
│   └── kafka/                      Listener, Publisher, TaskCommandFactory
│   └── config/                     KafkaConfig (two listener factories)
│
├── mock-tmf701/                    Mock TMF-701 processFlow API
├── mock-action-registry/           Mock action-code registry API
├── mock-pamconsumer/               Mock pamconsumer (reads notification.management)
├── mock-task-runner/               Mock task-runner (executes actions)
├── local-dev/                      Wires app + all mocks for local development
├── integration-tests/              E2E tests with Testcontainers
│
├── docker-compose.yml              Docker: PostgreSQL + Kafka + Kafka UI
├── podman-compose.yml              Podman: same services, Podman-compatible
└── Makefile                        All commands (make help)
```

---

## Key Files

| File | What it is |
|------|-----------|
| `FLOW.md` | Full end-to-end message flow with every JSON payload |
| `insomnia-collection.json` | Insomnia collection for all HTTP endpoints |
| `docker-compose.yml` | Docker infrastructure |
| `podman-compose.yml` | Podman infrastructure |
| `task-orchestrator-app/src/main/resources/dag/Auto_Remediation.yml` | DAG definition |
| `task-orchestrator-app/src/main/resources/application.yml` | App configuration |
