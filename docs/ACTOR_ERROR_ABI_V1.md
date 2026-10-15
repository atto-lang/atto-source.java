# Actor error ABI v1 (GraalVM/LLVM)

The two backends carry the identical `actor_errors.ores` source declarations and
`actor-errors.csv` catalog. Numeric codes 1001–1020 are frozen; new codes must be
appended, not renumbered. These are **guest Error subclasses**, not host Java
Throwable/C++ exception subclasses. Parser acceptance and host catalog tests **do
not** establish executable guest exception lowering on LLVM.

## Supervisor and actor transport rules

- A supervisor receives a **copied, value-only envelope** (numeric code,
  bounded UTF-8 diagnostic, bounded opaque actor/supervisor identifiers).
- Never carry a live guest Error object, stack object, Java Throwable, C++
  exception_ptr, function, callback, actor heap pointer or mutable collection.
- Envelope diagnostics are bounded to 1024 UTF-8 bytes, malformed bytes
  are repaired, and terminal/bidi/zero-width controls are scrubbed. **This is
  not secret redaction**: the host constructor still accepts arbitrary text.
  Runtime callers must use a fixed, public-safe diagnostic (for example the
  frozen error key) instead of passing host exception messages, file paths,
  capability values, payloads, tokens or stack traces. A future trusted trace
  channel must enforce disclosure authority independently.
- Full traces require a trusted, explicitly requested diagnostic channel and
  must be generated on the originating actor before teardown. A guest-supplied
  `include_trace` request alone does not grant trace authority.
- `cancelled`, deadline, isolation, revoked capability, fencing and runtime
  invariants are control-plane terminals. They are not caught by `trap` and
  must not be converted to success by local `on_throw` / `on_raise` hooks.
- Recoverable send/mailbox/remote failures may be returned as typed
  `Result<_, Error>` by their APIs; they must not bypass admission/ownership.
- Supervisor priority remains host fatal/process exit > cancellation > panic
  > raise > throw; a lower-priority cleanup error cannot hide higher priority.

## Integration stages

1. **This change:** freeze guest declarations and code registry, expose
   bounded host-only error envelopes with direct JVM/native tests.
2. Wire source-level constructors, inheritance checks (only Error-derived
   values may throw/raise/panic) and typed Result/serialization to both
   compilers, using real guest conformance fixtures.
3. Connect runtime send/spawn/stop/supervisor paths and validate all actor
   handoff points, including cancellation and teardown, with native Image and
   LLVM end-to-end evidence.

Do not infer stages 2–3 are complete from existence of these files.

## Typed actor CONTROL slice (October 14, 2026)

Java/GraalVM now parses `raise expr` and `panic expr` as distinct, terminal
actor effects. The typechecker requires a non-suspending actor activation and
an owned, nominally proven `Error`-derived value. Outside actors, borrowed
values, `Any`/`Unknown`, structural imitations and effect statements inside
`finally` are rejected. `recover` and actor pseudo-handlers remain gated.

Inside an actor turn, the evaluator validates the guest instance, **drops
the instance**, and emits a stackless `GuestActorEffectSignal` holding only
`RAISE` or `PANIC`. Neither `trap` nor explicit source `catch` may
consume the signal, even through a wrapped host exception. On terminal actor
failure, the runtime publishes an immutable `ActorFailureNotice` to a bounded
nonblocking **host/supervisor-only CONTROL queue** and a bounded, actor-owned
direct-parent CONTROL inbox. Both offers run after cleanup and the actor-exit
hook, before termination observers are released. Only bounded nonblocking
queue offers run while the finalization monitor is held. The notice contains
actor identity and structured-parent identity
(if present), effect kind and frozen actor-supervision ABI code 1009; its
diagnostic is a fixed protocol string, never guest or host exception text.
A full queue increments a dropped counter instead of blocking an actor.

The host notification queue remains **host-observable**. Parent actors can
independently poll their own direct-child CONTROL inbox during an actor turn;
this is **not** automatic `on_raise`/`recover` dispatch. Parent inboxes are
lazily allocated on first child failure (256 entries maximum), independent
of ordinary mailbox capacity; overflow has an actor-owned dropped counter. Scheduler-domain delivery,
structured recovery hooks, native guest LLVM lowering and end-to-end guest
actor tests are separate merge blockers. Tests have been added to exercise
real host actor turns and queue publication, but their execution is not yet
CI-verified. No live guest Error crosses actor heaps.

