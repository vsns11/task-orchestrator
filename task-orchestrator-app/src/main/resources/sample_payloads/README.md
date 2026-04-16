# Task Orchestrator — Sample Payloads

Reference JSON payloads covering every message type that flows through the
orchestrator. The payloads are organised by `processFlowSpecification`
(= DAG key) so each subfolder walks one complete end-to-end scenario.

```
sample_payloads/
├── passwordPushV2/       — async single-action flow (DAG key: passwordPushV2)
├── passwordResetV2/      — async single-action flow (DAG key: passwordResetV2)
├── miscellaneous/        — sync  single-action flow (DAG key: miscellaneous)
└── common/               — scenario-agnostic payloads (failure, lifecycle FAILED)
```

The three scenarios mirror the three DAG YAMLs under
`task-orchestrator-app/src/main/resources/dag/`:

| DAG key            | YAML file              | Batches | Actions                                  |
|--------------------|------------------------|---------|------------------------------------------|
| `passwordPushV2`   | `passwordPushV2.yml`   | 1       | `passwordPushV2`  (ASYNC)                |
| `passwordResetV2`  | `passwordResetV2.yml`  | 1       | `passwordResetV2` (ASYNC)                |
| `miscellaneous`    | `miscellaneous.yml`    | 1       | `miscellaneous`   (SYNC)                 |

> The action registry in `mock-action-registry` defines matching
> `actionCode` / `dcxActionCode` entries for all three actions so these
> scenarios also run end-to-end against the local-dev stack, not just as
> documentation.

---

## Topics

| Topic | Purpose |
|---|---|
| `notification.management` | TMF-701 native events — the orchestrator's `pamconsumer` reads from this topic; external systems also publish async response callbacks here. |
| `task.command`            | The single internal bus carrying orchestrator commands, task events, signals, and flow lifecycle events. Partitioned by `correlationId` so all events for one flow are processed in order. |

---

## Scenario 1 — `passwordPushV2` (ASYNC)

A password push to a target credential store. The action is
dispatched, the task-runner acknowledges and publishes `WAITING`, the
downstream system later sends an async response on
`notification.management`, pamconsumer converts it into a `task.signal`,
and the runner publishes `COMPLETED`.

Correlation ID: `11111111-aaaa-4bbb-8ccc-000000000001`

| # | File | Topic | Producer → Consumer |
|---|------|-------|---------------------|
| 01 | `01_processflow_created_notification.json` | `notification.management` | TMF-701 → pamconsumer |
| 02 | `02_processflow_initiated.json`            | `task.command`            | pamconsumer → task-orchestrator |
| 08a| `08a_flow_lifecycle_initial.json`          | `task.command`            | task-orchestrator → (observers) |
| 03 | `03_task_execute_async.json`               | `task.command`            | task-orchestrator → task-runner |
| 04 | `04_task_event_waiting.json`               | `task.command`            | task-runner → task-orchestrator |
| 05 | `05_async_response_notification.json`      | `notification.management` | external system → pamconsumer |
| 06 | `06_task_signal.json`                      | `task.command`            | pamconsumer → task-runner |
| 07 | `07_task_event_completed.json`             | `task.command`            | task-runner → task-orchestrator |
| 08b| `08b_flow_lifecycle_completed.json`        | `task.command`            | task-orchestrator → (observers) |

---

## Scenario 2 — `passwordResetV2` (ASYNC)

A password reset against a federated identity system. Identical shape
to `passwordPushV2` — different action, different ids, same async
WAITING → signal → COMPLETED roundtrip. Use this scenario to see the
pattern applied to a second, independent DAG.

Correlation ID: `22222222-bbbb-4ccc-8ddd-000000000001`

| # | File | Topic | Producer → Consumer |
|---|------|-------|---------------------|
| 01 | `01_processflow_created_notification.json` | `notification.management` | TMF-701 → pamconsumer |
| 02 | `02_processflow_initiated.json`            | `task.command`            | pamconsumer → task-orchestrator |
| 08a| `08a_flow_lifecycle_initial.json`          | `task.command`            | task-orchestrator → (observers) |
| 03 | `03_task_execute_async.json`               | `task.command`            | task-orchestrator → task-runner |
| 04 | `04_task_event_waiting.json`               | `task.command`            | task-runner → task-orchestrator |
| 05 | `05_async_response_notification.json`      | `notification.management` | external system → pamconsumer |
| 06 | `06_task_signal.json`                      | `task.command`            | pamconsumer → task-runner |
| 07 | `07_task_event_completed.json`             | `task.command`            | task-runner → task-orchestrator |
| 08b| `08b_flow_lifecycle_completed.json`        | `task.command`            | task-orchestrator → (observers) |

---

## Scenario 3 — `miscellaneous` (SYNC)

