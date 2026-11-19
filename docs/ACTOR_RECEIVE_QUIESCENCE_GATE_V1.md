# Source actor receive-quiescence gate — first Java runtime slice

**Status: implemented on stacked draft PR #3090; NOT source guest concurrency certification or full project test evidence.**

This feature stacks on Java source STOP fence PR #3084. It hardens the real
`ActorRuntime` source actor receive path; the runtime still has a single
physical carrier turn per actor (`activeTurns == 1`). AttoLang's specified
**concurrent-by-default** receiver execution is a separately required compiler
and scheduler migration. Do not describe the two-receiver host test as an
executed concurrent `.atto` guest test.

## Admission and physical retirement

- `ActorSourceLifecycleGate.enterReceive` atomically checks owner/type/mailbox
  identity and READY status, then mints one private `ReceiveLease`; an external
  STOP linearizes against the same gate monitor. A failed admission retains no
  token and no quota.
- The production `ActorCodeExecutor.receiveActor` invocation is bracketed
  with an explicit `finally { receiveLease.close(); }`. Synchronous guest
  normal/failure exits cannot leak the receive receipt. Double `close` is inert.
- STOP immediately seals new source mail, but it does not revoke a receiver
  already admitted. Graceful `beginTeardown` requires zero active receive
  receipts and is at most once per incarnation.
- An actual `tear_down` callback sets a separate active lifecycle pin.
  `finishTeardown` must run in `finally` on both normal and exceptional
  exits; cancellation after teardown begins cannot make native memory safe
  while the callback is still executing.
- Both `ActorSourceLifecycleGate.close` and the actual
  `ActorRuntime.finalizeStopLocked` fail closed while receives or teardown
  hold a pin. The existing carrier, source scheduler, active Task,
  child/supervisor, native I/O loan and sealed-state gates still apply.
- The current receive lease is dropped when a synchronous
  `receiveActor` dispatch returns. This does **not** prove safety for a
  suspended receive on its own; the source scheduler's physical
  cleanup/continuation pins are separately mandatory. Future concurrent
  source lowering must hold invocation leases across suspension and reentry
  under a distinct invocation/actor-generation identity.

No new tracing garbage collector, per-actor worker, periodic resource
sweeper or unbounded queue was added. Each Java receive lease is an ordinary
JVM object and can still contribute to host GC; explicit native actor arenas
are a separate memory domain. A process-level no-GC claim is not supported.

## Additional admission/receipt hardening

A STOP could previously win between the source mailbox dispatcher's
first lifecycle check and the separately synchronized receive-lease mint.
The gate would then reject `enterReceive` as an IllegalStateException, and
the broad actor exception handler would incorrectly promote an otherwise
graceful STOP to a terminal actor failure. The source receive path now
holds `ActorCell.lifecycleLock` around the final stopped/finalized check
**and** receive-lease admission; the STOP CAS and source gate seal take
the same monitor. If STOP wins, the dequeued envelope is closed and skipped,
not dispatched or counted as an actor failure.

The gate also creates its receiver lease **before** incrementing the active
receive count, so failed JVM allocation cannot strand a false physical pin.
A synchronized single-release receipt replaces the additional per-mail
`AtomicBoolean` allocation. Neither change eliminates JVM host GC.

### Local gate-only executed evidence

On 2026-11-14, an isolated JDK 21 build copied the exact Git blob of
`ActorSourceLifecycleGate.java` at
`0e71c4c3f048f2a831ac1cd0f36ef5fb842caffa` and verified its
`git hash-object`. With minimal external `ActorRuntime` type stubs,
`javac --release 21 -Xlint:all -Werror` succeeded and an executable
two-virtual-thread STOP/admission harness passed 1,000 rounds, alongside
teardown-before-reclamation, duplicate-release and forged owner cases.
This tests the byte-exact **gate**, not the full application, Maven/JUnit
suite, JDK 25 or compiled `.atto` guest.

The production `ActorRuntime` STOP-admission race fix and its
24-round `ActorSourceTearDownTest` have been **authored but not
executed** as a full project binary. Exact-head source CI remains pending.

## Regression tests and exact-head acceptance

- `ActorReceiveQuiescenceGateTest`: two separately scheduled Java receiver
  threads hold two live leases; STOP rejects a third, teardown and final
  physical close remain blocked until **both** exit, and a running teardown
  itself still blocks retirement.
