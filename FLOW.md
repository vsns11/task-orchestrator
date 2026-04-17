# Task Orchestrator — End-to-End Message Flow

This document walks through every message produced and consumed during
the execution of the `Auto_Remediation` DAG. Each step shows the full
JSON payload, which component publishes it, which component reads it,
and what side effects happen (database writes, HTTP calls).

---

## Components

| Component | What it does |
|-----------|-------------|
| **DemoFlowTrigger** | HTTP endpoint that simulates TTD creating a processFlow on TMF-701. Publishes a real TMF-701 processFlow payload to `notification.management` topic. |
| **MockPamConsumer** | Kafka consumer on `notification.management`. Reads real TMF-701 events, extracts `processFlowSpecification` as the DAG key, transforms into `processFlow.initiated` TaskCommand and publishes to `task.command`. Also injects async signals when called by MockTaskRunner. |
| **Orchestrator** | Kafka consumer on `task.command` (group: `task-orchestrator`). Manages batch barriers, seeds batches, publishes `task.execute` commands, advances the DAG. Emits ALL `flow.lifecycle` events (INITIAL / COMPLETED / FAILED) — the orchestrator is the single authoritative producer for flow-level lifecycle. |
| **MockTaskRunner** | Kafka consumer on `task.command` (group: `mock-task-runner`). Executes actions with configurable retry on downstream HTTP calls. SYNC actions complete immediately, ASYNC actions go through WAITING → signal → COMPLETED. |
| **MockTmf701Controller** | HTTP mock at `/mock/tmf701`. Stores processFlow state in memory. The orchestrator PATCHes it on COMPLETED/FAILED to register taskFlow references (appended, not replaced) and update lifecycle state. Does NOT PATCH on WAITING. |
| **MockActionRegistryController** | HTTP mock at `/mock/actionregistry`. Serves two APIs: `GET /actions` (actionName → actionCode) and `GET /dcx-actions` (actionName → dcxActionCode). Both are loaded at startup and reloaded every 24 hours (configurable). |

## HTTP APIs (Action Registry)

The orchestrator loads action identity from two separate APIs at startup (chained lookup, same pattern as the real PAM ActionCodeService):

**Step 1 — Load action codes (actionName → actionCode):**
```
GET /mock/actionregistry/action-codes
→ [{ actionCode, actionName, description, flowType, nodeType }]
```

**Step 2 — Load DCX action codes (actionCode → dcxActionCode):**
```
GET /mock/actionregistry/dcx-action-codes
→ [{ parent (=actionCode), flowType, dcxActionCode, nodeType }]
```

**Chained lookup at runtime:**
```
actionName="runVoiceDiagnostic"
  → Map 1 (by actionName): actionCode = "VOICE_SERVICE_DIAGNOSTIC"
  → Map 2 (by actionCode = parent): dcxActionCode = "DCX-VSD-01"
```

The `parent` field in the DCX response is the join key — it equals the `actionCode` from the first API.

Reload schedule: configurable via `orchestrator.actionregistry.reload-cron` (default: `0 0 2 * * *` = every day at 2 AM).

## Kafka Topics

