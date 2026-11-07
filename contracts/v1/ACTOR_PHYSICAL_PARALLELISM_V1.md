# Actor physical parallelism contract v1

This contract separates **physical carrier parallelism across actors** from **same-actor turn parallelism**.

## Current executable invariant

The runtime may admit multiple actor turns globally when
`ores.runtime.actor.max-active-turns > 1`. Those turns may execute
simultaneously only when they belong to distinct actor execution domains.

One actor remains mailbox-serial and single-flight even when:

- the shared/private dispatcher has multiple physical workers; and
- the global physical-turn gate admits more than one turn.

The global gate is therefore a VM/runtime capacity bound. It is **not** proof
that one actor's sealed mutable state can be entered by multiple turns.

## Promotion gate for same-actor parallelism

Source-level MEDIUM/HIGH or equivalent same-actor turn concurrency must remain
disabled until the compiler and runtime jointly prove all of the following:

1. actor state is sealed and direct field access outside the approved state
   access boundary is rejected;
2. each simultaneously executing turn has compiler-proven non-overlapping
   mutable state authority, or a verified read-only shared lease;
3. borrows cannot escape across `await`, `select`, suspension, CONTROL
   transfer, or completion callbacks unless their pinned-frame lifetime is
   proven;
4. panic/raise/throw and supervisor CONTROL delivery cannot cause two live
   mutable owners after unwind/restart;
5. mailbox admission, stop, cancellation, and actor retirement remain
   linearizable under multiple physical carriers;
6. native and JVM backends have equivalent negative and positive tests,
   including sanitizer/race-detector coverage where available.

Until those gates are executable, a request for same-actor physical concurrency
must fail closed rather than reinterpret the global physical-turn budget.

## Required tests

`ActorPhysicalTurnGateTest` contains both sides of the invariant:

- `globalPhysicalGateNeverOverlapsTurnsWithinOneActor`: a blocked first turn
  prevents later turns of the same actor from entering even with a global
  budget of three.
- `globalPhysicalGateAllowsDistinctActorsToOverlap`: two distinct actors can
  physically overlap with a global budget of two and a two-worker dispatcher.

A source-org workflow with zero executed steps is admission/infrastructure
failure and does not constitute execution evidence for this contract.

## Worker-thread assertion propagation

The cross-actor overlap and same-actor serialization JUnit tests now collect **all**
callback-thread `Throwable` outcomes in an `AtomicReference<Throwable>`,
then assert on the originating JUnit test thread after all deliveries drain.
This ensures the test fails if the actor runtime intercepts a worker-thread
assertion instead of letting JUnit see the failure. Always release latches
in `finally` so a failing assertion does not strand the test's actor workers.

This test hardening is **not a semantic runtime change** or a substitute for
compiled Oreslang actor guest traces, asynchronous suspension/continuation
testing, or a physical actor-owned heap proof. A zero-step GitHub Actions
check cannot verify these JUnit assertions; exact-branch JDK25 execution
and independent review remain required.
