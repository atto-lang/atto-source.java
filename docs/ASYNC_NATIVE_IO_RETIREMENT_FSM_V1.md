# AsyncNativeIo physical shutdown / operation state machine (draft)

This host-runtime protocol is stacked on #1940. It is **source code and
unexecuted JUnit tests** until an authorized JDK25 runner actually runs them.
It does not prove release of native pointers or a driver not using AsyncNativeIo.

## States

Each accepted operation owns one Future and one context admission slot:

| Before | Trigger | After | Allowed effect |
|---|---|---|---|
| ADMITTED | worker enters while context open | RUNNING | call host Callable |
| ADMITTED | shutdownNow returns the never-started runnable | RETIRING | fail Future without calling native IO |
| ADMITTED | worker enters after STOP | RETIRING | fail Future without calling native IO |
| RUNNING | Callable + host accounting finalizer exit | RETIRING | deliver terminal Future callbacks |
| RETIRING | subscribers finished; operation removed | RETIRED | release owned operation admission |
| RETIRING (not started) | subscribers finished; removed | CANCELLED_BEFORE_START | release never-used admission |

A runnable may be removed from a worker queue but not returned by its
executor's `shutdownNow`. Under such an untrusted/noncompliant executor it
must remain retained: **no fabricated pre-start receipt**. Exact JDK executor
behavior must be verified in runnable tests.

## Shutdown admission and callback ordering

1. Under the context monitor, seal all new operation/resource admission,
   set `closeScanPending`, and retain a strong snapshot of already-admitted
   resource owners.
2. Request host-worker interrupt via `shutdownNow`. This does not prove
   any RUNNING operation is physically quiescent.
3. Request `AutoCloseable.close()` for *every* registered resource, without
   converting a close request into an operation-release receipt. A failing
   close callback records sticky quarantine; subsequent resources still
   receive close attempts. Native drivers with callbacks after `close`
   need their own outstanding physical ticket.
4. Each executor-returned never-started `Operation` is transitioned once to
   RETIRING and has its Future failed. Worker RUNNING operations instead
   settle their Futures only after their Callable/finalizer returns.
5. Every operation stays in `admittedWork` until all synchronous runtime
   Future subscribers have exited. Completion may run on a native worker
   thread, not necessarily the actor carrier.
6. Rejecting a newly registered resource after STOP must close it *outside*
   the context lock, counting the in-flight rejected-resource close and
   latching any failure for quarantine.
7. `physicalShutdown` completes successfully only if STOP and the shutdown
   close scan finished, there are **zero admitted operations, zero pending
   rejected-resource close callbacks**, and no recorded close/notification
   failure. No unproven or failed release may be called success.

## Invariants for review/formal models

- **I1** A Future for a RUNNING operation never reaches terminal state by
  context STOP alone. It may become terminal only at/after RETIRING.
- **I2** A CANCELLED_BEFORE_START operation did not invoke its Callable.
- **I3** Each operation follows exactly one retirement path; all transitions
  serialize on the context monitor.
- **I4** A successful physicalShutdown receipt implies no admitted host
  operation or rejected-resource close callback can still run.
- **I5** A close or terminal notification failure prevents successful
  physicalShutdown; retained native resource ownership is quarantined.
- **I6** No resource close request or Future terminal callback runs while
  holding the context admission monitor.

These invariants have not been mechanically checked with TLA+/Apalache,
Dafny, Alloy, or Java concurrency verification. They are intended as formal
specification inputs and adversarial acceptance criteria, not a proof.

## Deterministic regression matrix

`AsyncNativeIoTest` on JDK25 now includes: a single-worker executor with
a queued never-started operation; STOP versus uninterruptible success and
failure; a deliberately blocked inline terminal Future subscriber; a
registered resource whose close fails; a hostile Error thrown by one resource
while a sibling still closes; a resource registered after STOP whose close
blocks; and a rejected late resource whose close fails and quarantines.

These tests are **committed but have not executed on the protected project's
GitHub Actions runners**. Source-org Actions job scheduling fails with zero
steps. No Git-tree mirror to other organizations is permitted until protected
main's private CI allowlist approves an independently reviewed exact target.

## Explicit remaining limitations

- This is the **AsyncNativeIo host executor and context ownership receipt**,
  not a universal FFI/GPU/pinned memory physical-release proof. Drivers with
  async callbacks or native ownership after `close()` must register separate
  `ActorPhysicalRetirementRegistry` receipts and join them with Actor STOP.
- `AsyncNativeIo.close()` initiates registered `AutoCloseable.close()` calls
  synchronously. Host drivers whose close can block indefinitely must use an
  independently bounded, non-parking native close lane with a real physical
  retirement receipt; they must never block an actor carrier.
- `physicalShutdown` requires an explicit join by the owning context or
  supervisor. Merely exposing the receipt does not itself wire actor DONE or
  supervisor restart policy to it.
- A broken executor that drops never-started tasks without returning them
  from `shutdownNow` may keep the receipt pending forever, which is a safe
  quarantine rather than a false success.
