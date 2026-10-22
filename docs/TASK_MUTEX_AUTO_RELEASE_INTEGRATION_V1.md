# Task/actor lock-owner retirement — guarded host integration v1

Status: **draft Java host-runtime implementation on top of draft PR #1887**.
This is NOT full Oreslang source lowering, actor-only turn registration,
distributed fencing, or LLVM/JS/Wasm/.NET parity. No exact-head JUnit or
GraalVM CI pass has been verified for this integration.

## Ownership and location

* **Mutex creation does not imply lock ownership.** A mutex may be created by
  one Task or actor and acquired by a different authorized execution scope.
* A successful acquisition is attached to the authenticated scheduler Task's
  `TaskOwnedLeases.Registry`. This is runtime-private metadata and need not
  be mirrored in a supervisor tree, ETS table, or process-group membership.
* A plain actor mailbox turn that does not run as a tracked Task is **not yet**
  enrolled automatically. That requires actor-epoch/turn-scoped registry
  installation and retirement in `ActorRuntime` and `SupervisorShutdownTree`.
* Local and shared mutex guards are not distributed locks. Cross-process locks
  require leases and fencing tokens; losing a process heartbeat may permit a
  newer owner, but does NOT guarantee the previous process stopped writing.

## Implemented host Java slice

1. `OresMutex.Local<T>` registers the acquired guard on the current tracked
   Task (not on creation). The hidden fallback release CAS-unlocks its
   nonparking gate after physical Task retirement. It is not a JVM monitor
   or thread-affine OS mutex. A failed lease-admission registration rolls
   back the acquired gate.
2. `OresMutex.Shared<T>` registers synchronous/try acquisitions as
   Task-owned guarded leases. Explicit owner release disarms retirement
   without poisoning; abandonment poisons and releases the permit.
3. `Shared.lockAsync()` registers the pending **request** on the requesting
   logical Task before enqueue. Its eventual grant may occur on an
   unrelated host thread. A handshake binds the guard before publication.
   Task retirement either cancels a pending waiter or poisons/releases the
   granted guard. Only one side can reclaim a racing handoff permit.
4. Guard data access, explicit release, and fail operations require the
   owning logical Task where a Task lease exists. The trusted physical
   retirement path does not require a thread-local guest context.
5. Early released tokens unlink in expected O(1) time. The quota is for
   simultaneously active acquisitions (max 4,096 per Task), not lifetime
   acquisition count. Historical early failures are bounded and still
   invalidate clean retirement.
6. Existing `TaskHandle.getDone()` waits until both the Task and its
   structured descendants physically retire; tracked leases retire before
   its outcome is published. An unlock failure makes `getDone()` Failed and
   appears in `cleanupFailures()` rather than fabricating success.

## Required safety ordering

```text
cancel / throw / raise / panic / success
  -> fence admissions and stop new work
  -> unwind guest callback and lexically scoped guards
  -> wait for current execution turn and owned children to retire
  -> fail-close any still-held tracked logical mutex guard
     [poison shared mutable payload; release at most once]
  -> dispose remaining pending lock queue entries
  -> publish Task.get_done / actor-done cleanup receipt
  -> allow supervisor restart or actor-memory reclamation
```

These ordering constraints are **not** a promise of bounded latency for
noncooperative FFI or native calls. Cancelling an observable Future is not
proof of stopped execution. Never call a Java monitor or `ReentrantLock`
unlock from a foreign runtime callback. Preserve actor arenas and leases
until physically safe or quarantine them and report incomplete cleanup.

A cancellation of a *waiter* that never acquired the mutex does not poison.
An abandoned *holder* poisons shared mutable state even on a successful
Task return that forgot to close the guard, unless later compiler
verification establishes a valid transaction/rollback invariant.
A recovery callback must execute under controlled exclusive admission.
No Task's arbitrary `defer` runs on the host lock notification thread.

## Remaining mandatory release gates

- **P0**: verified exact-head JDK25 Maven/JUnit, lock handoff stress, monitor
  reentrancy and failed-release fault injection. No passing test claim here.
- **P0**: wire actor-mailbox/actor-turn ownership registries into actor STOP,
  cancellation, crash, supervisor hook ordering, and arena finalization.
  Ensure actor logical generation and task identities are not conflated.
- **P0**: compiler emits guard acquisition/unwind before borrow/release
  lifetimes end; prove `await` rejects unsafe local/OS thread-affine loans.
  Bind checked `throws`/`raises`/`panics`, typed recover and cancel.
- **P0**: resolve `Shared` execution-domain vs logical-continuation identity
  (# related multi-carrier mutex audit). This host slice does not make
  parallel same-actor handler ownership generally correct.
- **P1**: LLVM native LogicalMutex/CriticalGate cleanup parity with generation
  fencing and TSan; model-check cancellation-vs-handoff and STOP/restart.
- **P1**: test starvation, bounded retention, lock-order deadlock and
  read/write loan revocation; tie Task clean receipts to supervisor shutdown.
- **P1**: use a separate strongly consistent lease/fencing manager for
  cross-process locks. ETS-style tables are fine for local state storage;
  pg-style group membership is not a substitute for a linearizable lock.

Related: Java #1887 task-owned host leases, Java #1911 supervisor shutdown,
Java #1572 child quiescence and actor arena issue #1566.