| Topic | Payload type | Who writes | Who reads |
|-------|-------------|-----------|----------|
| `notification.management` | `NotificationEvent` (real TMF-701 native JSON) | DemoFlowTrigger (simulating TMF-701) | MockPamConsumer |
| `task.command` | `TaskCommand` (orchestrator's uniform DTO) | MockPamConsumer, Orchestrator, MockTaskRunner | Orchestrator, MockTaskRunner |

Everything flows through `task.command` — commands, events, signals, AND lifecycle events.

## DAG Definition (Auto_Remediation.yml)

```yaml
dagKey: Auto_Remediation
batches:
  - index: 0
    actions:
      - actionName: runVoiceDiagnostic
        executionMode: ASYNC
      - actionName: runInternetCheck
        executionMode: SYNC
  - index: 1
    actions:
      - actionName: sendNotification
        executionMode: SYNC
        dependsOn:
          - runVoiceDiagnostic
          - runInternetCheck
```

The DAG defines only `actionName`, `executionMode`, and `dependsOn`. Retry config
(`maxAttempts`, `timeoutMs`) is NOT in the DAG — it's the task-runner's responsibility
and lives with the task-runner service, not with the orchestrator.

When an action declares `dependsOn`, the orchestrator queries the `task_execution` table
for the latest COMPLETED result of each listed action and passes it in
`inputs.dependencyResults`. Actions without `dependsOn` get no dependency results.

---

## Step 1 — processFlow (TMF-701 native payload)

**Topic:** `notification.management`
**Published by:** DemoFlowTrigger (simulating TMF-701)
**Consumed by:** MockPamConsumer
**Payload type:** `ProcessFlow` (real TMF-701 structure)

This is the entry point. In production, TMF-701 publishes this when
a processFlow is created via HTTP POST from TTD.

```json
{
  "id": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "href": "https://tmf-process-flow/tmf-api/processFlowManagement/v4/processFlow/bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "@type": "processFlow",
  "state": "active",
  "@baseType": "processFlow",
  "channel": [],
  "relatedParty": [],
  "relatedEntity": [
    {
      "id": "B54CCE7C0E0840FF86689103A",
      "href": "https://sharp-oneside-task/onesideTaskCatalog/findServiceDiagnosticFromCacheByTransactionId/B54CCE7C0E0840FF86689103A",
      "name": "Internet Service Diagnostic",
      "role": "RelatedEntity",
      "@type": "RelatedEntity",
      "@referredType": "InternetServiceDiagnostic"
    }
  ],
  "characteristic": [
    {
      "id": "B54CCE7C0E0840FF86689103A",
      "name": "SDT Transaction ID",
      "value": "",
      "valueType": "string",
      "characteristicRelationship": []
    },
    {
      "id": "N/A",
      "name": "internetSubscription",
      "value": "",
      "valueType": "string",
      "characteristicRelationship": []
    },
    {
      "id": "EZ82449",
      "name": "peinNumber",
      "value": "",
      "valueType": "string",
      "characteristicRelationship": []
    }
  ],
  "processFlowSpecification": "Auto_Remediation"
}
```

**What MockPamConsumer does when it reads this:**
- Checks `@type` → `"processFlow"` → this is a processFlow.created event
- Extracts `id` → `"bbf5e84d-..."` → uses as `correlationId` (= processFlowId)
- Extracts `processFlowSpecification` → `"Auto_Remediation"` → uses as `dagKey`
- Converts the entire payload into a Map and puts it into `inputs.processFlow`
- Publishes a `processFlow.initiated` TaskCommand to `task.command` (step 2)

The `flow.lifecycle` INITIAL event is emitted by the orchestrator, not by
pamconsumer — see step 2a below.

---

## Step 2 — processFlow.initiated

**Topic:** `task.command`
**Published by:** MockPamConsumer
**Consumed by:** Orchestrator
**Payload type:** `TaskCommand`

```json
{
  "eventId": "e5f6a7b8-...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-14T01:41:26.524Z",
  "messageType": "EVENT",
  "messageName": "processFlow.initiated",
  "source": "pamconsumer",
  "dagKey": "Auto_Remediation",
  "inputs": {
    "processFlow": {
      "id": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
      "href": "https://tmf-process-flow/tmf-api/processFlowManagement/v4/processFlow/bbf5e84d-...",
      "type": "processFlow",
      "state": "active",
      "processFlowSpecification": "Auto_Remediation",
      "relatedEntity": [
        { "id": "B54CCE7C0E0840FF86689103A", "name": "Internet Service Diagnostic", "role": "RelatedEntity" }
      ],
      "characteristic": [
        { "id": "EZ82449", "name": "peinNumber", "value": "" }
      ]
    }
  }
}
```

**What the Orchestrator does when it reads this:**
1. Looks up DAG `Auto_Remediation` → finds 2 batches
2. Creates a `batch_barrier` row in PostgreSQL for batch 0:

   | process_flow_id | batch_index | task_total | task_completed | task_failed | status |
   |---|---|---|---|---|---|
   | bbf5e84d-... | 0 | 2 | 0 | 0 | OPEN |

3. For each action in batch 0, looks up `actionCode` from `GET /actions` and `dcxActionCode` from `GET /dcx-actions` by `actionName`
4. Publishes 2 `task.execute` commands — one per action in batch 0

---

## Step 2a — flow.lifecycle INITIAL

**Topic:** `task.command`
**Published by:** Orchestrator (immediately after seeding batch 0, post-commit)
**Consumed by:** Nobody (the orchestrator skips `flow.lifecycle` messages it sees)
**Payload type:** `TaskCommand`

```json
{
  "eventId": "f1a2b3c4-...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-14T01:41:26.525Z",
  "messageType": "EVENT",
  "messageName": "flow.lifecycle",
  "source": "task-orchestrator",
  "dagKey": "Auto_Remediation",
  "status": "INITIAL"
}
```

Observability event. Filter by `messageName = "flow.lifecycle"` to track flow start/end.
All three lifecycle events (INITIAL, COMPLETED, FAILED) carry
`source = "task-orchestrator"` so you have a single authoritative producer to
filter on.

---

## Step 3a — task.execute (runVoiceDiagnostic, ASYNC)

**Topic:** `task.command`
**Published by:** Orchestrator
**Consumed by:** MockTaskRunner
**Payload type:** `TaskCommand`

```json
{
  "eventId": "c9d0e1f2-...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-14T01:41:26.530Z",
  "messageType": "COMMAND",
  "messageName": "task.execute",
  "source": "task-orchestrator",
  "dagKey": "Auto_Remediation",
  "intent": "EXECUTE",
  "action": {
    "actionName": "runVoiceDiagnostic",
    "actionCode": "VOICE_SERVICE_DIAGNOSTIC",
    "dcxActionCode": "DCX-VSD-01"
  },
  "batch": { "index": 0, "total": 2 },
  "execution": { "mode": "ASYNC" },
  "inputs": {
    "processFlow": { "id": "bbf5e84d-...", "processFlowSpecification": "Auto_Remediation" }
  }
}
```

**Where each field comes from:**
- `action.actionName` → from DAG YAML
- `action.actionCode` → looked up from `GET /actions` by actionName
- `action.dcxActionCode` → looked up from `GET /dcx-actions` by actionName
- `execution.mode` → from DAG YAML
  (`execution.timeoutMs` and `execution.maxAttempts` are owned by the task-runner, not the orchestrator)

**What MockTaskRunner does:**
- Calls downstream with retry (configurable: max 3 attempts on HTTP 502/503/504/429)
- Sees `execution.mode = ASYNC`
- Mints taskFlow ID: `tf-31c94387`
- Publishes `task.event` WAITING (step 5), then tells MockPamConsumer to inject a signal

---

## Step 3b — task.execute (runInternetCheck, SYNC)

**Topic:** `task.command`
**Published by:** Orchestrator
**Consumed by:** MockTaskRunner
**Payload type:** `TaskCommand`

```json
{
  "eventId": "d1e2f3a4-...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-14T01:41:26.531Z",
  "messageType": "COMMAND",
  "messageName": "task.execute",
  "source": "task-orchestrator",
  "dagKey": "Auto_Remediation",
  "intent": "EXECUTE",
  "action": {
    "actionName": "runInternetCheck",
    "actionCode": "INTERNET_CHECK",
    "dcxActionCode": "DCX-INT-04"
  },
  "batch": { "index": 0, "total": 2 },
  "execution": { "mode": "SYNC" },
  "inputs": {
    "processFlow": { "id": "bbf5e84d-...", "processFlowSpecification": "Auto_Remediation" }
  }
}
```

**What MockTaskRunner does:**
- Calls downstream with retry (configurable)
- Sees `execution.mode = SYNC`, mints taskFlow ID: `tf-ca72d013`
- Publishes `task.event` COMPLETED immediately (step 4)

---

## Step 4 — task.event COMPLETED (runInternetCheck)

**Topic:** `task.command`
**Published by:** MockTaskRunner
**Consumed by:** Orchestrator
**Payload type:** `TaskCommand`

```json
{
  "eventId": "f5a6b7c8-...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-14T01:41:26.850Z",
  "messageType": "EVENT",
  "messageName": "task.event",
  "source": "task-runner",
  "status": "COMPLETED",
  "task": {
    "id": "tf-ca72d013",
    "href": "http://mock-tmf701/processFlow/bbf5e84d-.../taskFlow/tf-ca72d013"
  },
  "action": {
    "actionName": "runInternetCheck",
    "actionCode": "INTERNET_CHECK",
    "dcxActionCode": "DCX-INT-04"
  },
  "batch": { "index": 0 },
  "execution": { "mode": "SYNC", "attempt": 1, "startedAt": "...", "finishedAt": "...", "durationMs": 1000 },
  "result": {
    "name": "runInternetCheck",
    "code": "INTERNET_CHECK",
    "id": "tf-ca72d013",
    "type": "TaskFlow",
    "taskResult": {
      "status": "pass",
      "diagnosticSummary": "All checks passed for runInternetCheck",
      "latencyMs": 245
    },
    "taskFlowResponse": {
      "id": "tf-ca72d013",
      "href": "http://mock-tmf701/.../taskFlow/tf-ca72d013",
      "@type": "TaskFlow",
      "state": "completed",
      "characteristic": [
        { "name": "status", "value": "pass" },
        { "name": "diagnosticSummary", "value": "All checks passed for runInternetCheck" }
      ]
    },
    "taskStatusCode": "COMPLETED"
  }
}
```

The `result` field is an `ActionResponse` with two payload slots:
- `taskResult` — the raw downstream response body (any shape: `JsonNode`, `Map`, domain DTO). Used for dependency-result passing between batches and for audit.
- `taskFlowResponse` — the typed TMF-701 `TaskFlow` resource (`id`, `href`, `state`, `characteristic[]`). The orchestrator reads `href` from here to PATCH the parent processFlow.

**COMPLETED validation.** Before advancing the barrier, the orchestrator also checks:

1. `result.taskStatusCode` must equal `COMPLETED` (case-insensitive) when present.
2. If `taskFlowResponse.characteristic[]` carries a `status` entry, its value must equal `pass` (case-insensitive).

If either check fails, the `task.event` is rejected: the barrier is closed with `status=FAILED`, the parent processFlow is PATCHed `state=failed`, and a `flow.lifecycle FAILED` event is published. Task-runners must therefore emit both `taskStatusCode="COMPLETED"` AND `characteristic: [{"name":"status","value":"pass"}]` for a successful completion.

**What the Orchestrator does:**
1. Inserts `task_execution` row: `process_flow_id=bbf5e84d, task_flow_id=tf-ca72d013, status=COMPLETED`
2. PATCHes TMF-701 — registers taskFlowId + href on the processFlow:
   ```json
   PATCH /mock/tmf701/processFlow/bbf5e84d-...
   { "relatedEntity": [{ "id": "tf-ca72d013", "href": "http://mock-tmf701/.../tf-ca72d013", "name": "runInternetCheck", "role": "TaskFlow" }] }
   ```
3. Updates `batch_barrier`: `task_completed = 1`, `pending = 1`

---

## Step 5 — task.event WAITING (runVoiceDiagnostic)

**Topic:** `task.command`
**Published by:** MockTaskRunner
**Consumed by:** Orchestrator
**Payload type:** `TaskCommand`

```json
{
  "eventId": "a1b2c3d4-...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-14T01:41:26.879Z",
  "messageType": "EVENT",
  "messageName": "task.event",
  "source": "task-runner",
  "status": "WAITING",
  "task": {
    "id": "tf-31c94387",
    "href": "http://mock-tmf701/processFlow/bbf5e84d-.../taskFlow/tf-31c94387"
  },
  "action": {
    "actionName": "runVoiceDiagnostic",
    "actionCode": "VOICE_SERVICE_DIAGNOSTIC",
    "dcxActionCode": "DCX-VSD-01"
  },
  "batch": { "index": 0 },
  "execution": { "mode": "ASYNC", "attempt": 1, "startedAt": "..." },
  "downstream": { "id": "ASYNC_1e4ec1e0-68a", "href": "http://mock-downstream/queries/ASYNC_1e4ec1e0-68a" },
  "awaitingSignal": { "downstreamTransactionId": "ASYNC_1e4ec1e0-68a" }
}
```

**What the Orchestrator does:**
1. Inserts `task_execution` row with `status = WAITING` and `downstream_id = ASYNC_1e4ec1e0-68a`
2. Does NOT PATCH TMF-701 — taskFlow reference is only registered on COMPLETED
3. Does NOT change the barrier — `pending` stays at 1. The flow is paused here.

---

## Step 6 — task.signal (async callback)

**Topic:** `task.command`
**Published by:** MockPamConsumer (called by MockTaskRunner after 300-900ms delay)
**Consumed by:** MockTaskRunner
**Payload type:** `TaskCommand`

```json
{
  "eventId": "b3c4d5e6-...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-14T01:41:27.552Z",
  "messageType": "SIGNAL",
  "messageName": "task.signal",
  "source": "pamconsumer",
  "task": { "id": "tf-31c94387", "href": "http://mock-tmf701/.../taskFlow/tf-31c94387" },
  "action": { "actionName": "runVoiceDiagnostic", "actionCode": "VOICE_SERVICE_DIAGNOSTIC", "dcxActionCode": "DCX-VSD-01" },
  "batch": { "index": 0 },
  "inputs": {
    "downstream": { "id": "ASYNC_1e4ec1e0-68a", "href": "http://mock-downstream/queries/ASYNC_1e4ec1e0-68a" }
  },
  "trigger": { "externalEventId": "e7f8a9b0-...", "externalType": "TaskFinalAsyncResponseSend", "reportingSystem": "ACUT" }
}
```

**Orchestrator:** Logs the signal. Does NOT process it.
**MockTaskRunner:** Fetches result from downstream, publishes COMPLETED (step 7).

---

## Step 7 — task.event COMPLETED (runVoiceDiagnostic)

**Topic:** `task.command`
**Published by:** MockTaskRunner (after receiving signal)
**Consumed by:** Orchestrator
**Payload type:** `TaskCommand`

```json
{
  "eventId": "c5d6e7f8-...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "schemaVersion": "1.0",
  "eventTime": "2026-04-14T01:41:27.576Z",
  "messageType": "EVENT",
  "messageName": "task.event",
  "source": "task-runner",
  "status": "COMPLETED",
  "task": { "id": "tf-31c94387", "href": "http://mock-tmf701/.../taskFlow/tf-31c94387" },
  "action": { "actionName": "runVoiceDiagnostic", "actionCode": "VOICE_SERVICE_DIAGNOSTIC", "dcxActionCode": "DCX-VSD-01" },
  "batch": { "index": 0 },
  "execution": { "mode": "ASYNC", "attempt": 1, "startedAt": "...", "finishedAt": "...", "durationMs": 1000 },
  "downstream": { "id": "ASYNC_1e4ec1e0-68a", "href": "http://mock-downstream/queries/ASYNC_1e4ec1e0-68a" },
  "result": {
    "name": "runVoiceDiagnostic",
    "code": "VOICE_SERVICE_DIAGNOSTIC",
    "id": "tf-31c94387",
    "type": "TaskFlow",
    "taskResult": {
      "status": "pass",
      "diagnosticSummary": "Async voice diagnostic completed successfully",
      "latencyMs": 1200
    },
    "taskFlowResponse": {
      "id": "tf-31c94387",
      "href": "http://mock-tmf701/.../taskFlow/tf-31c94387",
      "@type": "TaskFlow",
      "state": "completed",
      "characteristic": [
        { "name": "status", "value": "pass" },
        { "name": "diagnosticSummary", "value": "Async voice diagnostic completed successfully" }
      ]
    },
    "taskStatusCode": "COMPLETED"
  }
}
```

**What the Orchestrator does:**
1. Updates `task_execution`: `status = COMPLETED`, adds `result_json`
2. PATCHes TMF-701 — registers taskFlowId (this is the FIRST and ONLY PATCH for this task):
   ```json
   PATCH /mock/tmf701/processFlow/bbf5e84d-...
   { "relatedEntity": [{ "id": "tf-31c94387", "href": "http://mock-tmf701/.../tf-31c94387", "name": "runVoiceDiagnostic", "role": "TaskFlow" }] }
   ```
3. Updates `batch_barrier`: `task_completed = 2`, `pending = 0` → CLOSE batch 0
4. Promotes batch 1: seeds barrier, publishes task.execute for sendNotification

---

## Step 8 — task.execute (sendNotification, SYNC)

**Topic:** `task.command`
**Published by:** Orchestrator
**Consumed by:** MockTaskRunner
**Payload type:** `TaskCommand`

```json
{
  "eventId": "d7e8f9a0-...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "messageType": "COMMAND",
  "messageName": "task.execute",
  "source": "task-orchestrator",
  "dagKey": "Auto_Remediation",
  "intent": "EXECUTE",
  "action": { "actionName": "sendNotification", "actionCode": "NOTIFY_USER", "dcxActionCode": "DCX-NOT-09" },
  "batch": { "index": 1, "total": 1 },
  "execution": { "mode": "SYNC" },
  "inputs": {
    "processFlow": { "id": "bbf5e84d-...", "processFlowSpecification": "Auto_Remediation" },
    "dependencyResults": {
      "runInternetCheck":   "{\"name\":\"runInternetCheck\",\"id\":\"tf-ca72d013\",\"taskResult\":{\"status\":\"pass\"},\"taskFlowResponse\":{\"id\":\"tf-ca72d013\",\"href\":\"http://.../taskFlow/tf-ca72d013\",\"state\":\"completed\"},\"taskStatusCode\":\"COMPLETED\"}",
      "runVoiceDiagnostic": "{\"name\":\"runVoiceDiagnostic\",\"id\":\"tf-31c94387\",\"taskResult\":{\"status\":\"pass\"},\"taskFlowResponse\":{\"id\":\"tf-31c94387\",\"href\":\"http://.../taskFlow/tf-31c94387\",\"state\":\"completed\"},\"taskStatusCode\":\"COMPLETED\"}"
    }
  }
}
```

---

## Step 9 — task.event COMPLETED (sendNotification)

**Topic:** `task.command`
**Published by:** MockTaskRunner
**Consumed by:** Orchestrator
**Payload type:** `TaskCommand`

```json
{
  "eventId": "e9f0a1b2-...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "messageType": "EVENT",
  "messageName": "task.event",
  "source": "task-runner",
  "status": "COMPLETED",
  "task": { "id": "tf-4b6b61e2", "href": "http://mock-tmf701/.../taskFlow/tf-4b6b61e2" },
  "action": { "actionName": "sendNotification", "actionCode": "NOTIFY_USER", "dcxActionCode": "DCX-NOT-09" },
  "batch": { "index": 1 },
  "execution": { "mode": "SYNC", "attempt": 1, "startedAt": "...", "finishedAt": "...", "durationMs": 1000 },
  "result": {
    "name": "sendNotification",
    "code": "NOTIFY_USER",
    "id": "tf-4b6b61e2",
    "type": "TaskFlow",
    "taskResult": {
      "status": "pass",
      "notificationSent": true
    },
    "taskFlowResponse": {
      "id": "tf-4b6b61e2",
      "href": "http://mock-tmf701/.../taskFlow/tf-4b6b61e2",
      "@type": "TaskFlow",
      "state": "completed",
      "characteristic": [ { "name": "status", "value": "pass" } ]
    },
    "taskStatusCode": "COMPLETED"
  }
}
```

**What the Orchestrator does:**
1. Closes batch 1: `task_completed = 1`, `pending = 0` → CLOSED
2. PATCHes TMF-701 with taskFlow reference for sendNotification
3. No more batches → PATCHes TMF-701: `{ "state": "completed" }`
4. Publishes `flow.lifecycle COMPLETED` (step 9a)

---

## Step 9a — flow.lifecycle COMPLETED

**Topic:** `task.command`
**Published by:** Orchestrator
**Consumed by:** Nobody (observability only)
**Payload type:** `TaskCommand`

```json
{
  "eventId": "a0b1c2d3-...",
  "correlationId": "bbf5e84d-cf41-44ba-b0d4-45e4e8b0a152",
  "eventTime": "2026-04-14T01:41:28.100Z",
  "messageType": "EVENT",
  "messageName": "flow.lifecycle",
  "source": "task-orchestrator",
  "status": "COMPLETED"
}
```

---

## Async Response Event (production reference)

In production, when an external system completes an async operation, it publishes
a `TaskFinalAsyncResponseSend` event to `notification.management`:

**Topic:** `notification.management`
**Payload type:** `AsyncResponseEvent`

```json
{
  "correlationId": "VOICE_transactionId_A267E5B77F4746799381872C",
  "domain": "bell-it-sa",
  "eventId": "0E530799-32F3-4cd8-8919-db9696ccfafa",
  "eventTime": "2026-04-01T18:34:34.149335894-04:00",
  "priority": "NORMAL",
  "title": "vsdt-task-request",
  "event": {
    "id": "VOICE_transactionId_A267E5B77F4746799381872C",
    "href": "https://sharp-oneside-task.apps.ocp-prd-wvn.bell.corp.bce.ca/onesideTaskCatalog/findVoiceServiceDiagnosticFromCacheByTransactionId/VOICE_transactionId_A267E5B77F4746799381872C"
  },
  "reportingSystem": { "id": "ACUT", "name": "ACUT" },
  "@type": "TaskFinalAsyncResponseSend"
}
```

Pamconsumer correlates by `event.id`, looks up `waiting_task`, publishes `task.signal` to `task.command`.

---

## Manual Signal Injection

To manually complete an ASYNC task that is in WAITING status, use `POST /demo/signal`.

**Steps:**
1. Run `GET /demo/tasks` — find the WAITING task
2. Copy its `taskFlowId` (e.g. `tf-31c94387`) and `downstreamId` (e.g. `ASYNC_1e4ec1e0-68a`)
3. Call the signal endpoint:

```
POST http://localhost:8080/demo/signal?processFlowId=bbf5e84d-...&taskFlowId=tf-31c94387&downstreamTransactionId=ASYNC_1e4ec1e0-68a
```

**What happens:**
- Publishes a `task.signal` to `task.command` (same as what pamconsumer would publish)
- MockTaskRunner picks up the signal → publishes `task.event COMPLETED`
- Orchestrator advances the barrier → promotes next batch or completes the flow

**Response:**
```json
{
  "processFlowId": "bbf5e84d-...",
  "taskFlowId": "tf-31c94387",
  "downstreamTransactionId": "ASYNC_1e4ec1e0-68a",
  "signalEventId": "uuid",
  "message": "task.signal injected → task.command → MockTaskRunner will complete the task"
}
```

In Insomnia: use the "POST Inject Signal" request under the "Manual Signal Injection" folder.
Paste `process_flow_id`, `task_flow_id`, and `downstream_txn_id` into the environment variables first.

---

## Final State

### batch_barrier table

```sql
SELECT process_flow_id, batch_index, task_total, task_completed, task_failed, status
FROM batch_barrier WHERE process_flow_id = 'bbf5e84d-...' ORDER BY batch_index;
```

| process_flow_id | batch_index | task_total | task_completed | task_failed | status |
|---|---|---|---|---|---|
| bbf5e84d-... | 0 | 2 | 2 | 0 | CLOSED |
| bbf5e84d-... | 1 | 1 | 1 | 0 | CLOSED |

### task_execution table

```sql
SELECT process_flow_id, task_flow_id, action_name, action_code, batch_index, status, downstream_id
FROM task_execution WHERE process_flow_id = 'bbf5e84d-...' ORDER BY batch_index, action_name;
```

| process_flow_id | task_flow_id | action_name | action_code | batch_index | status | downstream_id |
|---|---|---|---|---|---|---|
| bbf5e84d-... | tf-ca72d013 | runInternetCheck | INTERNET_CHECK | 0 | COMPLETED | — |
| bbf5e84d-... | tf-31c94387 | runVoiceDiagnostic | VOICE_SERVICE_DIAGNOSTIC | 0 | COMPLETED | ASYNC_1e4ec1e0-68a |
| bbf5e84d-... | tf-4b6b61e2 | sendNotification | NOTIFY_USER | 1 | COMPLETED | — |

### Mock TMF-701 processFlow state

```
GET /mock/tmf701/processFlow/bbf5e84d-...
```

```json
{
  "state": "completed",
  "relatedEntity": [
    { "id": "tf-ca72d013", "href": "http://mock-tmf701/.../tf-ca72d013", "name": "runInternetCheck", "role": "TaskFlow", "@type": "RelatedEntity", "@referredType": "TaskFlow" },
    { "id": "tf-31c94387", "href": "http://mock-tmf701/.../tf-31c94387", "name": "runVoiceDiagnostic", "role": "TaskFlow", "@type": "RelatedEntity", "@referredType": "TaskFlow" },
    { "id": "tf-4b6b61e2", "href": "http://mock-tmf701/.../tf-4b6b61e2", "name": "sendNotification", "role": "TaskFlow", "@type": "RelatedEntity", "@referredType": "TaskFlow" }
  ]
}
```

Exactly **3 entries** — one per task, no duplicates. WAITING does not PATCH, only COMPLETED does.

### Kafka topic contents (inspect with `kcat` or a Kafka CLI)

**notification.management — 1 message:**

| Offset | @type | Key |
|--------|-------|-----|
| 0 | processFlow | bbf5e84d-... |

**task.command — 11 messages:**

| Offset | messageName | source | status |
|--------|-------------|--------|--------|
| 0 | processFlow.initiated | pamconsumer | — |
| 1 | flow.lifecycle | task-orchestrator | INITIAL |
| 2 | task.execute | task-orchestrator | — |
| 3 | task.execute | task-orchestrator | — |
| 4 | task.event | task-runner | COMPLETED |
| 5 | task.event | task-runner | WAITING |
| 6 | task.signal | pamconsumer | — |
| 7 | task.event | task-runner | COMPLETED |
| 8 | task.execute | task-orchestrator | — |
| 9 | task.event | task-runner | COMPLETED |
| 10 | flow.lifecycle | task-orchestrator | COMPLETED |

### Consumer groups (all lag = 0)

| Group | Topic |
|-------|-------|
| task-orchestrator | task.command |
| mock-task-runner | task.command |
| mock-pamconsumer | notification.management |

### Task-runner retry config

Retry configuration for downstream HTTP calls lives with the task-runner service,
not the orchestrator. In this repo the only consumer is `mock-task-runner`, which
reads it via `TaskRunnerRetryProperties` (defaults: `max-attempts=3`,
`backoff-ms=1000`, `retryable-status-codes=[502, 503, 504, 429]`). Override
by adding a `task-runner.retry.*` block to the runner's own config when needed.

Downstream HTTP calls are retried up to `max-attempts` times when the response
status code is in `retryable-status-codes`. Non-retryable codes (400, 404, 500,
etc.) fail immediately.

### Action registry reload config

```yaml
orchestrator:
  actionregistry:
    base-url: http://localhost:8080/mock/actionregistry
    reload-cron: "0 0 2 * * *"    # every day at 2 AM
```

Both `/actions` and `/dcx-actions` are called on startup and on the configured cron schedule.
