# Task Orchestrator

A Spring Boot service that orchestrates multi-batch, multi-action process flows on top of TMF-701. It consumes flow-initiation events from `pamconsumer`, drives `task-runner` instances batch-by-batch, tracks completion with a database-backed barrier, and PATCHes TMF-701 as work progresses.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Component Responsibilities](#2-component-responsibilities)
3. [Topic Design](#3-topic-design)
4. [Uniform Message Envelope](#4-uniform-message-envelope)
5. [Field Reference](#5-field-reference)
6. [Per-Message-Name Field Matrix](#6-per-message-name-field-matrix)
7. [Database Schema](#7-database-schema)
8. [Barrier Lifecycle & Lazy Seeding](#8-barrier-lifecycle--lazy-seeding)
9. [Concurrency, Ordering, Idempotency](#9-concurrency-ordering-idempotency)
10. [End-to-End Example: SYNC Action](#10-end-to-end-example-sync-action)
11. [End-to-End Example: ASYNC Action](#11-end-to-end-example-async-action)
12. [Combined E2E Walkthrough](#12-combined-e2e-walkthrough)
13. [DAG YAML Format](#13-dag-yaml-format)
14. [Action Registry](#14-action-registry)
15. [TMF-701 PATCH Contract](#15-tmf-701-patch-contract)
16. [Orchestrator Dispatch Table](#16-orchestrator-dispatch-table)
17. [Operational Notes](#17-operational-notes)

---

## 1. Architecture Overview

```
                                ┌───────────────┐
                                │     TTD       │
                                └──────┬────────┘
                                       │ HTTP POST /processFlow
                                       ▼
                                ┌───────────────┐
                                │   TMF-701     │
                                │  ProcessFlow  │
                                └──────┬────────┘
                                       │ publishes processFlow.created
                                       ▼
                          ┌──────────────────────────┐
                          │ topic: notification-mgmt │
                          └──────────┬───────────────┘
                                     │ also receives external async responses
                                     ▼
                              ┌─────────────┐
                              │ pamconsumer │
                              └──────┬──────┘
                                     │ publishes  processFlow.initiated
                                     │            task.signal
                                     ▼
        ┌────────────────────────────────────────────────────────────┐
        │                  topic: task.command                       │
        │   carries: processFlow.initiated, task.execute,            │
        │            task.event, task.signal                         │
        └──────────┬─────────────────────────────────────┬───────────┘
                   │                                     │
                   ▼                                     ▼
          ┌────────────────────┐                ┌──────────────────┐
          │  task-orchestrator │                │   task-runner    │
          │  (this service)    │                │                  │
          │  - DAG resolver    │                │  - 701 create    │
          │  - barrier service │                │  - downstream    │
          │  - publisher       │                │  - 701 patch     │
          └─────────┬──────────┘                └────────┬─────────┘
                    │ PATCH parent processFlow            │ PATCH taskFlow
                    └─────────────────┬───────────────────┘
                                      ▼
                                ┌───────────────┐
                                │   TMF-701     │
                                └───────────────┘
```

---

## 2. Component Responsibilities

| Component | Reads | Writes | Owns |
|---|---|---|---|
| **TMF-701** | HTTP from TTD | `processFlow.created` event to `notification-management` | Source of truth for processFlow / taskFlow state |
| **pamconsumer** | `notification-management` (TMF events + external async responses) | `processFlow.initiated`, `task.signal` to `task.command` | Filtering, signal correlation via `waiting_task` table |
| **task-orchestrator** | `task.command` (all messages except its own commands) | `task.execute` to `task.command`, PATCH parent processFlow on TMF-701 | DAG resolution, barrier lifecycle, batch promotion |
| **task-runner** | `task.command` (`task.execute` and `task.signal`) | `task.event` to `task.command`, POST/PATCH taskFlow on TMF-701 | Action execution pipeline |

---

## 3. Topic Design

There is **one topic for the orchestration boundary**: `task.command`.

| Topic | Producers | Consumers |
|---|---|---|
| `notification-management` (external) | TMF-701, external async systems | pamconsumer |
| **`task.command`** (this system) | pamconsumer, task-orchestrator, task-runner | task-orchestrator, task-runner |

### Why one topic?

- All orchestration messages share the same envelope (Section 4).
- The orchestrator is the single state owner — it must see everything in order.
- Single partition key (`correlationId`) gives total ordering per processFlow (Section 9).
- Producers tag messages with `source` so each consumer can ignore its own messages.

### Partition strategy

- **Partition key:** `correlationId` (= `processFlow.id`).
- **Effect:** all messages for the same processFlow land on the same partition → consumed sequentially → no intra-flow races.
- **Recommended partition count:** 12 to start, scale out later.

---

## 4. Uniform Message Envelope

Every message on `task.command`, regardless of producer or purpose, conforms to this single JSON shape. Fields not relevant to a given `messageName` are omitted (`null`).

```json
{
  "eventId":       "01HRZX...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime":     "2026-04-12T10:00:00.123Z",

  "messageType":   "EVENT | COMMAND | SIGNAL",
  "messageName":   "processFlow.initiated | task.execute | task.event | task.signal",
  "source":        "pamconsumer | task-orchestrator | task-runner",

  "dagKey":        "Auto_Remediation",
  "intent":        "EXECUTE | CANCEL | RETRY",
  "status":        "INITIAL | IN_PROGRESS | WAITING | COMPLETED | FAILED | CANCELLED",

  "batch":   { "index": 0, "total": 2 },
  "action":  { "ref": "a1", "actionCode": "...", "actionName": "...", "dcxActionCode": "..." },
  "task":    { "id": "tf-9a1b", "href": "..." },

  "execution": {
    "mode":        "SYNC | ASYNC",
    "attempt":     1,
    "maxAttempts": 3,
    "timeoutMs":   30000,
    "startedAt":   "2026-04-12T10:00:03.500Z",
    "finishedAt":  "2026-04-12T10:05:22.100Z",
    "durationMs":  318600
  },

  "downstream":     { "id": "...", "href": "..." },
  "awaitingSignal": { "businessTxnId": "..." },
  "trigger":        { "externalEventId": "...", "externalType": "...", "reportingSystem": "..." },

  "inputs":  { "processFlow": { /* ... */ }, "downstream": { /* ... */ } },
  "result":  {
    "name": "...", "code": "...", "id": "...", "type": "TaskFlow",
    "taskResult":       { /* raw downstream response — JsonNode / Map / any object */ },
    "taskFlowResponse": { /* typed TMF-701 TaskFlow with id, href, state, characteristic[] */ },
    "taskStatusCode":   "COMPLETED | WAITING | FAILED"
  },
  "error":   { "code": "...", "message": "...", "retryable": false, "details": { /* ... */ } }
}
```

### Kafka headers (mirror selected envelope fields for routing)

```
key:            <correlationId>          (string, drives partition assignment)
eventId:        <eventId>
messageType:    EVENT | COMMAND | SIGNAL
messageName:    <messageName>
source:         <producer name>
schemaVersion:  1.0
```

---

## 5. Field Reference

### Envelope (always present)

| Field | Type | Notes |
|---|---|---|
| `eventId` | ULID string | Unique per message. Used for idempotency. |
| `correlationId` | UUID string | Equal to `processFlow.id`. Drives partition assignment. |
| `schemaVersion` | string | Currently `"1.0"`. |
| `eventTime` | RFC 3339 timestamp | UTC. |
| `messageType` | enum | `EVENT`, `COMMAND`, or `SIGNAL`. |
| `messageName` | string | Logical message name; primary dispatch key. |
| `source` | string | Producing component name. |

### Domain fields (conditional)

| Field | Type | When populated |
|---|---|---|
| `dagKey` | string | On `processFlow.initiated`; orchestrator looks up DAG by this key. |
| `intent` | enum | On `task.execute`. |
| `status` | enum | On `task.event` only. |
| `batch.index` | int | On `task.execute`, `task.event`, `task.signal`. |
| `batch.total` | int | On `task.execute` only. |
| `action.ref` | string | DAG-local action key (e.g. `"a1"`). On `task.execute`, `task.event`, `task.signal`. |
| `action.actionCode` | string | TMF action code (e.g. `"VOICE_SERVICE_DIAGNOSTIC"`). |
| `action.actionName` | string | Human-readable action name. |
| `action.dcxActionCode` | string | DCX-side mapping code. |
| `task.id` | string | TMF taskFlow id. **Only present after task-runner creates the taskFlow** (i.e. on `task.event` and `task.signal`). Never on `task.execute`. |
| `task.href` | URL | TMF taskFlow href, same conditions as `task.id`. |
| `execution.mode` | enum | `SYNC` or `ASYNC`. |
| `execution.attempt` | int | 1-indexed retry counter. |
| `execution.maxAttempts` | int | From action config. |
| `execution.timeoutMs` | long | Per-attempt timeout. |
| `execution.startedAt` / `finishedAt` / `durationMs` | timestamps / long | Populated as the runner executes. |
| `downstream.id` | string | Identifier returned by the downstream system (e.g. `"VOICE_transactionId_..."`). |
| `downstream.href` | URL | URL the runner uses to fetch the downstream result. |
| `awaitingSignal.businessTxnId` | string | Key pamconsumer uses to correlate the eventual external signal back to this task. Same value as `downstream.id` for async actions. |
| `trigger` | object | On `task.signal`: which external event triggered this signal. |
| `inputs.processFlow` | object | Full TMF-701 processFlow object passed forward to the runner. |
| `inputs.downstream` | object | On `task.signal`: tells the runner which downstream URL to fetch. |
| `result` | `ActionResponse` | Present on `task.event` with `status=COMPLETED` (and `WAITING` for ASYNC). Carries two payloads: `taskResult` (raw downstream response, loosely-typed `Object` — `JsonNode` / `Map` / domain DTO) and `taskFlowResponse` (typed TMF-701 `TaskFlow`: `id`, `href`, `state`, `characteristic[]`). The orchestrator reads the taskFlow `href` from `taskFlowResponse` to PATCH the parent processFlow. On `COMPLETED` it is **also validated**: `result.taskStatusCode` must be `COMPLETED` and, if present, `taskFlowResponse.characteristic[name=status].value` must equal `pass` (case-insensitive) — otherwise the event is rejected and the flow is failed. |
| `error` | object | Present on `task.event` with `status=FAILED`. |

---

## 6. Per-Message-Name Field Matrix

✅ = required, ⚪ = optional / conditional, ❌ = must be omitted.

| Field | `processFlow.initiated` | `task.execute` | `task.event` | `task.signal` |
|---|:-:|:-:|:-:|:-:|
| **envelope** (`eventId`, `correlationId`, …) | ✅ | ✅ | ✅ | ✅ |
| `messageType` | EVENT | COMMAND | EVENT | SIGNAL |
| `source` | pamconsumer | task-orchestrator | task-runner | pamconsumer |
| `dagKey` | ✅ | ⚪ | ❌ | ❌ |
| `intent` | ❌ | ✅ | ❌ | ❌ |
| `status` | ❌ | ❌ | ✅ | ❌ |
| `batch` | ❌ | ✅ (index+total) | ✅ (index) | ✅ (index) |
| `action` | ❌ | ✅ (full triplet) | ✅ (full triplet) | ✅ (full triplet) |
| `task` | ❌ | ❌ | ✅ | ✅ |
| `execution` | ❌ | ✅ (mode, attempt, max, timeout) | ✅ (mode, attempt, timestamps) | ❌ |
| `downstream` | ❌ | ❌ | ⚪ (when applicable) | ❌ |
| `awaitingSignal` | ❌ | ❌ | ⚪ (status=WAITING) | ❌ |
| `trigger` | ❌ | ❌ | ❌ | ✅ |
| `inputs.processFlow` | ✅ | ✅ | ❌ | ❌ |
| `inputs.downstream` | ❌ | ❌ | ❌ | ✅ |
| `result` | ❌ | ❌ | ⚪ (status=COMPLETED) | ❌ |
| `error` | ❌ | ❌ | ⚪ (status=FAILED) | ❌ |

---

## 7. Database Schema

### `batch_barrier`

Holds at most one row per `(correlationId, batch_index)` — never future PENDING rows. Lazily seeded one batch at a time.

| Column | Type | Notes |
|---|---|---|
| `correlation_id` | UUID | PK |
| `flow_id` | VARCHAR(64) | PK; equals correlationId in current design |
| `batch_index` | SMALLINT | PK |
| `dag_key` | VARCHAR(64) | |
| `task_total` | INT | |
| `task_completed` | INT default 0 | |
| `task_failed` | INT default 0 | |
| `status` | VARCHAR(16) | `OPEN`, `CLOSED`, `FAILED` |
| `opened_at` | TIMESTAMPTZ | |
| `closed_at` | TIMESTAMPTZ nullable | |
| `created_at` / `updated_at` | TIMESTAMPTZ | |
| `version` | BIGINT | JPA `@Version` for optimistic locking |

`pending = task_total - task_completed - task_failed` (computed in code, not stored).

### `task_execution`

Per-task audit trail.

| Column | Type | Notes |
|---|---|---|
| `correlation_id` | UUID | PK |
| `flow_id` | VARCHAR(64) | PK |
| `task_id` | VARCHAR(64) | PK |
| `attempt` | SMALLINT | PK |
| `action_ref` | VARCHAR(32) | |
| `batch_index` | SMALLINT | |
| `status` | VARCHAR(16) | last seen status |
| `downstream_id` | VARCHAR(128) nullable | |
| `downstream_href` | TEXT nullable | |
| `started_at` / `finished_at` / `duration_ms` | timestamps / int | |
| `result_json` / `error_json` | JSONB nullable | |
| `created_at` / `updated_at` / `version` | | |

### `processed_events`

Idempotency table.

| Column | Type | Notes |
|---|---|---|
| `event_id` | VARCHAR(40) | PK |
| `processed_at` | TIMESTAMPTZ | default `now()` |

### `waiting_task` (owned by pamconsumer / runner — not orchestrator)

Lookup table populated when runner emits a WAITING event, used by pamconsumer to correlate incoming external async responses to the right task.

| Column | Type | Notes |
|---|---|---|
| `business_txn_id` | VARCHAR(128) | PK |
| `correlation_id` | UUID | |
| `task_id` | VARCHAR(64) | |
| `task_href` | TEXT | |
| `action_ref` | VARCHAR(32) | |
| `registered_at` | TIMESTAMPTZ | |
| `signaled_at` | TIMESTAMPTZ nullable | |

---

## 8. Barrier Lifecycle & Lazy Seeding

- On `processFlow.initiated`, orchestrator creates **one** barrier row for batch 0 with `status=OPEN`.
- On each `task.event` with `status=COMPLETED`, increment `task_completed`. If `pending == 0`:
  1. Set `status=CLOSED`, `closed_at=now()`.
  2. Look up the next batch in the DAG by `batch_index + 1`.
  3. If a next batch exists, **insert** a new barrier row for it with `status=OPEN`, then publish `task.execute` for each of its actions.
  4. If no next batch, PATCH parent processFlow `state=completed` on TMF-701.
- On `task.event` with `status=FAILED` (and `error.retryable=false`): set `status=FAILED`, abort flow, PATCH processFlow `state=failed`.
- `WAITING` does **not** mutate the barrier.

Lifecycle: `OPEN → CLOSED` (success) or `OPEN → FAILED` (terminal failure).

---

## 9. Concurrency, Ordering, Idempotency

### Layered protection

| Threat | Protection |
|---|---|
| Two events for same flow processed in parallel | **Partition key = correlationId** → all events for one flow on one partition → consumed serially |
| Brief overlap during consumer rebalance | **JPA `@Version` optimistic lock** + Spring Retry (3 attempts, 50 ms backoff) |
| Kafka at-least-once duplicate delivery | **`processed_events` table**: insert `event_id` first; PK violation = duplicate, skip |
| Listener crash mid-processing | Manual ack only after DB transaction commits; idempotent producer (`acks=all`, `enable.idempotence=true`) |
| Out-of-order events for same flow | Single partition guarantees Kafka-level ordering |

### Required Kafka client config (orchestrator)

```yaml
spring:
  kafka:
    producer:
      acks: all
      properties:
        enable.idempotence: true
    consumer:
      enable-auto-commit: false
      isolation-level: read_committed
    listener:
      ack-mode: MANUAL
      concurrency: 6   # ≤ partition count
```

### Handler skeleton

```java
@Transactional
@Retryable(retryFor = OptimisticLockingFailureException.class,
           maxAttempts = 3, backoff = @Backoff(50))
public void handle(TaskCommandEnvelope env) {
    if (!idempotency.markProcessed(env.eventId)) return;   // duplicate
    switch (env.messageName) {
        case "processFlow.initiated" -> flowService.initiate(env);
        case "task.event"            -> barrierService.applyTaskEvent(env);
        case "task.signal"           -> log.info("signal observed: {}", env.eventId);
        case "task.execute"          -> { /* own message — ignore */ }
    }
}
```

---

## 10. End-to-End Example: SYNC Action

A SYNC action's runner pipeline:

1. `POST /processFlow/{id}/taskFlow` → mints `task.id`, `task.href`.
2. Calls the downstream system (blocking).
3. `PATCH /processFlow/{id}/taskFlow/{taskId}` with the result.
4. Publishes a single `task.event` with `status=COMPLETED` and the full `result` payload.

The runner emits **one** event for the entire SYNC lifecycle. The orchestrator sees `COMPLETED` and increments the barrier directly.

### Sample messages for a SYNC action `a2` (INTERNET_CHECK)

#### `task.execute` (orchestrator → task.command)

```json
{
  "eventId": "01HRZX6N...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-12T10:00:02.000Z",
  "messageType": "COMMAND",
  "messageName": "task.execute",
  "source": "task-orchestrator",
  "intent": "EXECUTE",
  "dagKey": "Auto_Remediation",
  "action": {
    "ref": "a2",
    "actionCode": "INTERNET_CHECK",
    "actionName": "runInternetCheck",
    "dcxActionCode": "DCX-INT-04"
  },
  "batch":     { "index": 0, "total": 2 },
  "execution": { "mode": "SYNC", "attempt": 1, "maxAttempts": 3, "timeoutMs": 15000 },
  "inputs":    { "processFlow": { /* full processFlow object */ } }
}
```

#### `task.event` (task-runner → task.command)

```json
{
  "eventId": "01HRZX7P...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-12T10:00:05.000Z",
  "messageType": "EVENT",
  "messageName": "task.event",
  "source": "task-runner",
  "task": {
    "id": "tf-7c3d",
    "href": "https://tmf-process-flow.../processFlow/bbf5e84d-.../taskFlow/tf-7c3d"
  },
  "action": {
    "ref": "a2",
    "actionCode": "INTERNET_CHECK",
    "actionName": "runInternetCheck",
    "dcxActionCode": "DCX-INT-04"
  },
  "batch":  { "index": 0 },
  "status": "COMPLETED",
  "execution": {
    "mode": "SYNC",
    "attempt": 1,
    "startedAt":  "2026-04-12T10:00:03.200Z",
    "finishedAt": "2026-04-12T10:00:05.000Z",
    "durationMs": 1800
  },
  "downstream": {
    "id":   "INT-CHK-9981",
    "href": "https://internet-check.../checks/INT-CHK-9981"
  },
  "result": {
    "name": "runInternetCheck",
    "code": "INTERNET_CHECK",
    "id":   "tf-7c3d",
    "type": "TaskFlow",
    "taskResult": {
      "diagnosticResult": "OK",
      "metrics": { "latencyMs": 42, "packetLoss": 0.0 }
    },
    "taskFlowResponse": {
      "id":    "tf-7c3d",
      "href":  "https://tmf-process-flow.../processFlow/bbf5e84d-.../taskFlow/tf-7c3d",
      "@type": "TaskFlow",
      "state": "completed",
      "characteristic": [
        { "name": "status",           "value": "pass" },
        { "name": "diagnosticResult", "value": "OK"   }
      ]
    },
    "taskStatusCode": "COMPLETED"
  },
  "error": null
}
```

### Orchestrator effect

- `task_execution` row upserted: a2 COMPLETED.
- Barrier batch 0: `task_completed += 1`.
- PATCH parent processFlow to register the new taskFlow ref (Section 15).
- If batch is now closed, seed the next batch (Section 8).

---

## 11. End-to-End Example: ASYNC Action

An ASYNC action requires **three messages** across **two runner invocations**, separated by an external signal:

1. **First invocation** (triggered by `task.execute`):
   - `POST /processFlow/{id}/taskFlow` → mints `task.id`, `task.href`.
   - Dispatches downstream call (non-blocking).
   - Publishes `task.event` with `status=WAITING`, including `downstream.id` + `downstream.href`.
   - Writes a `waiting_task` row keyed by `downstream.id` so pamconsumer can correlate later.
   - Stops.
2. **External system** completes the work and publishes a final response on `notification-management`.
3. **pamconsumer** receives that external event, looks up `waiting_task` by the business transaction id, publishes a `task.signal` on `task.command`.
4. **Second invocation** (triggered by `task.signal`):
   - GET `inputs.downstream.href` → fetches full result payload.
   - `PATCH /processFlow/{id}/taskFlow/{taskId}` with that result.
   - Publishes `task.event` with `status=COMPLETED` and the full `result`.

### Sample messages for an ASYNC action `a1` (VOICE_SERVICE_DIAGNOSTIC)

#### `task.execute` (orchestrator → task.command)

```json
{
  "eventId": "01HRZX6M...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-12T10:00:02.000Z",
  "messageType": "COMMAND",
  "messageName": "task.execute",
  "source": "task-orchestrator",
  "intent": "EXECUTE",
  "dagKey": "Auto_Remediation",
  "action": {
    "ref": "a1",
    "actionCode": "VOICE_SERVICE_DIAGNOSTIC",
    "actionName": "runVoiceDiagnostic",
    "dcxActionCode": "DCX-VSD-01"
  },
  "batch":     { "index": 0, "total": 2 },
  "execution": { "mode": "ASYNC", "attempt": 1, "maxAttempts": 3, "timeoutMs": 30000 },
  "inputs":    { "processFlow": { /* full processFlow object */ } }
}
```

#### `task.event` — WAITING (task-runner → task.command)

```json
{
  "eventId": "01HRZX7Q...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-12T10:00:03.500Z",
  "messageType": "EVENT",
  "messageName": "task.event",
  "source": "task-runner",
  "task": {
    "id":   "tf-9a1b",
    "href": "https://tmf-process-flow.../processFlow/bbf5e84d-.../taskFlow/tf-9a1b"
  },
  "action": {
    "ref": "a1",
    "actionCode": "VOICE_SERVICE_DIAGNOSTIC",
    "actionName": "runVoiceDiagnostic",
    "dcxActionCode": "DCX-VSD-01"
  },
  "batch":  { "index": 0 },
  "status": "WAITING",
  "execution": {
    "mode": "ASYNC",
    "attempt": 1,
    "startedAt": "2026-04-12T10:00:03.500Z"
  },
  "downstream": {
    "id":   "VOICE_transactionId_A267E5877F47467993818722C",
    "href": "https://sharp-oneside-task.../findVoiceServiceDiagnosticFromCacheByTransactionId/VOICE_transactionId_A267..."
  },
  "awaitingSignal": {
    "businessTxnId": "VOICE_transactionId_A267E5877F47467993818722C"
  },
  "result": {
    "name": "runVoiceDiagnostic",
    "code": "VOICE_SERVICE_DIAGNOSTIC",
    "id":   "tf-9a1b",
    "type": "TaskFlow",
    "taskFlowResponse": {
      "id":    "tf-9a1b",
      "href":  "https://tmf-process-flow.../processFlow/bbf5e84d-.../taskFlow/tf-9a1b",
      "@type": "TaskFlow",
      "state": "acknowledged"
    },
    "taskStatusCode": "WAITING"
  },
  "error":  null
}
```

#### `task.signal` (pamconsumer → task.command)

```json
{
  "eventId": "01HRZX9S...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-12T10:05:21.500Z",
  "messageType": "SIGNAL",
  "messageName": "task.signal",
  "source": "pamconsumer",
  "task": {
    "id":   "tf-9a1b",
    "href": "https://tmf-process-flow.../processFlow/bbf5e84d-.../taskFlow/tf-9a1b"
  },
  "action": {
    "ref": "a1",
    "actionCode": "VOICE_SERVICE_DIAGNOSTIC",
    "actionName": "runVoiceDiagnostic",
    "dcxActionCode": "DCX-VSD-01"
  },
  "batch": { "index": 0 },
  "inputs": {
    "downstream": {
      "id":   "VOICE_transactionId_A267E5877F47467993818722C",
      "href": "https://sharp-oneside-task.../findVoiceServiceDiagnosticFromCacheByTransactionId/VOICE_transactionId_A267..."
    },
    "trigger": {
      "externalEventId": "985367b9-12f3-4cdd-b919-db9696ccfafa",
      "externalType":    "TaskFinalAsyncResponseSend",
      "reportingSystem": "ACUT"
    }
  }
}
```

#### `task.event` — COMPLETED (task-runner → task.command)

```json
{
  "eventId": "01HRZXAT...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-12T10:05:22.100Z",
  "messageType": "EVENT",
  "messageName": "task.event",
  "source": "task-runner",
  "task": {
    "id":   "tf-9a1b",
    "href": "https://tmf-process-flow.../processFlow/bbf5e84d-.../taskFlow/tf-9a1b"
  },
  "action": {
    "ref": "a1",
    "actionCode": "VOICE_SERVICE_DIAGNOSTIC",
    "actionName": "runVoiceDiagnostic",
    "dcxActionCode": "DCX-VSD-01"
  },
  "batch":  { "index": 0 },
  "status": "COMPLETED",
  "execution": {
    "mode": "ASYNC",
    "attempt": 1,
    "startedAt":  "2026-04-12T10:00:03.500Z",
    "finishedAt": "2026-04-12T10:05:22.100Z",
    "durationMs": 318600
  },
  "downstream": {
    "id":   "VOICE_transactionId_A267E5877F47467993818722C",
    "href": "https://sharp-oneside-task.../findVoiceServiceDiagnosticFromCacheByTransactionId/VOICE_transactionId_A267..."
  },
  "result": {
    "name": "runVoiceDiagnostic",
    "code": "VOICE_SERVICE_DIAGNOSTIC",
    "id":   "tf-9a1b",
    "type": "TaskFlow",
    "taskResult": {
      "voiceDiagnostic": {
        "status":      "pass",
        "lineQuality": "GOOD",
        "noiseLevel":  -55,
        "testedAt":    "2026-04-12T10:05:18.000Z"
      }
    },
    "taskFlowResponse": {
      "id":    "tf-9a1b",
      "href":  "https://tmf-process-flow.../processFlow/bbf5e84d-.../taskFlow/tf-9a1b",
      "@type": "TaskFlow",
      "state": "completed",
      "characteristic": [
        { "name": "status",      "value": "pass" },
        { "name": "lineQuality", "value": "GOOD" },
        { "name": "noiseLevel",  "value": "-55", "valueType": "Integer" }
      ]
    },
    "taskStatusCode": "COMPLETED"
  },
  "error": null
}
```

### Orchestrator effect across the ASYNC lifecycle

| Message received | Barrier change | Other effect |
|---|---|---|
| `task.event` WAITING | none | upsert task_execution; PATCH parent processFlow with taskFlow ref |
| `task.signal` | none | log only |
| `task.event` COMPLETED | `task_completed += 1`; close + seed-next if pending=0 | upsert task_execution; (taskFlow ref already added on WAITING) |

---

## 12. Combined E2E Walkthrough

Scenario: `Auto_Remediation` flow with two batches.
- Batch 0 = `[a1 (ASYNC voice diagnostic), a2 (SYNC internet check)]`
- Batch 1 = `[a3 (SYNC notify user)]`

`processFlow.id` = `bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152`

| Step | Producer → Topic | messageName | Effect |
|---|---|---|---|
| 1 | TTD → TMF-701 (HTTP) | — | processFlow created, id=`bbf5e84d-...` |
| 2 | TMF-701 → notification-management | processFlow.created | pamconsumer ingests |
| 3 | pamconsumer (filter) | — | Spec=Auto_Remediation passes |
| 4 | pamconsumer → task.command | `processFlow.initiated` | carries `dagKey` + `inputs.processFlow` |
| 5 | orchestrator | — | seeds barrier batch 0 (OPEN, total=2) |
| 6a | orchestrator → task.command | `task.execute` (a1, ASYNC) | runner picks up |
| 6b | orchestrator → task.command | `task.execute` (a2, SYNC) | runner picks up |
| 7 | runner | — | runs both pipelines in parallel |
| 8a | runner → task.command | `task.event` COMPLETED (a2) | barrier: completed=1, pending=1 |
| 8b | runner → task.command | `task.event` WAITING (a1) | barrier unchanged; ref added to processFlow |
| 9 | orchestrator → TMF-701 (PATCH) | — | parent processFlow gains taskFlow refs |
| 10 | external system → notification-management | TaskFinalAsyncResponseSend | pamconsumer correlates by businessTxnId |
| 11 | pamconsumer → task.command | `task.signal` | runner picks up |
| 12 | runner → task.command | `task.event` COMPLETED (a1) | barrier: completed=2, pending=0 → CLOSE batch 0 |
| 13 | orchestrator | — | lazy-seed batch 1 (OPEN, total=1); publish a3 |
| 14 | orchestrator → task.command | `task.execute` (a3, SYNC) | runner picks up |
| 15 | runner → task.command | `task.event` COMPLETED (a3) | barrier batch 1: completed=1, pending=0 → CLOSE |
| 16 | orchestrator → TMF-701 (PATCH) | — | processFlow `state=completed` |

### Barrier table progression

After Step 5:
| batch_index | total | completed | failed | pending | status |
|---|---|---|---|---|---|
| 0 | 2 | 0 | 0 | 2 | OPEN |

After Step 8a:
| batch_index | total | completed | failed | pending | status |
|---|---|---|---|---|---|
| 0 | 2 | 1 | 0 | 1 | OPEN |

After Step 12 (batch 1 lazy-seeded):
| batch_index | total | completed | failed | pending | status |
|---|---|---|---|---|---|
| 0 | 2 | 2 | 0 | 0 | CLOSED |
| 1 | 1 | 0 | 0 | 1 | OPEN |

After Step 15:
| batch_index | total | completed | failed | pending | status |
|---|---|---|---|---|---|
| 0 | 2 | 2 | 0 | 0 | CLOSED |
| 1 | 1 | 1 | 0 | 0 | CLOSED |

---

## 13. DAG YAML Format

DAGs live in `src/main/resources/dag/{dagKey}.yml`. The orchestrator loads all of them at startup into the `DagRegistry`.

```yaml
dagKey: Auto_Remediation
match:
  processFlowSpecification: Auto_Remediation
batches:
  - index: 0
    actions:
      - ref: a1
        actionCode: VOICE_SERVICE_DIAGNOSTIC
      - ref: a2
        actionCode: INTERNET_CHECK
  - index: 1
    actions:
      - ref: a3
        actionCode: NOTIFY_USER
```

The DAG only references actions by `actionCode`. The full action triplet (`actionName`, `dcxActionCode`, `executionMode`, `timeoutMs`, `maxAttempts`) is hydrated at command-build time from the Action Registry (Section 14).

### `ref` field

`ref` is a DAG-local key (`a1`, `a2`, …) used to correlate a `task.execute` command with the `task.event` it produces. It is required because `task.id` does not exist at command time (the runner mints it).

---

## 14. Action Registry

At startup the orchestrator loads action metadata from the TMF action-code API and caches it in memory:

```json
{
  "actionCode":    "VOICE_SERVICE_DIAGNOSTIC",
  "actionName":    "runVoiceDiagnostic",
  "dcxActionCode": "DCX-VSD-01",
  "executionMode": "ASYNC",
  "timeoutMs":     30000,
  "maxAttempts":   3
}
```

When the orchestrator builds a `task.execute` message, it joins:

- The DAG entry → gives `ref` and `actionCode`.
- The Action Registry entry → gives `actionName`, `dcxActionCode`, `executionMode`, `timeoutMs`, `maxAttempts`.

This way the DAG stays small and action metadata has a single source of truth.

---

## 15. TMF-701 PATCH Contract

### Per task: register taskFlow ref on parent processFlow

When a new taskFlow is observed (on WAITING or first-seen COMPLETED), orchestrator PATCHes the parent processFlow's `relatedEntity[]`:

```http
PATCH /tmf-api/processFlowManagement/v4/processFlow/{processFlowId}
Content-Type: application/json
```

```json
{
  "relatedEntity": [
    {
      "id":            "tf-7c3d",
      "href":          "https://tmf-process-flow.../taskFlow/tf-7c3d",
      "role":          "TaskFlow",
      "@type":         "RelatedEntity",
      "@referredType": "TaskFlow",
      "name":          "INTERNET_CHECK"
    }
  ]
}
```

### When all batches close: mark processFlow completed

```http
PATCH /tmf-api/processFlowManagement/v4/processFlow/{processFlowId}
```
```json
{ "state": "completed" }
```

### On terminal failure

```json
{ "state": "failed" }
```

The per-taskFlow PATCH (writing the actual result back to the taskFlow itself) is the **runner's** responsibility, not the orchestrator's.

---

## 16. Orchestrator Dispatch Table

| messageName | source | Action |
|---|---|---|
| `processFlow.initiated` | pamconsumer | Resolve DAG by `dagKey`; **seed batch 0 only**, status=OPEN; publish `task.execute` per action in batch 0 |
| `task.execute` | task-orchestrator | Ignore (own message) |
| `task.event` (status=INITIAL or IN_PROGRESS) | task-runner | Upsert `task_execution`; no barrier change |
| `task.event` (status=WAITING) | task-runner | Upsert `task_execution`; PATCH parent processFlow with new taskFlow ref; no barrier change |
| `task.event` (status=COMPLETED) | task-runner | Upsert `task_execution`; **validate `result.taskStatusCode == COMPLETED` and (if present) `taskFlowResponse.characteristic[name=status].value == pass` — if either check fails the event is rejected, the barrier flips to FAILED, and the processFlow is PATCHed `state=failed`**; otherwise PATCH parent processFlow if needed; barrier `task_completed += 1`; if `pending==0` → CLOSE batch, lazy-seed next batch (if any) and publish its `task.execute` messages, else PATCH processFlow `state=completed` |
| `task.event` (status=FAILED, retryable=false) | task-runner | Upsert `task_execution`; barrier `task_failed += 1`, status=FAILED; PATCH processFlow `state=failed` |
| `task.signal` | pamconsumer | Log only (observability) |

---

## 17. Operational Notes

### Topic provisioning

| Concurrent processFlows | Partitions for `task.command` |
|---|---|
| < 100 | 6 |
| 100–1k | 12 |
| 1k–10k | 24 |
| > 10k | 48+ |

Partitions can grow but never shrink — start a notch above today's need.

### Consumer concurrency

Set `spring.kafka.listener.concurrency` ≤ partition count. Each thread owns one or more partitions, and partition affinity ensures all events for a single processFlow stay serialized on one thread.

### Producer guarantees

- `acks=all`
- `enable.idempotence=true`
- Always set Kafka record key = `correlationId` so the partition assignment matches the consumer's expectation of per-flow ordering.

### Schema evolution

- `schemaVersion` is on every message.
- Add new optional fields freely; never remove or rename existing fields without bumping the major version.
- Consumers should ignore unknown fields (`@JsonIgnoreProperties(ignoreUnknown = true)`).

### Observability

Recommended structured-log fields on every handled message:

```
correlationId, eventId, messageName, source, batch.index, action.ref, status
```

This makes a single processFlow's lifecycle trivially greppable end-to-end.
