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
