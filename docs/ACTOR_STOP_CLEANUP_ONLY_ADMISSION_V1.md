# Actor STOP cleanup-only SourceTask lane (Java draft)

Date: 2026-10-22. **Status: draft; exact-head JDK25/JUnit execution NOT verified.**

Stack: Java SourceTask cancellation #1887, mutex grant retirement #1926/#1933,
this draft. Companion native handoff substrate #869 is not yet connected to
the actor STOP implementation here.

## Lifecycle contract staged in this branch

1. STOP/cancel/failure seals the ordinary actor mailbox and generic Future
   continuation channel. Already-admitted source scheduler commands are held
   in an independent, bounded, runtime-only control mailbox.
2. Under the actor lifecycle monitor STOP atomically seals SourceTask
   admission using the scheduler admission lock and captures the currently
   admitted Task snapshot; this phase invokes NO Future callbacks.
   A cancellation-snapshot barrier blocks actor finalization. STOP then
   requests cancellation of the snapshot only AFTER the actor lock is released.
3. Only after cancellation requests are issued may the actor dispatch saved
   scheduler SourceTask commands in STOP cleanup mode. Ordinary messages and
   generic Future completion callbacks remain denied. The preserved source
   commands are tied to the existing ActorCell/ActorId and never transferred
   to a replacement incarnation. A source turn dequeued BEFORE STOP is
   already admitted and must not be discarded as a stale queued command.
4. CleanupAwareTask cancellation returns to its saved lexical continuation.
   Deferred cleanup can itself await an asynchronous future and execute actor
   scheduler safepoints only while carrying the authenticated cleanup-turn
   marker. The actor done barrier remains pending across that suspension.
5. The scheduler reports physical quiescence only after its live Task set and
   all in-flight task-retirement callbacks have settled. The actor additionally
   joins the UNTRUSTED task count and timed terminal-subscriber admissions.
   Pending work defers actor registry removal, memory-slice close, and done.
6. Any rejected source cleanup admission, scheduler incomplete cleanup, or
   failed physical retirement callback must quarantine rather than publish
   a clean actor-done or reclaim its arena.

The source continuation lane does not introduce a new worker pool, OS thread,
guest-visible supervisor, mutable sharing mechanism, or memory layout.
It preserves existing per-actor single-writer execution; Task identity and
lease/handoff receipts remain independent of AoS/SoA/AoSoA/SoAoS/ECS layout.

## Regression matrix committed (NOT YET EXECUTED)

`ActorStopCleanupAdmissionTest`:
- STOP/cancel a shared actor while its SourceTask holds a mutex and awaits a
  borrowed producer, then block its cleanup on a SECOND asynchronous future.
- Assert the actor remains non-finalized, the guard remains physically held,
  ordinary Future continuation admission and fresh Task admission are denied.
- Settle the async cleanup, require exactly-one guard release, no spurious
  poison, correct cancellation cause, and a successor actor with a new ID.
- Cancel a queued SourceTask before first dispatch while the enclosing actor
  turn is blocked: no guest start, no lost queued command, actor eventual DONE.
- Crash a running actor after a SourceTask suspends with a mutex guard. Require
  original failure preservation, post-crash asynchronous cleanup, physical
  release before done, and no unintended guard poison.

The existing task/mutex and actor structured-cancellation suites are included
in the focused exact-head matrix, for Java and GraalVM-native carrier options.

## Remaining merge gates / limitations

- Complete JDK25/GraalVM compilation and run JUnit on an **authorized** runner.
  Previous Actions submissions produced zero job steps; such failures do not
  establish either test pass or test failure.
- Inspect scheduler-vs-STOP races under deterministic dequeue barriers and
  sanitizers (where applicable); exercise repeated STOP, actor crash, async
  defer exceptions, forced quota expiry, oversized cleanup queues, and
  fault-injected physical-receipt callbacks.
- Make arena loan receipts and TaskHandle-owned mutex physical settlement
  first-class supervisor joins, including poisoned state on unrecoverable
  abandoned mutable locks. This branch only joins scheduler and untrusted
  admission receipts, not all native I/O/FFI/arena loans.
- Add real *source-syntax* `defer`/async iterator closing with actor STOP
  and compare Java/LLVM traces; host-level CleanupAwareTask tests do not prove
  parser/lowering parity. Typed `raise`/`recover` is also pending.
- Define STOP cleanup wall/fuel budgets and failure reporting without ever
  treating a timeout/quarantine as a successful cleanup receipt.
- Resolve actor `self.endWithCleanup` ordering relative to suspended
  SourceTask lexical cleanup in guest conformance tests.
- Do not use cross-organization private source Git-tree mirroring without
  independent security approval and a protected destination allowlist.
- Keep the PR draft/unmerged until those gates have passing evidence.