## Current hard blockers (October 14, 2026)

- Java recognizes a compiler-owned, non-generic nominal `Error`
  hierarchy. `ThrowStmt` now has **a restricted local lowering**:
  `try { throw new GuestError(...); } catch (e) { ... }` passes
  Error-only type checking and gives `e` the original Oreslang guest object,
  never a host Java `Throwable`. Outside actors this is allowed **only** inside a
  lexical `try`. Synchronous actor startup and receive handlers can catch
  locally, or escalate an unhandled throw to payload-free `raise` at their
  source boundary. Async/suspending callables, detached callbacks, and
  borrowed errors remain rejected. `trap` does not consume
  this internal typed guest signal. Host exceptions keep their existing
  explicit-catch policy; cancellation and panic remain uncatchable.
- All statement visitors (ownership, borrow validation, capability admission,
  return/finally cleanup and incremental tree shaking/dependency scanning)
  now traverse the `throw` operand. `finally` may not throw.
- Synchronous Java actor `raise`/`panic` and actor `throw`-to-`raise`
  are now present on the draft branch. `recover`, source actor
  `on_throw`/`on_raise`, automatic dispatch, restart policies and suspended
  unwind are **not implemented**. These are not yet full exception semantics.
  `NativeGuestErrorRootTest` and `TypedExceptionalEffectAdmissionTest`
  cover the new slice, but **the added tests have not executed in CI**.
- The LLVM compiler supports only a source subset, and `src/core` is not an
  executable native core loader. Its sources do not establish LLVM guest
  inheritance, constructors, typed effects or catchability.
- The host `recoverable` value is **classification metadata**, not a proof
  that the runtime correctly catches, escalates, retries or supervises it.
- The cross-backend static verifier `scripts/verify_actor_error_abi.py`
  freezes source/host names, codes and declared policy; it does not exercise
  actor execution or the wire serializer. Do not mark the PR production-ready
  until executable guest inheritance + Error-only throw/raise/panic + transport
  and supervisor tests pass on both backends.


## Further failure-boundary audit — October 14, 2026

- A synchronous actor `receive` containing `loop` can execute in a
  heap-owned cooperative `SourceTask`, rather than returning directly through
  the synchronous actor boundary. Its `resume` exception path now converts
  any uncaught `GuestThrowSignal` into a shared, payload-free `raise` CONTROL
  signal before a host Future may retain the guest Error object.
- Local Oreslang `try/catch` still consumes the original guest Error before
  the task boundary. Linked-source tests cover cooperative-loop escalation,
  local catch, and the absence of a `GuestThrowSignal` in the resulting host
  Throwable cause/suppressed graph.
- Cleanup suppression now redacts direct **or wrapped** `GuestThrowSignal`
  values before adding them as suppressed diagnostics to a host Throwable.
  Scanning is identity-bounded to 64 nodes; oversized graphs fail closed.
  A targeted JVM test checks direct, nested and suppressed guest signals.
- **Unverified:** These changes are committed but have no passing exact-head
  JUnit or Native Image evidence. No scheduler restart or source on_* dispatch
  is claimed.


## Opt-in automatic host supervisor CONTROL dispatch (October 14, 2026)

The Java host runtime now defines `ChildFailureHandler<M>`, extending the
host `Behavior<M>` contract with
`onChildFailure(ActorFailureNotice, ActorContext<M>)`. This callback is
**host-runtime-only**; Oreslang source `on_raise`, `on_throw` and `recover`
remain gated, as does automatic restart.

After a failing child's code state and actor-exit hook finish, its terminal
notice is copied into the structurally owning parent's bounded CONTROL inbox.
The child finalizer then requests a best-effort asynchronous wake from the
runtime's **existing** actor dispatcher, never invoking a parent callback
inline. The parent's admitted actor turn prioritizes control notices before
continuations/user mail and invokes a registered handler on that parent's
serialized execution lane. Manual inbox polling still works for actors that
do not implement the optional handler. The callback cannot see a guest Error,
guest heap pointer or Java cause; only the frozen value-only envelope.

CONTROL admission and retirement are serialized by the parent lifecycle
monitor. Parent cancellation or retirement forbids new callback admission.
If a dispatcher rejects the automatic wake, the notice stays queued for
manual polling and an actor-local rejected-wake counter increases; **automatic
delivery is not guaranteed under scheduler saturation** until a reserved
CONTROL admission path exists. Closing a supervisor counts unread notices
as dropped. A separate host-visible aggregate
`childFailureControlNoticesDropped()` remains available after parent shutdown;
the independent host audit queue retains its own loss counter.

