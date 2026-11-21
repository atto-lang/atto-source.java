# SHARED actor initial-spawn retirement quarantine audit — 2026-10-19

**Status: DRAFT.** This is a narrow safety correction to the trusted host
SHARED actor lifecycle. It does not complete global checkpoint/code/scheduler
publication, isolated workers, native AOT code unload, or production CI.

## Concrete bug and second-order race

LogicalSourceActorRouterV1.spawn() previously acquired the generation route
pin, spawned an executable SHARED actor, awaited readiness, then on any
exception ran `physical.stop()` and immediately `generationLease.close()`.
A stop timeout or failure does not establish physical finalization. This
could retire generation lifetime authority while code still executes.

A first quarantine implementation registered the lease before admission and
allowed a global reaper to reclaim entries with no physical ActorRef. A
concurrent reaper could therefore see an in-progress initial spawn *before*
its physical ref had been assigned and wrongly debit its generation pin.
This was caught by a new race probe during this audit and corrected BEFORE
reporting the implementation complete.

## Hardening in the integration branch

- Each initial spawn preregisters a bounded supervisor-owned FailedInitialSpawn
  entry (capacity 1,024) BEFORE any physical spawn is attempted. A failure to
  reserve that slot occurs before actor execution and releases its unused pin.
- A successful fully ready spawn removes the pending entry and transfers the
  live route lease to its returned LogicalSourceActorRouterV1.
- An unsuccessful ready/spawn attempt requests stop, then releases the pin
  only when ActorRuntime.physicallyFinalizedForHotUpgrade() proves finalization.
  Stop timeout/errors retain the registered owner for a supervisor retry.
- A `volatile retryableFailure` handoff bit is published ONLY after failed
  spawn cleanup. The reaper ignores provisional reservations and cannot cancel
  or debit an actor that is still starting normally.
- The public trusted-host `reapFailedInitialSpawns()` snapshots candidates
  and retries nonblocking cancellation OUTSIDE the global quarantine monitor,
  so a synchronous cancellation subscriber cannot hold the global admission
  registry hostage. Reaping is under the monitor and retains failed pin debits
  for retry, rather than treating requested cancellation as physical exit.
- This is an explicit emergency retention ledger, not deterministic GC.
  Supervisors must retry outstanding quarantine entries. Uncooperative guest
  carriers remain pinned; trusted hot upgrade never force-unloads their code.

## Real-source JUnit (committed but NOT EXECUTED)

`LogicalSourceActorInitialSpawnQuarantineTest` includes TWO
package-level fault-injection tests:

1. A real compiler-linked SHARED source actor plus a second route lease:
   the provisional owner cannot be reaped or cancelled; after explicit
   failed-owner transfer, actual physical finalization is required;
   generation-pin accounting underflow preserves the record; after repair,
   the extra lease retires exactly once.
2. A newly registered startup reservation with NO physical ActorRef:
   a concurrent reaper must NOT reclaim this still-in-progress admission,
   even though physical is null. After an explicit failed-startup handoff,
   the otherwise unused generation lease may be safely released. This is
   the exact case that exposed the original second-order reaper race.

**Limitation:** The JUnit fixture injects a failed-spawn owner into the
registry; it does not itself force the `spawn().getReady()` error path.
That full-stack path still requires an exact-head live JVM integration
fixture and real CI execution.

## Executed independent Java 21 test

The source-free standalone
`/mnt/data/oreslang_initial_spawn_quarantine_audit/FailedInitialSpawnOracle.java`
compiled with `javac --release 21 -Xlint:all -Werror` and passed TWO
consecutive executions of **231,000 assertions each**:

- 15,000 live actor / delayed physical exit / cancellation retry scenarios;
- 10,000 failed generation-pin debit, retained-owner and retry scenarios;
- 10,000 pre-admission bounded capacity / successful handoff scenarios;
- 1,000 four-reaper concurrency rounds;
- 1,000 deliberate blocking-cancellation callbacks racing new reservations.
  New reservation must complete while the callback is blocked, and a
  provisional owner must not be reaped before failure handoff.

An earlier version of the test oracle FAILed at the provisional-owner
race, which exposed the bug. The corrected production implementation and
the corrected model then passed twice. This is an independent model,
not exact production ActorRuntime/GraalVM execution.

## CI and confidentiality

At audited head `bc716304ac90e04bd872081e7ca12f1631f71373`,
GitHub fast-ci run 39316623008 completed failure with ZERO executed
steps. New JUnit tests remain NOT_EXECUTED.

`.github/private-ci-approved-repositories.txt` on protected `main`
contains comments only and no approved destinations. Platform-conformance
security incidents #22, #77, runner policy #69, and source repo #962 remain
blocking. Available Actions minutes in another organization do not grant
authorization to copy or expose the private Oreslang repository. No
cross-organization source mirroring was performed.

## Follow-on P0 gates

- Exact-head approved JDK25/Maven/GraalVM startup timeout, stop-fault,
  initialization-failure, TaskScope, physical receipt and hot-upgrade suite.
- Explicit supervisor ownership and cleanup of any ActorRuntime spawn
  that throws *after* physical registration but before returning an ActorRef.
- One global code image/state checkpoint/scheduler/route/ABI publication
  authority, including postcommit quarantine and physical external leases.
- PRIVATE/UNTRUSTED isolation and LLVM native parity only after these gates.

Keep PR #1367 Draft.
