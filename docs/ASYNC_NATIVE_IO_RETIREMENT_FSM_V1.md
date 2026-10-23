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
6. Rejecting a newly registered resource *during* STOP must close it
   outside the context lock, counting the in-flight rejected-resource close
   and latching any failure for quarantine. After a successful shutdown
   receipt has already been published, registration rejects immediately and
   keeps responsibility for the never-admitted handle with its caller: the
   retired context must not initiate any new close callback.
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
- **I4** A successful physicalShutdown receipt implies no previously
  admitted host operation or previously authorized rejected-resource close
  callback can still run. Future resource registrations against a retired
  context are rejected without starting new native callbacks.
- **I5** A close or terminal notification failure prevents successful
  physicalShutdown; retained native resource ownership is quarantined.
- **I6** No resource close request or Future terminal callback runs while
  holding the context admission monitor.

These invariants have not been mechanically checked with TLA+/Apalache,
Dafny, Alloy, or Java concurrency verification. They are intended as formal
specification inputs and adversarial acceptance criteria, not a proof.

## Two-stage native release and context quarantine

Trusted host drivers that need to interrupt a worker before reclaiming a
native buffer should register a `AsyncNativeIo.StopThenRelease` resource:

- `requestStop()` is issued during STOP. It may request cancellation or
  shutdown on a transport; **it may not release pinned buffers, FFI state,
  or callback-owned memory**.
- `close()` is the final physical release callback. The context claims each
  release once and runs it outside the context admission monitor **only
  after** the tracked Callable, perf cleanup, and synchronous Future terminal
  listeners have retired. A pending final release blocks `physicalShutdown()`.
- Failures in either phase are sticky. Sibling stop requests/final release
  attempts continue, but the context is quarantined rather than asserting
  that memory reclamation is safe.
- Legacy `AutoCloseable` resources retain their old single-phase close
  behavior. **Their close is only a cancellation/transport-close receipt,
  not proof of native loan retirement.** Migrate ownership-sensitive drivers
  to the staged contract and register any external asynchronous FFI, JNI, NIO
  completion, or DMA callback with its own generation-scoped physical ticket.

`OresContext.close()` now requests the native shutdown, then checks the
physical receipt **before** invoking `AsyncRuntime.close()`,
`ActorRuntime.close()`, `OresVM.close()`, shared code-image cleanup or
GC cleanup. If a worker, terminal observer, or final resource release is
still outstanding, close fails immediately and retains the context in an
explicit strong-reference quarantine. A trusted owner can retry close after
`nativeIo().physicalShutdown()` resolves. An observed physical release
failure remains quarantined, even after a later retry.

This is a *nonblocking, fail-closed* context gate, not an implementation
of automatic retry/reaper, a universal actor-scoped native-loan instrumenter,
or a license to reclaim an arena before an actor-incarnation receipt retires.
In particular, a driver with callbacks outside the context I/O workers
must still join those tickets through `ActorPhysicalRetirementRegistry`.

## Deterministic regression matrix

`AsyncNativeIoTest` on JDK25 additionally specifies staged final release after an uninterruptible worker and after a blocked terminal subscriber, and sticky failure on STOP request. The suite includes: a single-worker executor with
a queued never-started operation; STOP versus uninterruptible success and
failure; a deliberately blocked inline terminal Future subscriber; a
registered resource whose close fails; a hostile Error thrown by one resource
while a sibling still closes; a resource registered during STOP whose close
blocks; a rejected late resource whose close fails and quarantines; a
fully retired context rejecting any new close callback; and a hostile
close Error that cannot skip another resource's close.

These tests are **committed but have not executed on the protected project's
GitHub Actions runners**. Source-org Actions job scheduling fails with zero
steps. No Git-tree mirror to other organizations is permitted until protected
main's private CI allowlist approves an independently reviewed exact target.

## Actor-incarnation operation admission (2026-10-23 continuation)

Java source lowering in `OresEvalRootNode` routes `fs.*_async`,
`network.connect_async`, `socket.read_line_async`,
`socket.write_text_async`, `socket.close_async`, and
`http.{get,post}_text_async` through context-aware
`AsyncNativeIo.submit(context,...)` / `submitClose(context,...)`.