Regression tests cover an idle parent receiving CONTROL without a second
user message, a multi-level raise-to-panic escalation, cancellation preventing
callbacks into a retired parent, and explicit accounting for notices discarded
when a non-handling parent stops. All new tests remain **unverified** while
GitHub Actions cannot execute jobs. Do not merge based solely on source review.

## Single-hop CONTROL wake and teardown audit (October 14, 2026)

Automatic host `ChildFailureHandler` notifications now submit **one** scheduling
ticket directly to the parent's existing actor dispatcher. The old two-hop
wake submitted a separate dispatcher job whose second submission could reject
and stop the parent. CONTROL-specific scheduling instead releases an
unadmitted dispatch ticket on rejection, preserves the parent actor and its
queued immutable notice, and increments `childFailureWakesRejected`. Ordinary
user-message dispatch continues using fail-closed rejection policy.

An unexpected runtime exception from the parent wake path is isolated from
the child's finalized lifecycle and is counted as a failed wake; process-fatal
errors are not swallowed. This is still best-effort notification when the
dispatcher is saturated, not a guaranteed CONTROL lane. The parent remains
eligible for later scheduling and manual polling.

Added a regression test with a blocked child exit hook: no automatic parent
handler is allowed to observe a notice until the child hook finishes; the
handler must then run on the parent's serialized actor execution lease.
Tests have **not** been executed successfully on this branch while GitHub
Actions jobs fail before reporting steps.

## Bounded reserved CONTROL scheduling (October 14, 2026)

Supervisor CONTROL dispatch now has a separate, finite admission budget:
`min(64, maxActors)` tasks per carrier pool. In lane/CPU affinity modes
`ActorAffinityDispatcher.executeControlOnLane` uses independent CONTROL
permits and dispatches on the supervisor's **existing** lane. With affinity
disabled, `ReservedActorDispatcher.executeControl` applies the same rule
over the existing pooled workers. Physical executor queue capacity is
increased by the exact reserve so ordinary submissions cannot occupy that
reserved headroom; no additional threads, per-actor executors or global
guest callbacks are created.

CONTROL rescheduling after a parent turn retains the same reserved admission
path; a saturated CONTROL reserve can borrow unused normal admission before
a wake is reported rejected. The actor's per-actor scheduling ticket still
prevents duplicate carrier admission. All permits are released at run start,
rejection or shutdown, including a host executor throwing unexpectedly.
A failed CONTROL wake does **not** terminate the supervisor or discard
the already-admitted immutable notice; loss/rejection counters remain visible.

**Scope of guarantee:** an ordinary workload at its configured admission
limit cannot, by itself, exhaust the reserved CONTROL permit budget.
This is *not* an unbounded-delivery guarantee: if both budgets are exhausted
or workers stop making progress, a wake can still be rejected and manual
polling / later actor activity may be needed. A policy for sustained
CONTROL exhaustion, restart generation fencing, and source-level
`on_raise`/recovery remain outstanding.

`ReservedControlDispatcherTest` deterministically covers both affinity
modes, separate normal/CONTROL permits, saturated admission, unexpected
physical submission rejection, and shutdown cleanup. These tests have not
been confirmed passing on an executable exact-head CI runner.


## Actor-local on_throw(Error): bool receive-turn slice (October 14, 2026)

The Java frontend now admits a **private, nonstatic, nongeneric,
nonsuspending** actor method exactly of the form:

```oreslang
on_throw(Error error): bool {
    // Runs on the failing actor's original execution domain.
    return true;
}
```

- This hook is invoked only when a guest `throw` escapes a **receive** turn,
  including a cooperative `SourceTask` receive. Lexical guest `try/catch`
  takes precedence. `on_start` and partially constructed actor-state failures
  still fail closed; they do not invoke the hook.
- `true` acknowledges this turn's uncaught guest Error; `false` or an
  unavailable hook escalates as a payload-free `RAISE`. An invalid/missing
  return, a nested uncaught guest Error or an attempted suspended/looping hook
  also escalates. Only the original actor-local activation sees the Error
  instance. No Error, guest stack or host exception carrying it is published
  to supervisor CONTROL.
- Explicit `raise` and `panic` **bypass** `on_throw`; cancellation and
  host fatal effects are not consumed by it. Calling `self.on_throw` directly
  is rejected; the VM owns this lifecycle hook.
