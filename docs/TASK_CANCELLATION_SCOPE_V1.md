# Task cancellation and structured ownership — v1 draft

**Status:** host/runtime contract prototype only (Java #1379). Guest `task {}`,
`nb task {}` and `do task {}` execution is gated in Java #1377; native
execution remains gated in LLVM #640. Do not describe this as source-level
runtime support or verified Java/LLVM parity.

## Ownership and policy

- Every live Task has exactly one lifecycle owner: a TaskScope, actor-owned
  TaskScope, or a named supervisor. Handles and observers do not silently
  transfer ownership. `do task` discards the caller's handle, **not** its owner.
- Parent cancellation requests cancellation of every owned descendant;
  child cancellation does **not** cancel ancestors or siblings.
- Cancellation and task failure are different events. A child's ordinary
  cancellation is not a supervisor `panic`. `throw`/`raise`/`panic` failure
  policy is independently selected by its supervisor.
- TaskScope admission and cancellation use one lock: no task is admitted
  after the cancellation snapshot. Linked cancel tokens may add other
  cancellation sources without creating extra lifecycle owners.
- Reparent/adopt must atomically transfer a linear lifecycle capability to
  an accepting supervisor. A naked `disown()` that can orphan work is invalid.
  Neither adoption nor cross-actor lifecycle linking is implemented here.
- Monitor/observer is a read-only completion relationship. Cancellation links
  are directed; Erlang-style failure links must be specified separately.

## Task completion versus physical quiescence

`Task<T>.cancel()` requests cancellation and may make the result Future terminal
**before the executing carrier exits**. The new host `getDone()` returns a fresh
non-producer-cancelling `OresFuture<Void>` observer of the stronger **physical
guest-turn-exit** fence. `cancelAndJoin()` cancels then returns the same kind of
nonblocking quiescence observer. Awaiting that observer is the cleanup barrier.
Cancelling the observer only detaches that observer.

`isDone()` continues to mean *result settled*; `isQuiescent()` means physical
Task exit. The AsyncRuntime Task bridge publishes the quiescence signal only
after the guest TurnExecutor returns (or after admission is cancelled before
entry), including any lexical `finally` executed on its path. External
resources and detached host callbacks not registered as Task-owned operations
are **not** proved complete by this signal.

A completion-only `OresTask` wrapper around a bare OresScheduler Future must
**fail closed** for `getDone()` and `isQuiescent()`: scheduler cancellation may
settle that Future before a guest turn retires. Provide an independent
scheduler exit fence before enabling those APIs for that scheduler.

Proposed *guest* spelling: `await t.get_done()` or
`await t.cancel_and_join()`; the current Java host names are `getDone()`
and `cancelAndJoin()`. Guest member lowering is not implemented.

## Host-only TaskScope prototype

`OresTaskScope.submit(work)` registers ownership before exposing the Task,
and removes membership only at physical Task exit. `childScope()` creates
downward cancellation/ownership. `cancel()` closes admissions and propagates
requests. `close()` cancels then **blocks the host** until owned descendants
quiesce. `submitDetached(work, onFailure)` retains scope ownership and requires
a non-null failure sink.

The host scope is not permission for guest actor code to block a carrier:
`close()` rejects actor turns and AsyncRuntime Task carrier turns. Its
blocking implementation is not a replacement for guest continuation lowering,
and it must not be used from a single-worker OresScheduler turn.

## Compiler and security boundaries

Actor-spawned Tasks must inherit *cancellation, deadline and quota metadata*
but not an actor's state loan, mutex guard, actor execution permit, or heap
mutation authority. Every capture must be moved/copied/shared safely before
launch. Supervising a Task does not grant it message-receive identity or
private actor state. On actor restart, generation-fenced late continuations
cannot resume into a new instance.

The runtime must support cancellation at `await`, `nb select`, channels,
timers and safepoints; cancelling a shared Future's wait must not automatically
cancel its producer. Blocking FFI may resist immediate cancellation and must
not cause the runtime to claim physical quiescence prematurely.

## Cancellation and shutdown hardening (2026-10-18)

The TaskScope host runtime now cancels in two phases: its iterative walk
first seals admissions and invokes the producer cancellation hooks for all
owned children, and only then invokes ordinary synchronous completion
subscribers. This prevents a blocked subscriber on one child from delaying
revocation of its siblings. OresFuture's package-private
`cancelDeferredNotification()` is NOT a general guest API; the caller must
invoke each returned notification exactly once. Direct `cancel()` retains
its established immediate delivery semantics.

Both TaskScope cancellation and physical join use iterative traversal, avoiding
Java call-stack exhaustion on deep supervision trees. An interrupted host
closer restores its interrupt bit after waiting for physical cleanup instead
of claiming success while owned continuations remain active. Other child joins
continue even if one reports an exception, and failed scopes retain the
recorded close failure for concurrent close observers.

New checked-in JUnit coverage addresses: synchronous blocked cancellation
subscribers, interrupted host shutdown, 4,096 nested TaskScopes, and explicit
revocation-before-notification ordering. These tests remain **not executed**
on the exact Java PR head while source-org Actions jobs receive no runnable
steps.

### CI confidentiality hold

P0 security incidents oreslang-platform-conformance #22 and #77 prohibit
new cross-organization private source mirrors without independent reviewed
approval binding the destination numeric repository ID and exact source
snapshot. The canonical policy draft Java PR #1217 is explicitly
NON-AUTHORIZING until both incident gates are completed. Do not attempt a
private-source mirror merely because another organization has Actions minutes.
A private destination alone is not an authorization.

A separate **source-free** synthetic cancellation model successfully ran on
gha-indie-worker/gha-indie-worker-e2e draft PR #12, run 39171032154:
20,011 independent-model assertions across 10,000 synthetic schedules, with
4,096 nested scopes. This is **NOT** exact-tree or executable Oreslang
Java/LLVM verification. No private Oreslang source was copied.

## Outstanding release blockers

1. Deliver scheduler-owned quiescence for OresScheduler continuations; ensure
   all cancellation paths close await registrations before Task exit.
2. Atomic owner-to-supervisor adoption and failure policy matrix, including
   cancellation races against ownership transfer.
3. Compose directed linked cancellation/deadlines with per-Task execution fuel
   and per-actor limits, without cross-domain cancel capabilities.
4. Implement actual Task source type-checking, source lambda state machines,
   guest `get_done`/`cancel_and_join` and actor-owned admission.
5. Verify two-phase multi-child cancellation against exact-head JUnit races, including blocked subscribers, reentrant callbacks and malformed host cancellation hooks.
6. Run exact-head Maven/JDK25 JUnit including cancellation/cleanup races,
   Graal/native-image, LLVM native and cross-backend executable conformance.

Tests checked into this branch exercise the host TaskScope and physical
completion distinction. They are **not** evidence of guest language execution
or verified exact-head CI runs.
