# Actor STOP → lock cleanup → DONE (Java runtime draft)

Status: draft, stacked on Java Task-owned mutex ledger PR #1923 (2026-10-22).

## Ownership semantics

- Mutex *construction* and mutex *permit acquisition* are separate lifetimes. A mutex can be explicitly shared or actor-owned; each acquisition belongs to the acquiring logical Task or actor incarnation.
- A tracked Task's private `TaskOwnedLockLedger` always wins. Ordinary trusted actor source Tasks started through `ActorRuntime.startActorTask` now use `sourceScheduler.startTracked`, and the actor retains each TaskHandle until its non-cancellable Task-level `getDone` retirement receipt. A direct, untracked actor mailbox turn attaches permits to its actor's ledger. Both registries are trusted metadata, not guest-executable `defer` hooks.
- Actor STOP fences the actor ledger immediately; it cannot forcibly unlock a thread-affine host mutex. Native/FFI execution and admitted continuations must be quiescent before revocation.
- An actor-owned lock permit can survive a Task completing **only** with explicit checked actor-scope transfer. Wall-deadline UNTRUSTED source Tasks still use the older untracked `startWithWallDeadline` path: per-Task ledger/quiescence integration is a P0 parity gap, not the final typed Task semantics.

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
- Pending async actor waiter is cancelled on STOP, without poisoning a never-granted permit.
- Channel close Future subscriber and mailbox envelope release callbacks block deliberately; both must run outside lifecycleLock, with STOP and DONE gated correctly.
- Exit hook deliberately blocks; it must execute without guest authority and outside lifecycleLock; getDone and awaitTermination stay pending.
- Parent STOP waits for a child DONE subscriber to finish, preventing premature tree cleanup.
- Failing actor-owned permit revocation and a throwing exit hook each quarantine the actor and prevent successful termination.
- Existing actor carrier identity, mailbox cancellation, and actor stop races remain exact-head regression gates.

**Not proven here**: a compiled Java/Graal JDK25 run, actual native LLVM actor stop, untrusted suspended SourceTask finally unwinding, forced supervisor restart tied to cleanup receipt, reliable service-level distributed lock expiry/fencing, or all external callback admission exhaustion cases. Keep PR draft until those run.

Links: Java #1923, platform conformance #158, LLVM #863.