A plain SYNC task. No WAITING, no signal — the task-runner does the
downstream work inline and publishes `COMPLETED` as its only
`task.event`. This is the reference scenario for a straight sync flow.

Correlation ID: `33333333-cccc-4ddd-8eee-000000000001`

| # | File | Topic | Producer → Consumer |
|---|------|-------|---------------------|
| 01 | `01_processflow_created_notification.json` | `notification.management` | TMF-701 → pamconsumer |
| 02 | `02_processflow_initiated.json`            | `task.command`            | pamconsumer → task-orchestrator |
| 05a| `05a_flow_lifecycle_initial.json`          | `task.command`            | task-orchestrator → (observers) |
| 03 | `03_task_execute_sync.json`                | `task.command`            | task-orchestrator → task-runner |
| 04 | `04_task_event_completed.json`             | `task.command`            | task-runner → task-orchestrator |
| 05b| `05b_flow_lifecycle_completed.json`        | `task.command`            | task-orchestrator → (observers) |

---

## Common — failure scenarios

| File | What it is |
|------|-----------|
| `task_event_failed_non_retryable.json` | `task.event` with `status=FAILED` and `error.retryable=false`. The `result.taskFlowResponse.state` is `failed`. The orchestrator will flip the barrier to FAILED and PATCH the parent processFlow to `state=failed`. |
| `flow_lifecycle_failed.json`           | `flow.lifecycle` event the orchestrator emits after a non-retryable task failure (`source=task-orchestrator`, `status=FAILED`). |

The correlation IDs in the common payloads use the `passwordPushV2`
flow's UUID, but you can adapt them to any scenario — the envelope is
DAG-agnostic.

---

## Envelope contract

Every message on `task.command` uses a uniform envelope (`TaskCommand`).
Consumers dispatch on `messageName` + `source`:

| `messageName`           | `source`              | Meaning |
|-------------------------|-----------------------|---------|
| `processFlow.initiated` | `pamconsumer`         | A new DAG execution is seeded from a TMF-701 processFlow. |
| `task.execute`          | `task-orchestrator`   | Command for the task-runner to execute a DAG action. |
| `task.event`            | `task-runner`         | Progress update for a single action (`status` ∈ {WAITING, COMPLETED, FAILED, CANCELLED}). |
| `task.signal`           | `pamconsumer`         | External system signal that unblocks a WAITING async task. |
| `flow.lifecycle`        | `task-orchestrator`   | Coarse-grained flow-level state change (INITIAL, COMPLETED, FAILED). Single authoritative producer. |

Kafka record key is always `correlationId` (the processFlow UUID) so
all messages for one flow land on the same partition and are processed
in order.

---

## `result` field shape (task.event)

The `result` on a `task.event` is an `ActionResponse` with two payload
slots:

| Slot              | Type                    | Contents |
|-------------------|-------------------------|----------|
| `taskResult`      | `Object` (free-form)    | Raw downstream response body — `JsonNode`, `Map`, or a domain DTO. Whatever the runner received from the downstream call. Used for `dependencyResults` pass-through and audit. |
| `taskFlowResponse`| TMF-701 `TaskFlow`      | Typed: `id`, `href`, `state`, `characteristic[]`. The orchestrator reads `href` here to PATCH the parent processFlow. Domain outputs (`outcome`, …) should also be emitted here as `characteristic` entries per TMF convention. |

---

## Conventions

- **Correlation:** each DAG scenario has its own `correlationId`
  (UUID). Every message in that scenario shares the same id; the
  embedded `inputs.processFlow.id` matches.
- **IDs:** `taskFlowId` prefixes indicate the DAG
  (`tf-pwp-*`, `tf-pwr-*`, `tf-misc-*`). Async downstream transaction
  IDs start with `ASYNC-`.
- **URLs:** all examples use RFC-2606 reserved hostnames
  (`*.example.com`). Replace with your real endpoints.
- **Null-exclusion:** payloads omit fields that are null
  (`@JsonInclude(NON_NULL)`). Consumers must tolerate absent optional
  fields.
- **Schema version:** all messages declare `schemaVersion: "1.0"`.

---

## Using these payloads

**For consumers:** pick the payload for the `messageName` you handle
and wire it into your test fixtures. The three async scenarios are
functionally identical shapes — use whichever reads clearer.

**For manual publishing** (`kcat` / Kafka CLI):

```bash
# publish a processFlow.initiated for the passwordPushV2 scenario
kcat -b $BROKER -t task.command -K: -P <<EOF
11111111-aaaa-4bbb-8ccc-000000000001:$(cat passwordPushV2/02_processflow_initiated.json)
EOF
```

The record key (`-K:` then `correlationId:json`) must equal
`correlationId` for partition ordering to hold.
