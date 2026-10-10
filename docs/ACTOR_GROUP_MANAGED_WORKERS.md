# ActorGroup owned workers and spawn readiness

## Contract

- `spawn WorkerActor()` creates the actor identity and immediately returns
  `ActorRef<WorkerActor>`. Startup runs on the actor dispatcher. A handle is
  **not** evidence that `on_start` has succeeded.
- `await spawn WorkerActor()` returns that **same ActorRef type** after
  initialization succeeds. A constructor/`on_start` failure rejects the await.
  It never waits for the actor's lifetime to finish.
- `await worker.ready` has the same readiness barrier; `await worker.done`
  waits for finalization. The existing non-await spelling remains valid.
- No synchronous waits are introduced on actor carrier threads. Await yields
  its continuation through the existing Future/scheduler boundary.

## Membership and ownership are distinct

A group maintains cooperative `members` (group join authorization and
event-bus access) separately from its `workers` (strong references to
group-owned actor handles). A merely joined actor is **not** terminated by
group closure. A managed worker is a member and is gracefully stopped by
`group.close()`; lifecycle supervision is distinct from independently owned
actor heaps, isolation domains and dispatchers.

Java host API:

```java
ActorRuntime.ActorGroup group = runtime.createActorGroup();

// Immediate handle, no startup waiting.
ActorRuntime.ActorRef<String> worker = group.spawnWorker(
    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
    ctx -> (message, turn) -> { });

// Optional readiness barrier preserving the handle type.
OresFuture<ActorRuntime.ActorRef<String>> pending = group.spawnWorkerReady(
    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
    ctx -> (message, turn) -> { });

// Adopt the ActorRef returned by ordinary spawn, including a ref that
// has already finished its on_start readiness barrier.
group.manage(existingWorker);
group.workerRefs();        // manager-only snapshot
group.workerCount();       // managed workers (not all cooperative members)
group.workersDone();       // Future<Void>, snapshot-wide nonblocking drain
group.releaseWorker(worker); // relinquish ownership without stopping worker
group.stopWorkers();       // asynchronous stop request; does not park carriers
OresFuture<Void> finished = group.closeAsync(); // nonblocking close + terminal barrier
group.closedDone();        // observe finalization after close()
group.close();             // fences admissions and stops owned workers only
```

The source language currently supports `spawn`, `await spawn`, and
`ActorRef.ready/done`. The **ActorGroup management methods above are Java
host-runtime methods**; native source-level `group.manage(...)` lowering and
a source-exposed ActorGroup type are still required for guest-level API parity.

## Required lifecycle invariants

1. Admission and group close serialize on the group authority lock. Group
   ownership cannot appear after close is published.
2. Actor callers may manage only self or structured descendants; host
   supervisors may explicitly adopt any actor in their own runtime. Exactly
   one group may hold lifecycle ownership of a worker at a time, even though
   multiple cooperative memberships are allowed.
   Cross-runtime handles are rejected.
3. An actor constructor failing before readiness propagates the same failure
   through `ready`/`await spawn` and later `done`.
4. The group drops managed references on actor retirement, including startup
   failures; `close()` drops remaining refs and requests nonblocking stops.
   `releaseWorker()` permits explicit ownership handoff while leaving
   cooperative membership unchanged.
5. `stopWorkers()` and `close()` operate only on owned workers. A joined
   cooperative member is not silently stopped or given lifecycle authority.
   A committed stop request fences `releaseWorker()`: lifecycle ownership
   cannot be transferred after shutdown has been admitted. During
   `group.close()`, runtime-wide ownership remains reserved even after the
   group has cleared its worker references, until the worker's finalization
   removes the reservation. This closes the re-adoption window between
   group-close admission and the actual stop request.
6. Cancellation/stop before ready fails the readiness barrier. `done` is a
   distinct finalization signal. Shutdown/await must not block a carrier.
   `workersDone()` observes a worker snapshot while a group is open; after
   close it returns the persistent `closedDone()` barrier, not a spurious
   success merely because the closed group's worker map was cleared.
   `closeAsync()` initiates stop and returns that same noncancellable future.
   Do not await your own group's finalization from an actor it needs to stop.
7. Groups are not independent heap/isolate GC domains and cannot bypass
   current runtime actor isolation or message ownership rules.

## Remaining integration gates

- Expose typed ActorGroup worker-management operations to native Oreslang
  source and statically enforce ownership + capability rules there.
- Add optional fail-fast/restart supervision policy and graceful shutdown
  deadlines. The current `workersDone()` aggregates a fixed worker snapshot,
  not dynamically spawned workers after the call or nested grandchildren.
- Exact-source GraalVM validation on the final implementation revision:
  **116 focused Java tests + 295 broader Java tests**, zero failures and
  errors, at https://github.com/athlet-o/athleto-e2e/actions/runs/38093763352 .
  The funded mirror verified 74 exact Git source/test blobs, and every
  mirrored Java class/test file matches the PR's source tree. Validation
  covers ActorRuntime, ActorStructuredCancellation, ActorGroupMailman,
  ActorReadinessLifecycle, ActorGroupManagedWorkers, SourceActorSpawnExecution,
  and the additional language/compiler regressions available in that mirror.
- Run broader/full Java integration regression/CI on the latest head, and
  differential native source-to-runtime tests once LLVM source actors are lowered.
