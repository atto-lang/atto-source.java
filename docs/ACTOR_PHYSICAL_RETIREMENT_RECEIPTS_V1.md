# Actor physical resource retirement registry — Java draft

Status: **DRAFT / no exact-head executed JDK25 proof**. Stacked onto
actor STOP cleanup PR #1936 (which stacks on mutex/grant retirement #1933).
The work introduces a host runtime substrate; it does **not** automatically
instrument native TCP/HTTP, FFI, or LLVM runtimes.

## Runtime contract

- Exactly one `ActorPhysicalRetirementRegistry` belongs to each ActorCell,
  identified by its immutable ActorId and generation.
- A trusted actor-turn adapter must hold the actor's *physical*
  single-writer lease to register a resource ticket. Alternatively, a trusted
  native host driver/supervisor may register against an exact ActorRef from
  outside all guest/actor turns before accepting/handing a resource to that
  actor. Both paths register **before** acquiring/publishing the physical
  resource or native loan.
- Every ticket declares a kind: MUTEX_HANDOFF, TCP_HTTP_HANDLE, FFI_LOAN,
  ASYNC_ITERATOR, SHARED_BLOB_LEASE, OTHER. Diagnostics expose only immutable
  per-kind counts and the failure cause; no ticket escapes guest syntax.
- STOP seals ordinary ticket admission under the actor lifecycle monitor.
  Only a scheduler-authenticated cancellation cleanup turn or explicit
  `endWithCleanup` body may register further physical close tickets, bounded
  globally per actor to 4096 pending receipts. Guest code cannot manufacture
  or close tickets through Oreslang source APIs.
- A physical driver calls `Receipt.completeAfterPhysicalRelease()` **only
  after** the native close, final callback, mutex handoff reclamation, shared
  blob lifetime, or other release barrier really completes. The settlement
  callback may be on a foreign completion thread, but never executes guest
  code directly. Repeated settlement returns false; no double decrement.
- `Receipt.failPhysicalRelease(Throwable)` is sticky, retains the ticket,
  keeps the ActorCell reachable, and quarantines the arena. A failed release
  cannot be converted into success by a late completion or duplicate call.
  Capacity exhaustion also records a sticky failure.
- The actor's finalization gate joins physical ticket settlement **in addition
  to** SourceTask physical quiescence, terminal Future subscribers,
  cleanup-created Futures, owned Future revocation, and child-actor completion.
  The registry becomes irreversibly retired only on the verified finalization
  winner, before ActorRef.done and arena closure.
- A parent/supervisor cannot publish completion until each child has retired
  all of its physical tickets. A replacement actor incarnation has its own
  registry and cannot inherit or reuse old receipts.

## Trusted adapter pattern (conceptual Java host code)

```java
// Inside a trusted runtime adapter, while holding this actor's lease:
var receipt = runtime.registerCurrentActorPhysicalReceipt(
    ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE);
// Or from the native host transport acceptor, outside actor/guest turns:
// var receipt = runtime.registerActorPhysicalReceiptFromDriver(
//     exactActorRef, ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE);
// Acquire native/transport loan only after registration succeeds.
// The resource close machinery retains the opaque receipt.
requestPhysicalClose(nativeHandle);
// Later, on trusted driver's ACTUAL physical release callback:
receipt.completeAfterPhysicalRelease();
// If the close fails and physical release cannot be proven:
// receipt.failPhysicalRelease(failure);
```

The ticket is a claim of physical release, **not** a promise that an
`OresFuture.cancel()` or `closeAsync()` request will finish. An adapter
must not settle it when cancellation is merely accepted, on producer Future
completion without the handle-close barrier, or when an actor is logically
stopped but a DMA/FFI loan remains active.

## Tests committed, not passed

`ActorPhysicalRetirementRegistryTest` covers:
- Actor cancelled with outstanding TCP + FFI tickets; DONE waits for BOTH;
  external completion callback is allowed, double-settlement is ignored.
- A native host transport acceptor registers its TCP loan BEFORE delivering
  work; rejects foreign-runtime ActorRefs, new tickets after logical STOP,
  and tickets after finalized retirement.
- Parent actor STOP with child FFI loan: parent and child DONE remain pending
  until the child's physical release.
- SourceTask with an FFI ticket: after STOP its authenticated cancellation
  cleanup registers an async-iterator ticket and both settle separately.
