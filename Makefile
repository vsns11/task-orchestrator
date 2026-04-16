.PHONY: docker-up docker-down podman-up podman-down build test run run-podman clean status help

# ============================================================================
# Auto-detect container runtime: podman-compose if available, else docker compose
# Override with: make run RUNTIME=podman  or  make run RUNTIME=docker
# ============================================================================
RUNTIME ?= $(shell command -v podman >/dev/null 2>&1 && echo podman || echo docker)

ifeq ($(RUNTIME),podman)
  COMPOSE_CMD = podman compose -f podman-compose.yml
  COMPOSE_DOWN = podman compose -f podman-compose.yml down
else
  COMPOSE_CMD = docker compose
  COMPOSE_DOWN = docker compose down -v
endif

# ============================================================================
# Infrastructure
# ============================================================================

## Start PostgreSQL + Kafka using Docker
docker-up:
	docker compose up -d

## Stop Docker containers and remove volumes
docker-down:
	docker compose down -v

## Start PostgreSQL + Kafka using Podman
## Works with both:
##   - 'podman compose' (built into Podman Desktop, no Python needed)
##   - 'podman-compose' (pip install, needs Python)
podman-up:
	podman compose -f podman-compose.yml up -d
	@echo "Waiting for Postgres to be ready..."
	@sleep 10
	@echo "Infrastructure ready."

## Stop Podman containers
podman-down:
	podman compose -f podman-compose.yml down

## Start infrastructure (auto-detects Docker or Podman)
infra-up:
	$(COMPOSE_CMD) up -d

## Stop infrastructure (auto-detects Docker or Podman)
infra-down:
	$(COMPOSE_DOWN)

## Show container status
status:
	$(COMPOSE_CMD) ps

# ============================================================================
# Build & Test
# ============================================================================

## Clean build all modules
build:
	./gradlew clean build

## Run all tests (requires Docker/Podman for Testcontainers)
test:
	./gradlew test

## Run unit tests only (no containers needed)
unit-test:
	./gradlew :task-orchestrator-app:test

# ============================================================================
# Run Application
# ============================================================================

## Start app with Docker infrastructure
run: docker-up
	./gradlew :local-dev:bootRun

## Start app with Podman infrastructure
run-podman: podman-up
	./gradlew :local-dev:bootRun

## Start app with auto-detected infrastructure
start: infra-up
	./gradlew :local-dev:bootRun

# ============================================================================
# Cleanup
# ============================================================================

## Remove build artifacts
clean:
	./gradlew clean

## Full cleanup: stop containers + remove build artifacts
clean-all: infra-down clean

# ============================================================================
# Help
# ============================================================================

## Show available targets
help:
	@echo ""
	@echo "  Task Orchestrator - Available Commands"
	@echo "  ======================================="
	@echo ""
	@echo "  Infrastructure (pick one):"
	@echo "    make docker-up      Start with Docker"
	@echo "    make podman-up      Start with Podman"
	@echo "    make infra-up       Auto-detect Docker or Podman"
	@echo "    make status         Show running containers"
	@echo ""
	@echo "  Run Application:"
	@echo "    make run            Docker + app"
	@echo "    make run-podman     Podman + app"
	@echo "    make start          Auto-detect + app"
	@echo ""
	@echo "  Build & Test:"
	@echo "    make build          Clean build all modules"
	@echo "    make test           All tests (needs containers for Testcontainers)"
	@echo "    make unit-test      Unit tests only (no containers)"
	@echo ""
	@echo "  Cleanup:"
	@echo "    make docker-down    Stop Docker + remove volumes"
	@echo "    make podman-down    Stop Podman containers"
	@echo "    make clean          Remove build artifacts"
	@echo "    make clean-all      Stop containers + clean build"
	@echo ""
	@echo "  After 'make run':"
	@echo "    curl -X POST http://localhost:8080/demo/start"
	@echo "    curl http://localhost:8080/demo/barriers"
	@echo "    curl http://localhost:8080/demo/tasks"
	@echo "    DBeaver:  localhost:5433 / orchestrator / orchestrator"
	@echo ""