- On an actor execution turn, the trusted host adapter authenticates the
  exact owning `ActorRuntime` and mints a generation-scoped
  `NATIVE_IO_OPERATION` physical receipt **before** executor admission.
  `ActorPhysicalRetirementRegistry` rejects ownership registration without
  the actual actor single-writer lease. A foreign runtime is denied.
- A worker-running receipt retires only after the actual Callable, perf
  accounting and synchronous terminal Future subscriptions return. A
  `shutdownNow()`-returned never-started runnable retires through
  `CANCELLED_BEFORE_START` instead; an executor that fails to prove
  nonexecution must leave the actor quarantined rather than forging a release.
- A capacity rejection performs no host work; its receipt retires only after
  the rejection Future's synchronous host callbacks have drained, outside
  the I/O admission monitor. The actor's `ActorRef.done()` independently
  joins those generation-scoped tickets.
- Root-context operations have no actor incarnation; they remain context
  owned and must be joined through `physicalShutdown()`. This does not
  authorize externally launched callbacks or foreign runtime resources.
- New adversarial Java tests exercise a stopped actor with uninterruptible
  I/O, blocked terminal subscriber, never-started queued work, foreign
  runtime/root forged registration, and independent replacement actor whose
  DONE cannot release an old incarnation's outstanding loan.

**Independent native callbacks are still separate.** A Java host Callable
return is NOT proof that a JNI/native driver has stopped accessing pinned
memory it retained after return. Such drivers must acquire a separate
`ActorPhysicalRetirementRegistry` ticket before handing out the loan.

## TCP per-connection read/write fencing

`NativeIo.SocketStopThenRelease` now owns a synchronized active-operation
counter. A read/write acquires a loan before accessing the socket stream and
returns it only after the host call and stream buffering/flush have exited.
STOP seals new admission and requests `shutdownInput`/`shutdownOutput`.
Explicit socket close that races an outstanding loan fails closed, retains
the registered owner, and cannot claim final descriptor closure. Another
close after loan exit, or context-STOP final release after all context workers
retire, may physically close the descriptor. A simultaneous second closer
cannot report success while the first is still inside native `close()`.

This is a per-connection host loan fence; it is not yet a full independent
actor-incarnation receipt for sockets escaped across actor lifetimes, and
must not be treated as zero-copy pointer/DMA lifetime certification.

## Explicit remaining limitations

- **Concrete TCP migration (partial):** `NativeIo.connect()` now registers
  `SocketStopThenRelease`. Context STOP half-closes input/output, deferring
  final descriptor close until tracked context workers and synchronous
  notifications retire. Connection read/write methods now hold explicit
  per-worker, one-shot, same-thread physical operation loans; concurrent
  explicit close fails closed until those loans retire. However external
  callbacks/FFI, borrowed socket handles that outlive their owning actor
  and socket-native pinned/DMA transfers still require independent receipts.
  Java virtual-thread interruption may itself close a transport descriptor;
  cancellation is not a proof that its native callback exited.
- **Actor DONE now joins context-submitted native I/O work:** actor-turn
  `submit(context,...)` and `submitClose(context,...)` mint physical
  tickets before worker dispatch. But host drivers that bypass these APIs,
  native code retaining loans *after* the Java Callable exits, and raw
  connection handles crossing incarnations still need their own actor-scoped
  receipts. Issue #1944's final-notifier reentrancy/exception ordering remains
  open. The existing last wake may still execute on the retiring notifier's
  thread. Do not claim callback-exit formal proof without resolving #1944
  and running exact-source concurrency evidence.
- This is the **AsyncNativeIo host executor and context ownership receipt**,
  not a universal FFI/GPU/pinned memory physical-release proof. Drivers with
  async callbacks or native ownership after `close()` must register separate
  `ActorPhysicalRetirementRegistry` receipts and join them with Actor STOP.
- `AsyncNativeIo.close()` initiates registered `AutoCloseable.close()` calls
  synchronously. Host drivers whose close can block indefinitely must use an
  independently bounded, non-parking native close lane with a real physical
  retirement receipt; they must never block an actor carrier.
- `OresContext.close()` now fails closed and quarantines if
  `physicalShutdown` is incomplete or failed, before actor/VM/GC teardown.
  Per-actor STOP is independent: every other native/FFI driver must still
  register a generation-scoped actor receipt and join it through DONE.
- A broken executor that drops never-started tasks without returning them
  from `shutdownNow` may keep the receipt pending forever, which is a safe
  quarantine rather than a false success.
