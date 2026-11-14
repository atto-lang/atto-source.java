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
