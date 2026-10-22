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

## Physical layout and ECS design constraint (planned, NOT implemented here)

Runtime lock ownership, waiter admission, supervisor child metadata, and
Task/actor lifetime bookkeeping MUST be designed for **AoS, SoA, AoSoA,
SoAoS, and ECS-style iteration** from the outset. Layout is a *physical
storage choice*, not a change to ownership, fairness, or shutdown semantics.

- **AoS**: compact individual control records for low cardinality,
  mixed-field access, and uncommon/cold paths.
- **SoA**: columnar hot fields for batch owner cancellation, lease expiry
  scans, active-generation validation, and supervisor/Task state sweeps.
- **AoSoA**: bounded, cache-/SIMD-friendly tiles for high-cardinality
  sharded scheduling, wait queues, and repeated per-batch state transitions.
- **SoAoS**: a separately specified nested/blocked layout option when
  grouped records and field-wise subscans both matter. Define its precise
  indexing/packing ABI before implementing it; do not silently treat the
  abbreviation as interchangeable with AoSoA.
- **ECS**: permit entity/component indexing and batch system iteration over
  large collections of logical actors, Tasks, mutex leases, and generations,
  WITHOUT forcing ordinary Oreslang programs, actors, or Tasks to use ECS.

Use one authoritative physical representation per store at a time, with a
layout-selectable `LayoutStore<T>` / `InvertedArray<T>`-style abstraction
where suitable. **Never** maintain four synchronized mutable copies of
the same lock graph. Hot storage may include `OwnerId`, `ActorId`,
`TaskId`, `MutexId`, incarnation/lease generation, guard mode, lease
state, waiter head/tail or index, and cleanup sequence; cold diagnostic
data may be stored separately. These are examples for benchmarking, not
a frozen memory ABI.

Expose stable, generation-checked opaque handles instead of raw interior
pointers. Layout conversion, compaction, and row relocation must never
invalidate live leases, permit stale handles to unlock recycled slots,
alter per-owner LIFO unwind order, or reorder linearized lock grants.
Require a freeze/quiescence/epoch protocol (or defer relocation) whenever
read/write borrows or protected callbacks remain active. Actor-private
arenas remain isolated; a shared index of handles does NOT confer a
capability to read an adjacent actor heap or bypass actor ownership.

Cross-backend requirements: avoid per-lease Java object/boxing overhead
where generated off-heap metadata accessors suffice, preserve LLVM/Java/
Wasm memory and alignment constraints, and keep source semantics independent
of the chosen layout. Benchmark memory per live lease, cache misses, lock
handoff latency, batch cancellation cost, contention/fairness, and layout
conversion against AoS baseline. Differential/model-check tests must show
that each implemented layout produces identical safety/lifecycle traces
through cancel, throw, raise, panic, STOP/restart, generation rollover,
poison/recover, and concurrent async handoff.

This PR implements Task ownership integration, **not** these additional
physical layouts or ECS storage; they remain explicit engineering gates.

## Post-v1 hardening: logical owner and async failure accounting

This draft branch now uses a per-execution **reference-identity owner key**
in `OresMutex.Shared`, rather than the stable actor-wide domain or
value-based equality of `ActorExecutionContext` records. Tracked scheduler
Tasks retain the same underlying execution identity over await/carrier
migration; untracked actor dispatches use their distinct current physical
turn context; ordinary host calls use the current carrier identity. This
key is *not* an ownership capability for reading a foreign actor arena.

**Actor-domain authorization is independent of logical Task ownership.**
A `SharedGuard` acquired during actor execution captures the requesting
actor's stable execution domain separately from the Task/turn owner. Both
must match for guard access/release, even if the actual async permit was
granted on an unrelated scheduler/host carrier. The runtime-only abandoned
cleanup path bypasses guest authorization only after physical retirement.

Async acquisition reserves the logical owner and waiter quotas before
admitting its Task-owned lease; a failed admission rolls back the earlier
reservations. A request that ends with no grant disarms its lease without
running any permit-release callback. A grant that loses Future completion
must reclaim its permit first, then disarm the undelivered lease. Abandoned
*delivered* holders still follow the poisoned-release path. Notification
threads do not run guest `defer`, recovery, or actor callbacks.

New **unexecuted** JUnit regressions cover: distinct logical Tasks
sharing a single scheduler carrier, more than 4,096 zero-timeout async
requests in one Task, recursive admission rejects without leaking Task
leases, equal-valued actor turn records remaining distinct lock owners,
disarming an ungranted lease without touching its release callback,
and refusing a foreign actor access to a still-live guard.
These complement the original eight Task-owned guard regressions; passing
results are not claimed.

Future SoA/AoSoA layout work should replace this allocation-heavy host
owner-key wrapper with a **stable generation-checked numeric handle**,
maintaining the same identity and compare-and-set invariants. The current
wrapper is a correctness staging bridge, not a physical layout commitment.

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
