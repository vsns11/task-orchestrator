# Task Orchestrator — Sample Payloads

Reference JSON payloads for every Kafka message flowing through the task-orchestrator
system. Intended for consumers, producers, integration testers, and documentation.

All payloads describe the **Auto_Remediation** DAG running end-to-end for a single
`processFlow` (`correlationId = bbf5e84d-9c3a-4e7b-a1f2-3d6e8f0a1b2c`). Timestamps
progress chronologically so the files can be read in order as a narrative.

---

## Topics

| Topic | Purpose |
|---|---|
| `notification.management` | TMF-701 native events — the system under test consumes from here and publishes *to* it for async response callbacks. |
| `task.command` | The single internal bus carrying orchestrator commands, task events, signals, and flow lifecycle events. Partitioned by `correlationId` so all events for one flow are processed in order. |

---

## DAG: `Auto_Remediation`

```
Batch 0  (parallel)               Batch 1
┌──────────────────────────┐      ┌──────────────────────────┐
│ runInternetCheck  (SYNC) │ ───▶ │ sendNotification (SYNC)  │
│ runVoiceDiagnostic(ASYNC)│      │   depends on both        │
└──────────────────────────┘      └──────────────────────────┘
```

See `src/main/resources/dag/Auto_Remediation.yml`.

---

## Payload Index

### Happy path — end-to-end flow

| # | File | Topic | Producer → Consumer | What it represents |
|---|------|-------|---------------------|--------------------|
| 01 | `01_processflow_created_notification.json` | `notification.management` | TMF-701 → pamconsumer | A TMF-701 processFlow was just created. Entry point to the system. |
| 02 | `02_processflow_initiated.json` | `task.command` | pamconsumer → task-orchestrator | pamconsumer's transformation of (01). Triggers DAG execution. |
| 10a | `10a_flow_lifecycle_initiated.json` | `task.command` | task-orchestrator → (observers) | Flow-level lifecycle event emitted when seeding batch 0. |
| 03a | `03a_task_execute_sync_runInternetCheck.json` | `task.command` | task-orchestrator → task-runner | Command to execute batch 0 / action 1 (SYNC). |
| 03b | `03b_task_execute_async_runVoiceDiagnostic.json` | `task.command` | task-orchestrator → task-runner | Command to execute batch 0 / action 2 (ASYNC). |
| 04a | `04a_task_event_completed_sync_runInternetCheck.json` | `task.command` | task-runner → task-orchestrator | SYNC action completed — result carried in `result` (ActionResponse). |
| 04b | `04b_task_event_waiting_async_runVoiceDiagnostic.json` | `task.command` | task-runner → task-orchestrator | ASYNC action started — flow pauses awaiting a signal. |
| 11 | `11_async_response_notification.json` | `notification.management` | external system → pamconsumer | Downstream system reports async completion (TMF-701 event format). |
| 05 | `05_task_signal_async_response.json` | `task.command` | pamconsumer → task-runner | pamconsumer's transformation of (11) — unblocks the WAITING task. |
| 06 | `06_task_event_completed_async_runVoiceDiagnostic.json` | `task.command` | task-runner → task-orchestrator | ASYNC action completed after receiving (05). Batch 0 now closes. |
| 07 | `07_task_execute_sync_sendNotification_with_dependencies.json` | `task.command` | task-orchestrator → task-runner | Batch 1 / action 1 — includes `dependencyResults` from batch 0. |
| 08 | `08_task_event_completed_sync_sendNotification.json` | `task.command` | task-runner → task-orchestrator | Batch 1 completes → flow done. |
| 10b | `10b_flow_lifecycle_completed.json` | `task.command` | task-orchestrator → (observers) | Flow-level lifecycle event: all batches closed. |

### Failure scenarios

| # | File | Topic | What it represents |
|---|------|-------|--------------------|
| 09 | `09_task_event_failed_non_retryable.json` | `task.command` | A task that failed non-retryably. `error` is populated; `result` reflects FAILED status. |
| 10c | `10c_flow_lifecycle_failed.json` | `task.command` | Flow-level lifecycle event emitted after a non-retryable task failure. |

---

## Envelope contract

Every message on `task.command` uses a uniform envelope (`TaskCommand`). Consumers
should dispatch on `messageName` + `source`:

| `messageName` | `source` | Who produces | Meaning |
|---|---|---|---|
| `processFlow.initiated` | `pamconsumer` | pamconsumer | A new DAG execution was seeded from a TMF-701 processFlow. |
| `task.execute` | `task-orchestrator` | orchestrator | Command for the task-runner to execute a DAG action. |
| `task.event` | `task-runner` | task-runner | Progress update for a single action (`status`: WAITING, COMPLETED, FAILED, CANCELLED). |
| `task.signal` | `pamconsumer` | pamconsumer | External system signal that unblocks a WAITING async task. |
| `flow.lifecycle` | `pamconsumer` / `task-orchestrator` | both | Coarse-grained flow-level state change (INITIAL, COMPLETED, FAILED). |

Kafka record key is always `correlationId` (the processFlow UUID) — guarantees
ordered delivery of all messages for one flow to a single consumer.

---

## Conventions used in these samples

- **Correlation:** every message shares `correlationId = bbf5e84d-9c3a-4e7b-a1f2-3d6e8f0a1b2c`
  and any embedded processFlow shares the same `id`.
- **Timestamps:** `eventTime` progresses through the 3.4-second happy path
  (T+0.100s → T+3.400s). `execution.startedAt` / `finishedAt` tell the real wall-clock
  work performed by the task-runner.
- **IDs:** `taskFlowId` always starts with `tf-` followed by 8 hex chars; async downstream
  transaction IDs start with `ASYNC-`.
- **URLs:** all examples use RFC-2606 `*.example.com` reserved hostnames. Replace with
  your environment's real endpoints.
- **Null-exclusion:** payloads omit fields that are null (`@JsonInclude(NON_NULL)` on the
  envelope). Consumers MUST tolerate absent optional fields.
- **Schema version:** all messages declare `schemaVersion: "1.0"`. Future breaking
  changes will bump this.

---

## Using these payloads

**For consumers:** pick the payload(s) for the `messageName` you handle; wire them into
your test fixtures. Expect the fields shown; any field not shown for your `messageName`
will be absent on the wire.

**For producers (outside this repo):** the shape shown here is what the orchestrator
expects. Match field names and types exactly. Use `correlationId` = your TMF-701
processFlow id.

**For manual publishing (kcat / Kafka UI):**

```bash
kcat -b $BROKER -t task.command -K: \
  -P <<EOF
bbf5e84d-9c3a-4e7b-a1f2-3d6e8f0a1b2c:$(cat 03a_task_execute_sync_runInternetCheck.json)
EOF
```

The record key (`-K:` then `correlationId:json`) must equal `correlationId` for
partition ordering to hold.
