# Actor STOP → lock cleanup → DONE (Java runtime draft)

Status: draft, stacked on Java Task-owned mutex ledger PR #1923 (2026-10-22).

## Ownership semantics

- Mutex *construction* and mutex *permit acquisition* are separate lifetimes. A mutex can be explicitly shared or actor-owned; each acquisition belongs to the acquiring logical Task or actor incarnation.
- A tracked Task's private `TaskOwnedLockLedger` always wins. Ordinary trusted actor source Tasks started through `ActorRuntime.startActorTask` now use `sourceScheduler.startTracked`, and the actor retains each TaskHandle until its non-cancellable Task-level `getDone` retirement receipt. A direct, untracked actor mailbox turn attaches permits to its actor's ledger. Both registries are trusted metadata, not guest-executable `defer` hooks.
- Actor STOP fences the actor ledger immediately; it cannot forcibly unlock a thread-affine host mutex. Native/FFI execution and admitted continuations must be quiescent before revocation.
- An actor-owned lock permit can survive a Task completing **only** with explicit checked actor-scope transfer. Both ordinary trusted and UNTRUSTED wall-deadline source Tasks now use `TaskHandle` and private logical Task lock ledgers; the wall-time quota, physical quiescence callback and terminal-notification quota callbacks remain in the untrusted scheduler admission path. Typed guest lowering and callback failure tests are still open.

## Lifecycle order (linearization)

1. Under ActorCell.lifecycleLock: mark STOP/sealing, fence actor lock admission, close mailbox admissions, capture pending Futures, drain waiters and children; increment the in-flight STOP effects barrier.
2. **Outside** lifecycleLock: close both mailbox channels (which may synchronously run ChannelRuntime Future subscribers), drain admitted envelope release callbacks, fail drain waiters, cancel pending operations and propagate cancellation to children. A release/cancel callback failure quarantines the actor. Decrement the STOP effects barrier under the monitor, then attempt finalization claim.
3. Claim finalization once, under lifecycleLock, only when `activeTurns=0`, no scheduled ticket, `carrierActive=false`, no children and no pending STOP effects.
4. Outside the monitor on a host finalizer: shut down and physically retire source-scheduler Tasks, await tracked source TaskHandles' individual `getDone` receipts, initiate actor-ledger revocation, await every asynchronous physical handoff receipt **without blocking an actor carrier**.
5. On proven clean settlement: revoke shard/snapshot leases, invoke actor exit hook, release owned memory, unregister actor, publish ready/DONE and the host terminal latch. Notify the parent that its child has retired **only after** the child's terminal subscribers and latch have completed; otherwise a parent could reclaim its resources ahead of a child's live callback.
6. On cleanup failure: set terminal diagnostic, **keep actor registered and its backing arena/loans retained**, never forge successful getDone/awaitTermination. Runtime `close()` must also fail before invalidating global Shared/SyncCell/ActorGroup capability registries while any actor is unfinalized. A supervisor must quarantine rather than restart against possibly-live state.

## Constraints and tests

No user `defer` / `recover` / `tear_down` execution is required to revoke permits. Those may run during ordinary guest unwinding, but the trusted runtime ledger remains authoritative.

`ActorRef.awaitTermination` waits on a per-incarnation terminal latch; absence from `actors` is not proof that physical cleanup or observers finished. Generation-specific identity prevents stale completion being accepted as the replacement's own receipt.

Tests added in `ActorLockStopRetirementTest`:
- Direct actor leaks writer guard; STOP revokes it, poisons shared state, and DONE comes after cleanup.
- A compiler-started trusted source Task leaks a guard; its own Task ledger revokes and poisons **before** actor STOP (the actor stays alive).
- A wall-deadline Task's own ledger revokes an unreleased mutable guard, while its physical and notification quota callbacks execute exactly once; a suspended deadline Task stopped by scheduler closure is revoked after physical quiescence.
- Pending async actor waiter is cancelled on STOP, without poisoning a never-granted permit.
- Channel close Future subscriber and mailbox envelope release callbacks block deliberately; both must run outside lifecycleLock, with STOP and DONE gated correctly.
- Exit hook deliberately blocks; it must execute without guest authority and outside lifecycleLock; getDone and awaitTermination stay pending.
- Parent STOP waits for a child DONE subscriber to finish, preventing premature tree cleanup.
- Failing actor-owned permit revocation and a throwing exit hook each quarantine the actor and prevent successful termination.
- Existing actor carrier identity, mailbox cancellation, and actor stop races remain exact-head regression gates.

**Not proven here**: a compiled Java/Graal JDK25 run, actual native LLVM actor stop, untrusted suspended SourceTask finally unwinding, forced supervisor restart tied to cleanup receipt, reliable service-level distributed lock expiry/fencing, or all external callback admission exhaustion cases. Keep PR draft until those run.

Links: Java #1923, platform conformance #158, LLVM #863.

## Formal STOP/restart contract

The executable bounded Java model `FormalActorStopRetirementModelCheckTest`
explores all reachable interleavings of carrier exit, cancellation/STOP-effect
drain, descendant completion, source-Task completion, physical lock handoff,
and synchronous Future callback drain. It requires
`RUNNING -> STOPPING -> QUIESCING -> RETIRING -> TERMINATED`;
an exceptional host-stage dispatch, cleanup callback, or physical receipt leads
to `QUARANTINED` instead of successful termination.

The supervisor can activate a new incarnation only after `TERMINATED`, with
all receipt bits clear. A stale generation and duplicate STOP are idempotent.
The model passed four standalone local Java 21 checks (107 reachable model
states); that evidence is **only about the bounded model**. Concrete Java
GraalVM, source lowered `await`, child-native loan lifetimes, restart
admission, and LLVM parity still require executable refinement tests.

The runtime now wraps virtual-thread dispatch and all chained retirement
stages so host scheduling/continuation failures are captured in the actor's
irreversible quarantine diagnostic, rather than disappearing into a callback
while `finalizationStarted` remains permanently true. A separate JUnit
fault injection asynchronously fails the actor lock-retirement witness; the
actor must not publish DONE or release its quarantined backing memory.


## Runtime refinement: actor registry and Task notification drain

A public `OresFuture` terminal CAS is observable **before** its already-registered
synchronous host subscribers return. Therefore an actor must not use Task
`getDone().isDone()`, or the beginning of its own subscription, as proof of
physical terminal callback completion. Each tracked `TaskHandle` now exposes
a runtime-only **read-only `doneCallbacksDrained()` witness**, triggered by
`OresFuture`'s final notification hook. The actor retains that Task handle
until this witness settles, and its source-Task STOP join waits on these
witnesses. Observers receive `minimalCompletionStage()`, not the mutable
underlying `CompletableFuture`.

For the actor's own terminal notification, group-binding cleanup still
precedes public DONE, but registry removal and actor-quota release now occur
**after** synchronous terminal subscribers return, before the incarnation's
termination latch and parent child-finalization notice. This keeps
`ActorRuntime.close()` and successor admission from misinterpreting a removed
registry entry or recycled actor slot while an old callback is still active.

Deterministic JUnit regressions cover a blocked Task `getDone` subscriber
during actor STOP, a blocked actor DONE subscriber while the registry is held,
and attempted cancellation of a Task drain observation. The local source-free
Java 21 read-only receipt/callback model passed 500 iterations; it is **not**
a GraalVM/JDK25 integration result. CI and native parity remain required.