- Existing `ActorSourceLifecycleGateTest`: at-most-once teardown, stop during
  init, forged owner, cancellation and new required cleanup completion receipt.
- Existing `ActorSourceTearDownTest`: the real runtime's stop/guest-failure
  cleanup paths, including teardown on an admitted turn and terminal DONE.

Run on the exact stacked PR head:

```sh
mvn -Dtest=ActorReceiveQuiescenceGateTest,ActorSourceLifecycleGateTest,ActorSourceTearDownTest test
```

A separate local, **reconstructed-source-only** JDK 21 smoke (not the full
private repository or exact PR tree) compiled with
`javac --release 21 -Xlint:all -Werror` and exercised two Java threads,
STOP, blocked teardown and final cleanup. This is supporting debugging
evidence only, not a CI/JUnit result. As of this branch, source-org GitHub
Actions jobs show failure with no executed steps. Respect
`docs/security/CI_PRIVATE_SOURCE_MIRRORING.md`: no ad-hoc source export
to another organization without explicit approved destination, protected-main
and reviewer gates. Missing CI, full GraalVM guest execution or verified
native resource reclamation remains **NOT_EXECUTED**.


## STOP racing source initializer: additional runtime correction

The source actor initializer may be physically executing when a host STOP
seals the `ActorSourceLifecycleGate`. The gate correctly rejects a late
`commitInit`, but the surrounding `ActorRuntime` previously classified
the `IllegalStateException` as an unhandled guest failure. This poisoned
normal actor DONE despite STOP being the legitimate winner.

The actual startup path now maps **only** a late commit rejected while the
runtime is stopped **and** the source gate is `STOPPING_TERMINAL` to
`ActorCancellationSignal`. Foreign owned-state, malformed init results and
unrelated startup exceptions still remain terminal failures. The existing
outer actor catch treats this control-plane cancellation as normal STOP
unwind. No guest hook is run on an actor stopped before READY.

`ActorSourceTearDownTest` includes a deterministic blocked-initializer
handoff: the test uses a separate host STOP thread because
`ActorRef.stop()` waits for physical finalization, then releases init,
joins STOP, verifies `ready` rejected, `done` succeeded and `tear_down`
never ran. These full-runtime JUnit changes are **AUTHORED_NOT_EXECUTED**;
the source-actor READY-publication observer race remains separate and must
be reconciled with the existing Java READY-observer fixes.

### Local test command

```sh
mvn -Dtest=ActorSourceTearDownTest,ActorSourceLifecycleGateTest,ActorReceiveQuiescenceGateTest test
```

Running this command requires the actual source checkout, its JDK/GraalVM
dependencies and a functioning local/approved CI runner. Host-only
receiver-gate tests alone are not proof of compiled `.atto` concurrency.

## Additional STOP/init/quiescence audit (2026-11-14)

Source startup has **two** STOP races: STOP can win between the outer
`!stopped` check and `beginInit`, or while the initialized state is
being produced before `commitInit`. Both now return the dedicated runtime
`ActorSourceLifecycleGate.InitializationStopped` result, mapped to
`ActorCancellationSignal` only at the trusted source startup boundary.
No arbitrary guest `IllegalStateException` is reinterpreted as STOP.
Non-STOP failures, including a guest state ownership accessor that throws
`IllegalStateException`, retain their failure classification.

A separate `initializationActive` pin survives STOP until the source
initializer physically returns or throws and the runtime invokes
`failInit()`. The gate refuses physical `close()` before the trusted
STOP seal, while initialization is still active, or while an approved
graceful `tear_down()` has not finished. `readyForRetirement()` exposes
these combined invariants to `ActorRuntime.finalizeStopLocked()` **before**
the runtime publishes `finalized=true`. Receive and teardown pins
remain separately required.

Tests have been updated for the stronger lifecycle phases, including
STOP-before-begin, STOP-during-init, malformed ownership state, duplicate
commit, premature teardown, and finalizer readiness. These full project
JUnit tests remain **NOT_EXECUTED** until the JDK25 runner or an approved
local source checkout is available.

An isolated JDK21 `--release 21 -Xlint:all -Werror` smoke copied the
**byte-exact current gate blob** `6d5cb47674877f1ae22de3b35c26359c0d91b406`,
verified with `git hash-object`. The local executable harness passed
1,000 two-virtual-carrier STOP races, 1,000 STOP-during-initializer
cases, and 1,000 malformed state-accessor failures. These are gate
tests using minimal external `ActorRuntime` stubs; they do **not**
constitute compiled `.atto` actor execution, full Maven JUnit, or
five-backend parity.

