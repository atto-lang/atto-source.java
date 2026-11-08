# Cooperative checkpoint security audit — 2026-11-07

**Scope:** Java/GraalVM source-loop safepoints, the ordinary UNTRUSTED
physical-turn quota, and persistent UNTRUSTED logical SourceTask quotas.
This is a source-level hardening change, **not** an executed multi-backend
certification or authorization to allow untrusted automatic suspension.

## Separate cost amortization from security accounting

* Trusted continuation-backed loops use a private `SourceCooperationQuantum`
  counter: increment once for each **committed** backedge, check only every
  64 completions with a power-of-two mask, normally read `nanoTime` only
  on those checks, and force a cooperation request at 512 completions.
  After 512, check **every** subsequent backedge until the scheduler actually
  releases the carrier. This is preferable to `counter % 100 == 0`: 64
  uses a simple bit mask, with a precisely bounded interval.
* A fast path is **not** permission to skip fuel, cancellation or deadlines
  for UNTRUSTED actors. The source loop calls `schedulerSafepoint()` at
  **every** UNTRUSTED backedge. Retain checks at ordinary statement, call,
  allocation, and other compiler-instrumented boundaries. A single
  backedge does not bound the cost of its body, host call or FFI.
* Distinguish `schedulerSafepoint()` (observation/revocation check) from
  `OresScheduler.cooperate()` (a real, heap-backed continuation handoff).
  A poll does not suspend a Java/Truffle stack, release a guard, or count
  as an actor turn handoff.
* The source counter belongs to the logical SourceTask, and dispatch
  reduction/time belong to the **physical dispatch**. Neither is the
  UNTRUSTED fuel/wall/CPU security quota. A source task's quotas do not
  reset when it awaits, cooperates, migrates or spawns an admitted child.

## Audit correction: exclusive deadline and saturating counters

In this patch, an UNTRUSTED task is **expired at** `elapsed >= budget`;
remaining-wall admission already used that condition, while the check
executed on the carrier formerly used `elapsed > budget`. CPU enforcement
now uses the same exclusive boundary. This removes a deterministic
one-tick disagreement between admission and execution.

The ordinary physical-turn fuel balance now saturates at zero rather than
decrementing into the negative range. The safepoint metric saturates at
`Long.MAX_VALUE`. The physical wall/CPU delta fails closed when signed
monotonic arithmetic returns a negative value; a wrapped delta cannot be
treated as a fresh quota. Existing persistent SourceTask fuel already
saturates at zero, and this patch does not change its cumulative accounting.

These changes deliberately **do not** make a watchdog a hard preemptor.
The 4 ms watchdog is advisory, and the 2 ms / 512 reduction quantum is
soft. The operating system, GC/JIT, time spent inside a long single
iteration, host callbacks and FFI can exceed these numbers. A hostile
non-cooperating native call requires independently revocable isolate /
process execution, not `Thread.yield()`, Thread interruption, or a
fabricated Java-stack continuation.

## Required invariants before UNTRUSTED automatic cooperation

1. Logical fuel must be charged per actual instrumented operation/backedge,
   not once per batch of 64 or 100; the quota must not be reset by resume.
2. Cancellation/shutdown and hard resource revocation must remain observable
   even if no loop backedge runs (e.g. recursion, allocations, blocking
   host calls). Reject unsupported non-polling execution in a claimed
   sandbox; a timer alone is not a physical guest-kill guarantee.
3. Only committed, continuation-lowered boundaries can publish Cooperate.
   Pending `defer`/`finally`, exceptions, borrowed values, held critical
   sections, mailbox single-writer admission, and actor-state capabilities
   must remain valid across handoff or preclude suspension.
4. One logical task uses one generation-fenced execution/actor identity.
   Another carrier may resume only after the previous physical dispatch
   exits; cancellation must not recycle memory, quotas or a task slot
   before both quiescence and terminal notification drain.
5. Carry wall start/deadline and accumulated per-carrier CPU debt across
   await/cooperate, including unrelated carrier CPU-clock origins.
   If CPU sampling is unavailable, do not claim enforced CPU quotas.
6. The compiler must instrument all reachable cycles, including backedges,
   irreducible CFGs, continuations, indirect calls/recursion, generated
   code and loop bodies without a natural counted backedge. Do not let
   optimization remove, hoist, duplicate, or move a safety check past
   externally committed effects. Verify this *after* optimization.
7. For Wasm, enforce engine fuel/epoch interruption or a revocable Worker/
   process boundary at the **host**, not only source-level polls. For
   LLVM/native, use an isolated process or similarly enforceable
   sandbox for hard termination. Do not claim Java/GraalVM hard RT.
8. For Forge, Rust, Wasm, Java, LLVM and .NET, require identical
   canonical abuse fixtures, negative mutations and executed guest
   receipts at exact source trees before upgrading a parity badge.

## New regression coverage

`UntrustedCheckpointBoundaryTest` deterministically probes exclusive
wall and CPU deadlines (including a CPU clock changing carrier origin),
fuel exhaustion over 32 attempted re-entries, clock-read amortization,
the frozen-clock 512 reduction cap, guard-delayed overdue polls and
fresh-dispatch reset. There are no sleeps or timing-race assertions.

Suggested focused test command:

```sh
mvn -Dtest=UntrustedCheckpointBoundaryTest,SourceCooperationQuantumTest,UntrustedLogicalQuotaTest test
```

**Evidence status:** source patch and test authoring only until exact-head CI
runs these tests, their step/job counts and exit codes are recorded, and
independent review accepts the change. Compiler-backed guest execution,
native-image/AOT, broad stress and cross-backend parity are
**NOT_EXECUTED / NOT_CERTIFIED** by this audit. The UNTRUSTED implicit-loop
cooperation gate remains **disabled**.
