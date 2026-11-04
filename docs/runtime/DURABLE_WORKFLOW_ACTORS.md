# Durable actor runtime seam — AI dev loop (draft)

**Status:** implementation plan, NOT wired to guest actor execution. No exactly-once external side-effect guarantee.
**Related contract:** `oreslang-common-source/docs/DURABLE_WORKFLOW_ACTORS_V0.md`.
**Conformance model:** `oreslang-platform-conformance/src/durable-dev-loop-model.mjs`.

## Existing Java/Graal boundaries to reuse

- `ActorRuntime.java`, `ActorMailman.java`, `CooperativeActorMailman.java`: mailbox admission, execution lease and continuation scheduling. Durable messages must be admitted without allowing host I/O threads to invoke guest handlers.
- `ActorHeapAdmission.java`: snapshot only explicit serializable actor-owned values at safe suspension boundaries; do not share mutable guest heaps or serialize host pointers.
- `ActorEventBus.java`: events are in-process notification, **not** the durability log. A durable event store is a separate boundary.
- `HotReloadManager.java`: pin actors to a code generation; require an explicit migration for incompatible saved state. Never deserialize old-generation handles into a different isolate.
- `NativeHttp.java`: webhook ingress validates authentication, persists event delivery and acknowledges promptly; request-exchange ownership is not serialized.
- `OresContext.java`: activation, shutdown and restore hooks must respect runtime teardown ordering.

The current actor-wide lease covers initialization, receive, timers, continuations and cleanup; frozen durable actors must obey the same rule. No active guest handler or borrower may remain when the arena is reclaimed.

## Incremental implementation plan

### Phase A — host-side storage contract (no new guest keyword)

Introduce a pluggable `DurableActorStore` interface separate from `ActorRuntime`. Define:
- immutable `ActorAddress(tenantId,workflowId,runId,nodeId)`;
- `activate(address)` that advances a monotone storage-enforced epoch and returns a lease;
- `enqueue(address,deliveryId,payload)` idempotently, including when the actor is dormant;
- `commitTurn(address,epoch,expectedVersion,deliveryId,newState,events,outbox,timers)` with one atomic transaction, fencing, and expected-state-version CAS;
- `hibernate(address,epoch,snapshot,checkpoint)` only after the scheduler confirms quiescence;
- `load(address)` that restores a versioned envelope or quarantines incompatible snapshots;
- an outbox dispatcher whose network effects are outside the database transaction and require durable operation receipts.

Do **not** use `ActorEventBus` alone as the source of truth. A volatile publisher can drop events during a process crash.

### Phase B — scheduler adapter

Implement `DurableActorActivator` to map stable addresses onto ordinary actor mailboxes. It must:
- take and release the same actor execution lease as normal messages, never start two carrier invocations;
- bound dormant wake admission; use quotas per tenant and per workflow;
- synchronize freeze with handler/continuation quiescence and cancellation;
- prevent stale activations from committing to state or outbox;
- avoid one Graal isolate per actor; code-generation and trust-domain policy remain unchanged.

A suspended `await` is not necessarily safe to snapshot. Model in-flight I/O/AI/Git jobs as explicit durable operation state rather than arbitrary continuation serialization.

### Phase C — typed guest-facing library

Create optional `stdlib/workflow` facilities such as `DurableActor<State>`, `WorkflowNode<In,Out,State>`, `WorkflowActor<State>`, `WorkflowSupervisor`, and `WorkflowRef`. These are library-level actor bases or typed traits, **not** new core language keywords. Do not add JSON, SQL, HTTP, LLM, or Git dependencies to freestanding `core`.

Guest hooks should receive typed `on_input`, `on_resume`, `on_cancel` signals, and commands should be delivered by mailbox. Workflow graph edges and loops are durable routing definitions rather than native `pipe` stream replacements.

### Phase D — GitHub dev loop and UI

`TicketSource -> CodeEditor -> GitPush -> GitHubWebhook -> TestRunner -> [BugReporter -> CodeEditor | ReviewGate]`.

The GitHub webhook supplies untrusted event fields; validate HMAC, installation/repository, ref, run, iteration and the **expected SHA pinned in persisted state**. Do not compare two values both supplied by the webhook. An old push/webhook/test result may be stored as an audit event but cannot advance a newer iteration.

Render a versioned, redacted actor projection via HTMX, authenticate command POSTs and SSE subscriptions, and send full snapshots on reconnection. The browser never owns execution state or secret capability tokens.

## Acceptance tests before calling this durable

1. A crash before commit leaves the inbox message unconsumed; a crash after commit does not duplicate the logical transition.
2. Concurrent activators with old and new epochs cannot both commit, even across process failures.
3. Freeze under load retains durable inbox and outbox; no guest callbacks execute after arena reclamation.
4. Side effects reconcile from receipts, with bounded retries and no claim of exactly-once GitHub writes.
5. Late/duplicate/wrong-ref webhook and old-SHA CI notifications do not advance the run.
6. Code-generation mismatch and corrupted snapshots quarantine the actor instead of guessing.
7. Cross-tenant attempts fail and are not exposed through HTML/SSE.
8. The dev loop stops at budget or human-review gate; no automatic merge/deploy.

## Open decisions

- Pick concrete Postgres storage driver / SQL dialect in the optional adapter, not the compiler core.
- Define canonical snapshot codec and backward-compatible schema upgrade procedure.
- Define the lifecycle public API naming (`freeze` vs `hibernate`) after testing the scheduler boundary.
- Decide how actor logs and large artifacts are stored without embedding them in state snapshots.

**Do not represent this document as completed Java runtime support.** This PR is intended to pin safe integration seams for incremental implementation.