## Host-owner callback isolation and byte-exact gate execution (2026-11-14)

Audit found `ActorOwnedGuestState.ownerActorId()` invoked under the
source-lifecycle monitor during initialization, receive admission and
teardown, and invoked under `ActorCell.lifecycleLock` during production
receive admission. Because an executor supplies this accessor, a blocked or
reentrant implementation could stall a trusted STOP seal.

- `commitInit` snapshots owner outside the monitor, with a phase check both
  before and after that callback. STOP winning during the callback produces
  only the typed `InitializationStopped` result.
- `enterReceiveOwners` accepts only actor/mailbox identity snapshots and
  atomically validates READY/ownership and mints its receive lease under the
  gate monitor. The production dispatcher snapshots outside lifecycleLock,
  then performs its final stopped check and gate admission together under
  lifecycleLock. No arbitrary owner accessor runs under those locks.
- `beginTeardown` likewise captures ownership before taking its monitor.
- A redundant second owner callback before receipt admission was removed.
  STOP remains a control-plane operation, not a guest exception.

The current exact GitHub gate blob is
`a4f9f3d45254bde716af3d3089bcebd5dc10aa42`. In an isolated local
JDK 21 project, this **byte-exact Git blob was verified by
`git hash-object`**, compiled with
`javac --release 21 -Xlint:all -Werror`, and passed 1,000 two-carrier
STOP/admission races plus 500 deliberately blocked owner-accessor cases
(250 initialization, 250 receive). No compiler/JUnit stub can substitute
for the new full-runtime `ActorSourceTearDownTest` regression or a real
compiled `.atto` guest. Those remain **NOT_EXECUTED**.

## Graceful teardown admission failure cannot deadlock STOP (2026-11-14)

A further runtime-path audit found a liveness violation: if
`runGracefulTeardownIfNeeded()` reserved the lifecycle lane but
`beginTeardown()` failed (for example, an executor-provided
`ActorOwnedGuestState.ownerActorId()` accessor threw), the gate remained
`STOPPING_GRACEFUL`. The outer runtime reset
`gracefulTeardownPending=false`, leaving no task able to claim the hook,
while `readyForRetirement()` rejected physical finalization forever.
The original failure could be recorded without `get_done()` settling.

The runtime now throws an explicit invariant failure for a reserved lane
without initialized state; on an error from hook admission or hook
execution, it seals the source lifecycle gate **terminally** via
`sourceLifecycleGate.sealStop(false)` and rethrows the original exception.
This revokes only a still-unclaimed graceful permit; it cannot remove a
`TEARING_DOWN` hook's physical pin. The normal outer finalization path
retains the exception as DONE/supervisor evidence. The added real-runtime
`failedTeardownOwnershipAdmissionDoesNotStrandActorFinalization` JUnit case
asserts a completed host STOP, exceptional DONE with the original state
accessor failure, and **zero guest teardown callbacks**. A matching
source-gate JUnit test covers the fail-closed phase transition.

The byte-exact gate blob
`a4f9f3d45254bde716af3d3089bcebd5dc10aa42` was independently
compiled with JDK 21 `--release 21 -Xlint:all -Werror`; an isolated test
exercised 1,000 additional failed hook-admission/terminal-revocation
cycles and passed. Full `ActorRuntime` JUnit, GraalVM 25 and compiled
`.atto` remain **NOT_EXECUTED** pending authorized runner admission.

## Cross-carrier state-reference publication (2026-11-14)

Another source runtime review identified that
`ActorCell.codeActorState` was published by the actor dispatcher
without the same `lifecycleLock` used by host-side STOP and graceful
teardown eligibility checks. The state *reference* is now
`volatile`, establishing a Java Memory Model visibility guarantee
between the initializing carrier and later host readers. This is
not a shared-mutable-state capability: actor ownership and
`critical-read`/`critical-write` remain mandatory for guest state
access, and physical teardown still waits for each receive and
continuation receipt.

Existing source STOP/initialization and blocked-host-accessor
regressions cover the affected lifecycle interleavings; this
specific visibility adjustment is **NOT independently proven by full
Maven/JDK25 integration**, which remains blocked by GitHub Actions
failing before job steps.