- This is intentionally **not** source `on_raise`, `recover`, automatic
  supervisor restart or LLVM guest effect lowering. Those remain fail-closed.
- The linked-source regression class `SourceActorOnThrowExecutionTest`
  verifies both synchronous and cooperative receive paths, local catch
  precedence, false/true handling, nested throw redaction, invalid signatures
  and uncatchable raised/panicked effects. These tests are committed but
  **not yet known to pass** on an executed exact-head JVM CI job.

## Epoch-fenced CONTROL retry on scheduler release (October 14, 2026)

Supervisor CONTROL scheduling now has a **bounded, event-driven** retry
registry instead of relying only on the next user mailbox message.
`ControlWakeRetryQueue` holds at most the actor pool's configured
`maxActors` host-only Runnable identities, with **one pending registration
per supervisor ActorCell**. Both `ActorAffinityDispatcher` and
`ReservedActorDispatcher` notify one registered waiter when an admitted
task starts and releases a physical queue slot. No timer, worker dedicated to
retry, busy loop, or guest callback is involved. Ordinary and CONTROL
admission budgets remain independent.

The caller snapshots a dispatcher release epoch **before** attempting the
first CONTROL wake. When both CONTROL and ordinary admission reject, it
registers a callback using that epoch. A slot released between rejection and
registration causes an immediate one-shot callback; a later release dequeues
one waiter FIFO. Registrations are coalesced per parent actor. Parent
finalization cancels any still-pending registration. Failed queue submissions
release permits **without signalling** waiters (they did not free a physical
queue slot), avoiding recursive rejection cascades. Shutdown clears the
bounded registry without executing queued callbacks.

A retry still uses the actor's original serialized scheduling ticket and
physical dispatch lane. Its immutable failure notice remains in the parent
CONTROL inbox; no guest Error or child heap pointer enters the queue.
`childFailureWakesRejected` counts failed submissions. If the bounded retry
registry itself cannot accept a waiter, the rejected notification remains
host-auditable and can be consumed on a later legitimate parent turn; this
is **not** a guarantee of progress when all carrier workers are permanently
blocked or the scheduler is closed.

New deterministic JUnit fixtures `ControlWakeRetryQueueTest` and
`ReservedControlDispatcherTest` exercise release-before-register,
register-before-release, single-delivery FIFO, cancellation, shutdown,
admission saturation in both affinity modes, and re-admission on a slot
release. These fixtures are selected by
`scripts/verify_actor_exception_jvm.sh` but have **not** passed the exact
GraalVM/JDK25 repository test suite due to GitHub Actions jobs failing
before steps execute. A separate isolated Java 21 smoke harness exercised
eight pure retry-queue assertions; that is not proof of full runtime
correctness, AOT, or native parity.

Structured one-for-one restart, restart intensity limits, source-language
on_raise recovery and LLVM guest lowering remain unimplemented.

## Host-only one-for-one restart authority and terminal barrier (October 14, 2026)

Java `ActorRuntime.restartCurrentChildOneForOne(notice, retiredHandle,
replacementFactory)` is now a **host-runtime-only** restart primitive,
available only inside the structurally owning parent's current
`ChildFailureHandler.onChildFailure` callback. The exact immutable notice
object must be the current callback's notice, its actor and supervisor IDs
must match the original parent-owned `ActorHandle`, and the old child must
have left the live actor registry **and** completed its terminal Future.
The authority is consumed **before** replacement admission so the same
notice cannot spawn multiple replacements even if the factory fails.

To enforce this without blocking the parent actor, global host audit notice
publication remains before terminal observers settle, but delivery to the
parent's per-actor CONTROL inbox is deferred until *after* unregistration,
the child exit hook and the done/finalized Future barriers. Parent lineage
acceptance recognizes only the original runtime-owned child cell while
rejecting forged IDs or unrelated owners. A concurrent parent shutdown
drops and accounts for the undeliverable notice.

Replacement spawns under the **original effective child policy and kind**,
with a newly allocated ActorId and strictly increasing ActorRef generation.
The retired ActorRef/ActorHandle cannot silently redirect to the new actor.
A parent-local, actor-turn-confined restart intensity cap allows at most
three calls to this primitive in a rolling one-minute window. The cap
does not apply to arbitrary explicit user spawns, so it is not a general
admission quota or automatic restart policy.