- `endWithCleanup` registration of a physical close after logical STOP.
- Sticky physical release failure, duplicate release failure, immutable
  per-kind diagnostics, late post-retirement registration denial.
- Two simultaneous foreign completion threads racing to settle one ticket
  result in exactly one success and no negative outstanding count.

The focused exact-head JDK25/GraalVM workflow includes this suite.
If GitHub Actions reports zero steps, mark CI **not executed** rather than
passing or failing tests. No private Git tree may cross organizations until
protected-branch private-source policy approves the destination's exact
owner/repo and immutable repo ID via independent review (#1538).

## Remaining P0 integration and execution gates

1. Connect real native IO close, TCP/HTTP transport, FFI loan, mutex async
   handoff, async iterator and shared-blob lease owners to ticket registration
   and their **physical** settlement events. A resource not registered is
   invisible to this registry, so the current patch alone does not prove all
   actor resources are safe at DONE. Task-owned mutex receipts already
   participate in TaskHandle and SourceTask scheduler barriers separately.
2. Prove actor/driver lock-order compatibility, reentrant native callbacks,
   timed-out close and cancellation racing an in-flight callback. On failure,
   record quarantine rather than clearing tickets or releasing the arena.
3. Run JDK25 source/JUnit tests and GraalVM native-image tests on an approved
   runner. Add deterministic crash/restart, force isolation and cancellation
   matrix plus actual source `defer`/`finally`/iterator-close programs.
4. Connect LLVM scheduler/resource-registry ticket issuance to actual
   guest/native lifetime transitions and compare semantic traces across
   Java and LLVM with sanitizer evidence.
5. Keep every involved PR draft until an independently reviewed exact-head
   artifact proves successful source compilation, lifecycle conformance
   and physical resource reclamation.

## Last-receipt callback retirement fence

Removing a ticket from the pending set does not immediately prove physical
callback quiescence. The registry also tracks `inFlightNotifications`:
the ticket removal winner increments this count BEFORE exposing its empty
pending set, notifies the actor, releases the in-flight count, and notifies
the actor again to retry finalization. ActorCell must see BOTH zero pending
tickets AND zero in-flight host notification callbacks. Errors from either
notification stage latch as physical retirement failures and quarantine the
actor. These callbacks execute without holding the registry's internal lock.

Deterministic tests block the first host notification after the last ticket
has been removed, then require `retireVerified()` to reject until notification
exit; they also inject a throwing notification and require sticky quarantine.
This is distinct from the SourceTask Future-subscriber drain fence and must
be preserved in the native/LLVM port.

## Concrete JDK NIO channel boundary (new follow-up)

`ActorPhysicalChannelLease` is a host-only (not guest-language) bridge
for a **synchronous JDK Channel.close() boundary**. A trusted acceptor calls
`ActorRuntime.registerActorPhysicalChannelFromDriver(exactActorRef, channel,
kind)` **before** publishing the accepted channel to that actor. The returned
lease owns the physical close transition.

- The lease calls the REAL `Channel.close()`, checks `!channel.isOpen()`,
  and only then publishes `Receipt.completeAfterPhysicalRelease()`.
  Concurrent `close()` calls serialize and do not double-close or release
  the actor's retirement counter twice.
- If `close()` throws, or returns while `isOpen()` is still true, the
  ticket stays outstanding and a sticky physical failure quarantines the
  actor's arena. The original actor failure remains the primary lifecycle
  cause; the driver-close failure is visible through the physical diagnostic.
- **No automatic close-on-STOP is implemented in this draft.** The driver
  must issue the close itself as part of STOP or cleanup. Until that call
  completes, the actor remains unretired. Do not infer physical completion
  from merely cancelling the actor, an OresFuture, or a transport task.
- JDK synchronous channel close is NOT a sufficient receipt for outstanding
  overlapped operations, pending native callbacks, pinned receive buffers,
  DMA/FFI loans, or zero-copy shared blobs. Each such lifetime requires its
  own independently settled ticket.
- Tests: actual `java.nio.channels.Pipe.SourceChannel` close, blocked
  fake-kernel close, two concurrent closers, and injected `IOException`
  failure. These source-level tests are committed but **not executed**.

### Security and testing status

The repository's protected-branch destination allowlist is still empty.
Do not copy private Git trees to another GitHub org without exact reviewed
numeric-ID approval, even when alternative orgs have available Actions minutes.
Keep this PR in draft until JDK25 JUnit, native-image/LLVM and adversarial
source-level execution passes on an authorized runner.


## Automatic STOP close lane and independently tracked NIO I/O (new)

The trusted host driver now registers a Java `Channel` and its actor
retirement ticket **atomically under ActorCell.lifecycleLock**. A successful
lease is retained on that exact ActorCell until verified closure. Actor STOP
takes a sealed snapshot of the registered leases under the same lock, then
submits their `close()` requests to a dedicated eight-worker bounded host
executor **outside the actor lifecycle lock**. The executor is not an actor
carrier or an Oreslang thread-pool domain. It is retired with ActorRuntime.
STOP admission never waits for physical close; failed submissions, failed
channel closures or callbacks that cannot be proven quiescent quarantine the
arena. A verified-closed channel detaches from the actor's lease set before
its physical receipt can permit actor DONE.

The new host-only `ActorPhysicalIoCallbackLease<V,A>` implements JDK
`CompletionHandler`: the driver must register one ticket **before** issuing
each async read/write and pass this wrapper as the actual completion
handler. It completes its ticket only after the native operation completes
AND its trusted callback returns. A failed operation may still have a
successfully retired operation callback, but a throwing callback or uncertain
submission keeps a sticky physical failure. A channel's closure does not
implicitly retire these independent operation callbacks or FFI loans.

Deterministic tests include automatic STOP closing a real NIO pipe,
a slow/blocked close, dual host closer races, injected close failures,
actual `AsynchronousFileChannel.read` with a deliberately blocked completion
callback, and a failing trusted I/O callback. All remain unexecuted pending
authorized JDK25 CI.

### Residual integration/verification caveats

- The bridge is **not yet wired to every Oreslang HTTP/TCP/FFI server**;
  existing native backends that bypass it still need integration.
- A JDK `Channel.close` is only a synchronous channel close receipt. Even
  after closure, independent native I/O callbacks, pinned memory, zero-copy
  loans and FFI references must settle their own tickets.
- A completion notification's *final host-only lifecycle recheck* may run
  while the actor is finalizing; it is trusted bookkeeping rather than a
  native resource callback. Audit that callback for reentrant exceptions
  and do not claim full callback-exit safety without executed race proofs.
- The bounded cleanup lane does not force-release a stuck native operation.
  A stalled close may delay shutdown and quarantine instead of reclaiming
  memory, as required for safety.

## Reentrant close audit (2026-10-23)

The synchronous NIO physical-close winner sets its `closed` flag immediately after `Channel.close()` returns and `!channel.isOpen()` is verified, BEFORE invoking the owner-detach or retirement callbacks. These trusted callbacks can reenter `ActorPhysicalChannelLease.close()` via host-only lifecycle notification or an `ActorRef.done()` waiter. Such reentrance now observes the physically-closed state rather than throwing a false recursive-close error. A failed owner-detach still records a sticky failed receipt, retains quarantine and does not report successful actor retirement. Deterministic JUnit cases exercise both paths. This is committed source coverage, not executed test evidence.

## Isolated exact-source host smoke (2026-10-23)

Two host helper source files were inspected at this PR branch and reproduced byte-for-byte in a local Java 21 test directory. The Git blob hashes matched the fetched GitHub blobs exactly:

- `ActorPhysicalIoCallbackLease.java`: `4ddb01dd7c0748318add0e3376a7ea20aa59dc44`
- `ActorPhysicalChannelLease.java`: `7189ae9482d16bdbb76c8f23e5d36dd5537363c499b03f9deec4af`

A minimal **stub** for `ActorPhysicalRetirementRegistry.Receipt` supplied settlement counters/failure state to compile and exercise these exact production helpers in isolation using `javac 21.0.12.1`. The following **8 isolated cases passed**: normal NIO completion; overlapping duplicate callback quarantine; throwing callback quarantine; actual JDK NIO Pipe physical close; reentrant verified channel close; blocked physical close; throwing owner-detach callback; rejected bounded close executor submission.

This is useful **local behavioral evidence for the two helper classes only**. It does NOT compile the 8k-line `ActorRuntime`, test its STOP/supervision interplay, use the real `ActorPhysicalRetirementRegistry`, or establish JDK25/GraalVM/LLVM parity. Those remain explicit release gates. No private-source cross-org mirror was created.

Also added source JUnit regressions for (a) a broken channel whose close fails while a healthy sibling still closes on STOP, and (b) a duplicate asynchronous completion while its original delegate remains running. Failed close/notify paths must quarantine and may never publish clean `ActorRef.done()`.

## AsyncNativeIo physical-worker retirement (2026-10-23)

An additional lifecycle audit identified a context-owned I/O hole in `AsyncNativeIo.close()`: it previously called `OresFuture.failFromRuntime` for all pending and control operations immediately after interrupting the workers. Interrupted host calls can ignore interruption and still own an NIO/FFI/mapped-buffer loan, so that publication was a **false physical retirement signal**.

This branch now changes context-close behavior:

- `close()` seals admission and calls `shutdownNow()` as an interruption REQUEST. It does not settle Futures belonging to physically active workers or discard their ownership entries.
- Every submitted `Callable` publishes its outcome only after returning or throwing, including its host `CorePerf` finalizer. Only then does it remove the operation from the context's live admission set. A successful host operation that exits after the context closed is reported as a context-close failure; actual host failures retain their original cause.
- A host operation that ignores interruption stays pending and strongly reachable until it exits, rather than fabricating `getDone` or releasing actor/native arena ownership.
- `AsyncNativeIoTest` adds deterministic cases for an uncooperative SUCCESS and an uncooperative FAILURE. Both are held behind a latch while context.close requests interruption; neither Future may settle before actual worker exit. Exact-head focused JDK25 configuration includes this suite.

**Limits:** This fixes premature `OresFuture` terminal publication and context bookkeeping. The context's existing resource-close traversal can still run while a worker is active; each resource driver must independently fence native access and physical release. Context close itself is not proof that every uncooperative worker has returned, and it is not a substitute for the incarnation-bound `ActorPhysicalRetirementRegistry`.

**CI evidence at head:** push-triggered [focused run #39993103400](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39993103400) failed before any job step in both `java` and `native` configurations; runner_name was empty. This is NO source-level JUnit/AOT test evidence. The project cannot export its private Git tree to another organization without the exact reviewed destination allowlist becoming active on protected main.

## Deferred physical notifier handoff (2026-10-23 follow-up)

The final physical-ticket notifier previously invoked `sourceTaskCleanupQuiesced`
a second time inline *after* decreasing `inFlightNotifications`. That could
authorize `ActorRef.done()` while the final host notifier remained on the native
completion stack; a reentrant or throwing callback could then report failure
after actor memory reclamation.

The actual ActorCell now passes a **bounded, host-only asynchronous executor**
(`physicalCloseDispatcher::execute`) into its physical registry. The first
state-change notification runs while `inFlightNotifications > 0`, so it cannot
finalize the actor; after that notification exits and its in-flight fence
decrements, the registry **queues** the final actor STOP recheck off the native
completion stack. The queue is not a guest carrier and cannot run guest code.

The deferred wake uses a one-shot dispatch-approval gate: a fast worker waits
until the submitter proves the queue accepted the task without throwing.
Executor rejection and an executor that enqueues then throws both fail closed;
the queued but unapproved task is inert. Caller-runs/inline execution is
forbidden, and even a caller-runs executor that **swallows** its inline error
is detected and quarantined. Old direct registry-only test instances retain
their synchronous behavior for isolated callback fault tests; real ActorCells
always use the deferred dispatcher.

Committed deterministic Java JUnit regressions cover deferred first/final
notifications, enqueue-then-throw with a stale queued callback, inline
caller-runs, swallowed inline failure, and a worker that starts before dispatch
acceptance returns. This is source coverage until JDK25 actually runs it.

**Limits/release gates:** The bounded close lane can be saturated or stopped,
in which case a release is quarantined rather than forged. Driver callbacks
outside the registered scope still require physical tickets. Host-actor
notification code that throws after emitting DONE, fatal VM errors, late
callbacks after device/driver shutdown, and concurrent actor STOP plus host
close require executed integration verification before #1944 can be closed.
Do not treat the queued wake alone as universal proof of native driver exit.
No approval to cross-org mirror private source is implied.
