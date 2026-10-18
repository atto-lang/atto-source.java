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

## Directed host cancellation sources (October 18 continuation)

`OresCancellationSource` is a host-only cancellation authority and
`OresCancellationToken` is a read-only observation capability. Tokens have
no `cancel()` operation and do not expose a raw `OresFuture` which could
be cancelled to revoke a producer.

`OresCancellationSource.linked(parentA.token(), parentB.token())` applies
OR semantics: a cancellation of either upstream requests cancellation of
the linked child; cancelling the child never cancels the upstream sources.
Upstream registrations are detached on child cancellation or close, to
prevent retention. The construction-only link direction also precludes
introducing a cancellation cycle by reparenting a live source.

`OresTaskScope.cancelWhen(token)` connects a source cancellation event to
one scope and its descendants. It is **not** Task adoption, a failure link,
a monitor, or an Actor capability grant. Scope cancellation detaches external
registrations and does not affect the token's source. Tokens do not alter
deadlines, CPU fuel, actor heap isolation or quota admission by themselves.

These host callbacks may run synchronously on the source's cancelling
thread. They must never directly run guest continuations. Proper guest
syntax, scheduler-affine delivery, and cross-actor/tenant capability checks
remain outside this Java runtime prototype.

The Future's deferred cancellation notification gate now also fences
late-registered callbacks until the whole owning scope's cancellation
revocation walk completes. Merely flipping the Future's cancelled state
cannot admit a new subscriber before its sibling producers are revoked.
One-shot deferred notification is idempotent.

Additional JUnit fixtures cover directed cancellation, linked-source
OR propagation, late registration, listener detachment, and cancelling
a linked TaskScope with a running child. These tests are checked in but
**not verified executed** on the exact branch head.

## suspends-effect runtime integration

`suspends` is a callable effect of the *current* Task, not a Task producer.
A continuation lowered from `suspends fnc` / a suspension-effect lambda
must keep the same Task identity, owner scope, cancellation domain, quotas,
and generation fence across `OresScheduler.Await` and resume. It must never
create a new `OresTask<T>` simply to express the suspension.

Scheduler-level host tests now model a suspends-style two-resume frame and
prove that cancelling its owner detaches its pending waiter without cancelling
an externally shared producer. A different Task may still await and complete
from the same producer. Late producer settlement must never resume the
cancelled Task.

Compiler-lowered `suspends` callable values, borrow/effect safety,
cancellation propagation through lexical cleanup, preemption safepoints,
actor-affine admission and cross-backend execution are not yet enabled.
A host scheduler-frame test is not evidence that Oreslang source-level
suspends/task interop executes successfully.

## Stack-safe directed-link audit (October 18, 2026)

The earlier `OresCancellationSource.linked(...)` callback called the next
source's `cancel()` synchronously. Nested links therefore consumed one Java
call frame per hop and could overflow the stack before the terminal source
received cancellation. The new package-private
`OresCancellationDispatcher.request(source)` provides a per-thread iterative
queue. A nested downstream request appends to the active queue instead of
recursively entering another source's cancellation walk; the queue is cleared
and its ThreadLocal binding removed on exit.

Committed JUnit regression cases now exercise an **8,192-link chain** and a
diamond-shaped DAG, checking terminal events occur once, existing upstream
subscriptions detach, and unrelated sources remain unaffected. These are
actual Java tests in PR #1379, but exact-head JUnit execution is still blocked
by source-organization zero-step Actions failures.

**Separate evidence:** the independently authored source-free graph model ran
on funded `gha-indie-worker/gha-indie-worker-e2e` draft PR #12 at
[Actions run 39174769429](https://github.com/gha-indie-worker/gha-indie-worker-e2e/actions/runs/39174769429),
with 36,393 passing synthetic assertions, 8,192-link depth, and 10,000
schedules. It executed on a hosted worker but **did not compile or run the
Oreslang Java runtime**, and **did not export private source**.

A remaining limitation is that arbitrary blocking host callbacks are not
preemptible; directed cancellation callbacks must remain host-only and must
not directly resume guest actor/task code. Complete Java/GraalVM regression
runs, actor-affine message delivery, fuel/deadline inheritance, and LLVM
parity are separate release gates.

## Two-phase directed graph revocation audit (October 18, 2026)

A further audit identified an ordering failure in the first iterative linked
cancellation implementation: a synchronous host observer of the first child
could block the cancelling thread before a second sibling's cancellation
callback had even been entered. Iteration alone removed recursion but did
**not** ensure revoke-before-observe across sibling links.

`OresCancellationSource` now registers linked children separately from its
ordinary `OresCancellationToken` observation listeners. The runtime-local
`OresCancellationDispatcher` first atomically claims and traverses all
reachable linked children using a queue, then publishes their terminal
notification Futures. Every newly enqueued cancellation wave is revoked
before the next ordinary notification. Directed child registration still
does not confer ownership, actor identity or upstream cancellation rights.

Committed Java JUnit regressions include a deliberately blocked observer
on the first sibling, an observer that throws, nested cancellation/reentrant
claims, a newly registered child after source cancellation, an independently
canceled child, diamond fan-in, and 8,192-node link depth. The tests remain
unexecuted on an actual Java/GraalVM source runner.

Independent, source-free Node graph oracle on funded
`gha-indie-worker/gha-indie-worker-e2e` Actions run
[39175925002](https://github.com/gha-indie-worker/gha-indie-worker-e2e/actions/runs/39175925002)
executed successfully: 86,393 abstract invariant checks, depth 8,192, and
10,000 DAG trials; no private source copied. This proves **only the model**,
not Java implementation correctness, JUnit execution, actor fairness or
cross-backend conformance. Java fast-ci on the Task branch remains blocked
before a runner starts (zero executed steps).

Remaining race gates: simultaneous graph registration/cancellation, blocked
arbitrary host callbacks (which cannot be preempted), exception and
physical-cleanup accounting, bounded work admission, deadline/capability
propagation, and runtime-specific scheduler/actor quiescence.

## Concurrent scope-claim and retirement verification (October 18 follow-up)

An idempotency audit found a parent cancellation traversal previously skipped
a child scope as soon as its cancellation flag was claimed. A concurrent
canceller can claim that flag before traversing its grandchildren, leaving
a parent `close()` dependent on the other thread making progress. The iterative
walk now revisits already-claimed child subtrees (without reporting a second
winning cancellation claim). Task cancellation is still CAS-idempotent, so
re-walking does not run producers twice or revive cancelled work.

A checked-in JUnit regression models the real intermediate state where
a child has claimed cancellation but not yet visited its grandchild.
It verifies parent cancellation reaches the grandchild, cancels its live
Task, preserves repeat-cancel return values, and joins physical Task exit.

The independent source-free runner model successfully executed
[funded Actions run 39176814665](https://github.com/gha-indie-worker/gha-indie-worker-e2e/actions/runs/39176814665):
50,260 synthetic checks across 10,000 artificial claim/traversal
interleavings and a 4,096-level nested supervision tree. It also probes the
distinction between logical cancellation, physical completion and a failed
trusted retirement outcome. This run **did not contain or execute private
Oreslang source**, and does not validate Java compiler/runtime behavior.

The Task handle's `getDone()` result is intentionally different from
`getAwaited()`: failure/cancellation of the ordinary Task result must not
be mistaken for failed physical cleanup, but failure of a trusted retirement
fence must be preserved in every live `getDone()` observer. Additional Java
JUnit tests now exercise failed retirement, independent observer detachment,
and successful physical cleanup after Task cancellation. A source-local
GraalVM/JDK 25 execution and Native Image pass are still required.