JUnit fixtures cover a successful single restart with a fresh generation,
replay rejection for the same notice, stale old-reference message rejection,
and a fourth restart being rejected by the intensity budget. These tests are
committed but **not verified passing** at the exact branch head while
GitHub Actions jobs fail before starting executed steps.

This does **not** implement source-language `on_raise`, policy-configurable
backoff, durable restart state across parent failures, `one_for_all`/
`rest_for_one`, or LLVM guest restart parity. Keep the PR draft until full
compiler/runtime, AOT and lifecycle regression tests execute successfully.

## Incarnation authority and identity collision audit (October 14, 2026)

The Java actor runtime now treats the **exact registered `ActorRef` object plus generation** as the incarnation capability for ref-based operations, instead of trusting an `ActorId` lookup alone. `send`, `stop`, `cancel`, force cancellation, `gracefulStop`, `drain`, readiness, host placement/code-image/mailbox introspection, and parent/child enumeration reject or ignore a stale or forged reference, even if its ID and numeric generation match a live actor. A revoked older reference cannot operate on a replacement actor.

Actor registration now uses `putIfAbsent` rather than `put` for every root/child path. A generated UUID collision must fail closed and roll back its actor slot rather than overwrite an existing live cell; for inherited ActorGroups, the runtime claims the actor identity **before** publishing the managed worker, and uses `putIfAbsent` there too. The existing rollback removes only entries that match the new cell/ref.

`ActorIncarnationIdentityTest` uses host-test-only reflection to create detached references that carry an identical ActorId and either an older or equal generation; neither can send, cancel, stop or inspect the live incarnation. The original actor remains operable. The test is included in `scripts/verify_actor_exception_jvm.sh`. This regression is **committed, not yet executed successfully** in the project JUnit/JDK25/GraalVM test suite.

The existing reservation/CONTROL retry/one-for-one restart code remains draft; no source-language `on_raise` recovery, full LLVM guest execution, formal lifecycle model proof, or successful exact-head CI is claimed.

## Affinity-aware CONTROL retry fairness (2026-10-14)

The existing bounded `ControlWakeRetryQueue` now tags waiting host callbacks
with the physical affinity lane of their owning actor. Each actual queued-turn
start still releases one physical queue permit and signals **at most one**
pending retry; when the release originated on lane L, the scheduler prefers
a waiting callback for L within the first 16 FIFO entries. If none is found,
the globally oldest waiter runs instead. This global fallback is essential:
ordinary and CONTROL admission semaphore budgets are shared between lanes,
so a release from a different lane may be the only event that makes the
supervisor admissible. A strictly lane-local retry queue could deadlock.

The bounded scan keeps physical dispatcher handoffs from doing unbounded
per-turn queue traversal. `ActorRuntime` passes the parent actor's assigned
lane into the host-only retry registration. Non-affinity pools retain the
existing FIFO and epoch handoff behavior. Tests cover same-lane preference,
cross-lane progress and the 16-entry scan limit; the focused JVM script
already includes the updated test class.

**Verification scope:** an isolated Java 21 compilation and execution of the
same retry-queue logic completed 36 assertions. That is not the full Oreslang
JDK25/JUnit/GraalVM runtime build. The exact PR's focused Actions job still
returns `failure` with no executed steps or useful logs. Both private-CI
destination allowlists are empty, so no cross-org source mirror has been
authorized. Keep this draft and do not merge.

## Structured shutdown and registry finalization barrier (2026-10-14)

The structured parent/child teardown order now requires a child's host
`ready`, `done` and internal finalized Futures to settle **before** the
runtime unregisters that child's ActorId and before the owning parent's
`childFinalized` releases its structured-child barrier. This closes two
independent races:

- `ActorRef.awaitTermination` historically interpreted absence from the
  runtime registry as physical completion. Removing the cell before
  `doneFuture` callbacks completed allowed early successful termination.
- A stopping parent could finalize immediately after receiving
  `childFinalized` even while its child's own `done` observers were
  still pending. The parent may now finish only after the child has
  settled its own terminal Futures.

The terminal host audit notice is still published ahead of the done
observers. The parent-facing immutable CONTROL notice remains last, only
after physical teardown, completed Futures, unregistration and the
structured owner unlink. `one_for_one` restart therefore continues to
require the exact terminated child handle and completed removal.

Two deterministic JUnit tests, `awaitTerminationDoesNotOvertakePendingDoneObservers`
and `parentShutdownBarrierCannotOvertakeChildDoneFuture`, exercise these
barriers with controlled CountDownLatch gates. `ControlWakeRetryQueueTest`
also now includes 4,096 registrations competing with concurrent slot
releases and cancellation, checking one-shot callback delivery,
conservation of noncancelled callbacks and a zero-leak queue.

An independent Java 21 `javac`/runtime stress harness compiled the
current retry-queue logic and completed **10,000 concurrent registrations**
across eight threads with no duplicated callbacks, no pending leaks and no
lost noncancelled registration. This is narrowly scoped evidence; it does
**not** establish passing repository JUnit or GraalVM Native Image tests.

Latest sampled GitHub Actions jobs in Java and LLVM had empty runner
identities, zero executed steps and terminated in roughly two seconds.
Neither their results nor absent logs prove exhausted Actions minutes.
Both source repositories' protected destination allowlists remain empty;
cross-organizational private-source mirroring is therefore not authorized.
Keep the actor exception PRs draft and unmerged.

## Retry epoch ownership and exact-blob stress verification (2026-10-15)

The scheduler's release-epoch handshake now retries **the supervisor whose
submission failed** if a physical admission slot was freed before its
registration. Previously, registration appended the new callback and dequeued
some possibly older/lane-matching callback. With an existing backlog, that
could leave the newly registering supervisor waiting forever when no further
physical queue-slot release occurred. Existing FIFO/lane-aware wake behavior
is unchanged for later releases; no entry is allocated for an immediate
epoch-gap retry. The bounded registration cap remains enforced for genuinely
pending callbacks.

The regression `missedReleaseRetriesTheNewSupervisorRatherThanAnOlderWaiter`
reproduces the two-existing-waiter case and verifies the exact new supervisor
is retried immediately while an older waiter retains its registration.

**Executed independent test:** the exact Java source file was compared against
the GitHub blob SHA
`fe3387a9a179597569fb88d152cfbe796d8a1281`, compiled using Java 21,
and exercised by a 12-thread synthetic harness with **80,000 registrations,
80,019 assertions, 0 duplicate callback deliveries and 0 retained queue
entries**. All noncancelled callbacks were delivered. Additional assertions
checked reentrant registration, priority of the missed-epoch caller, benign
retry assertions and fatal VM signal propagation. This is a host-only helper
test, **not** evidence that the full Java actor runtime, JDK25/GraalVM
Native Image or LLVM guest runtime passed.


## Dequeued CONTROL cancellation fence and retained quota (2026-10-15)

`ControlWakeRetryQueue` now uses an identity-bearing `Retry` ticket, rather
than a plain deferred `Runnable`. The ticket stays visible to `cancel()`
or `close()` from the instant a physical slot release dequeues the wake
until its **callback-start admission** is linearized under the queue monitor.
Cancellation before that point revokes the ticket and refunds its retained
quota exactly once. Cancellation after start cannot roll back a callback
already executing; downstream actor generation/ownership checks remain
mandatory. Cancelling a ticket also removes its reference from the
in-transit tracking set immediately, so a delayed carrier does not pin
that reference in the queue.

A single bounded retention counter continues to cover pending, deferred,
and dequeued-but-not-started callbacks; fresh/stale-epoch retry handling,
FIFO/lane-locality fairness, fatal callback cleanup, and subsequent valid
re-registration must remain intact. No guest callback is executed inside
the queue monitor.

**Local exact-class verification:** Java 21 `javac -Xlint:all -Werror`
compiled the file with Git blob SHA
`09d83f5cd371823ab529baf428f410dbf4c6f7a4`.
A package-local harness executed 200 deterministic pre-start cancel/close
handoff trials, 600,000 reentrant registration attempts, 75,000
registrations across 12 threads, and fatal-callback cleanup, all passing.
The deterministic handoff trial pauses a carrier after dequeue using a
host-only reflective ThreadLocal gate; the committed JUnit and standalone
stress runners include the regression and check immediate ticket unlink.

**Verification limits:** this is exact-file JVM evidence, not execution of
the checked-in full JUnit suite, Maven integration, GraalVM Native Image,
or compiler guest exceptions. [Focused Actions run #38781817261](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/38781817261)
failed with zero executed steps, so CI has not verified this change.
A private, source-free Benefactor runner probe completed three steps
successfully, but the protected-main source-transfer allowlist remains empty.
No private source objects were mirrored to another organization.

