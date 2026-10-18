# Actor-owned Task scopes — stage 3 integration audit

Status: DRAFT, stacked on Java TaskHandle/TaskScope PR #1407 and cancellation-tree PR #1402.
This file describes real source changes but is **not** evidence of a successful Maven/GraalVM integration run.

## Ownership, admission and failure-closed boundaries

The trusted Java actor runtime now exposes:

```java
TaskHandle<Integer> child = context.runtime().startActorOwnedTask(
        resume -> OresScheduler.done(42));
```

This entry point requires `currentActor` to identify an executing actor turn.
A host with only an ActorRef cannot create or obtain that actor's Task scope.
The runtime refuses admissions if actor stop, fencing, actor finalization or
runtime closure was already published. A racing stop and TaskScope admission
linearize at the scope's synchronized admission gate: a child admitted before
scope cancellation is cancelled; a child submitted after sealing is rejected.

**UNTRUSTED actors fail closed** when using this new staging entry point.
They must continue using `startActorTask`, which enforces explicit logical
fuel/wall-time quotas and bounded admissions; passing them through
`TaskHandle.start` would bypass those controls until tracked admission
is integrated with `startWithWallDeadline`.

## Shutdown semantics

- ActorCell lazily creates exactly one actor-owned TaskScope on first trusted
  `startActorOwnedTask` call, using the actor's existing mailbox-backed scheduler.
  No per-Task actor, mailbox, or OS thread is allocated.
- ActorCell termination marks the actor stopped and snapshots the TaskScope
  under the actor lifecycle monitor. Cancellation is requested **outside**
  that monitor, because it may synchronously invoke Future subscribers.
- Actor finalization now requires the TaskScope completion witness in addition
  to the existing physical carrier-exit, scheduled-dispatch and child-actor gates.
  This prevents actor `done` from preceding quiescence of tracked Task children.
- The TaskScope's completion callback rechecks the actor finalization gate.
  It does not execute guest code and never manufactures a completed actor turn.
- Scope cancellation does not promise bounded latency: synchronous runtime
  callbacks remain a separate follow-up hardening requirement.

## Real-runtime tests committed (not yet executed in Maven)

`src/test/java/dev/oreslang/ActorOwnedTaskScopeTest.java` covers:

1. Self-stop with queued Task (body must never start).
2. Stop while a Task is awaiting a borrowed pending Future (do not cancel producer).
3. Reject Task admission outside an actor execution turn.
4. Successful completed Task outcome survives later actor stop.
5. Host stop racing source continuation must not publish actor done before Task done.
6. Parent stop cancels eight queued Task handles with no leaked executable work.
7. Possession of ActorRef alone never grants Task admission authority.
8. An UNTRUSTED actor cannot bypass the quota-controlled existing task admission path.
9. An actor handler failure cancels owned Tasks without erasing the actor's terminal error.

Run against exact nested PR head:

```bash
mvn -Dtest=TaskCancellationTest,TaskHandleTest,TaskScopeTest,ActorOwnedTaskScopeTest test
```

## Supplemental isolated host-model check

Using OpenJDK 21, a standalone **model of the ActorCell finalization gate**
was compiled against the previously staged synthetic TaskHandle/TaskScope and
scheduler/Future stubs. It passed 1,000 iterations:

- 500 cases where the child task physically remained in execution while its
  result was cancelled; actor finalization stayed pending until physical exit.
- 500 cases where Task children had quiesced but the actor carrier lease was
  still active; finalization remained pending until that lease was released.

A completion Future's state can become visible before its synchronous
notification callback has returned; the test therefore waits for eventual
finalization rather than incorrectly requiring the callback to have run at
the instant `getDone()` first becomes observable.

**Limit:** this model does NOT compile or execute production ActorRuntime,
OresScheduler, or GraalVM/Truffle code. No exact-tree Maven/JUnit source
execution has yet been obtained.

## Important remaining gaps

- Existing source `startActorTask` and untrusted quota-aware task lowering are
  not automatically migrated; both still use existing code paths.
- There is not yet parent task-subscope creation on every lexical source task,
  actor-integrated `do task` failure supervision, or authorized cross-scope
  ownership transfer and hot-reload handoff.
- A TaskHandle remains a Java host/runtime control API, not validated guest
  source Task<T> syntax, and cannot be passed into cross-actor mailboxes.
- Unsupported user native/FFI blocking work cannot be preempted forcibly.
- Source-org GitHub Actions are failing before starting any test steps.
  Cross-organization private source mirroring is *not authorized* while
  security issue #962's protected-source provenance, pinned repository IDs,
  independently approved policy and private/private preflight are unresolved.
  The earlier public mirror exposure is tracked in #832.
- Full Java/GraalVM, LLVM and shared source conformance have not run.

Do not merge any stage until its required real CI checks and independent
security/provenance approval are established.


## October 18 hardening follow-up — cancellation cascade completion

Review of the stage-3 actor-finalization path found that the actor's new
`TaskScope.getDone()` subscriber can re-enter `finalizeStopLocked()` **during**
`terminateTree()`, while the latter is still dispatching cancellation to
other actor-owned Futures and child actors. In an idle actor with a completed
owned Task and a pending owned Future, this permits a scope-completion callback
to publish actor `done` before all pending Future cancellation subscribers
have returned.

**Correction committed on this draft branch:**

- `ActorCell.activeTerminationCascades` records overlapping/reentrant
  termination requests under the existing lifecycle lock.
- `terminationCascadeComplete` prevents finalization until the *last* active
  cascade has dispatched scope cancellation, owned Future notifications and
  child-actor cancellation requests.
- `terminationCascadeFailed` fails closed if an exception interrupts a cascade
  before the full dispatch completes. A later successful reentrant request
  cannot override the failed-dispatch state.
- A `finally` block decrements the counter and revisits finalization only
  when the terminal cascade completion state is safe. The existing guest
  carrier, actor-child, scheduler and quiescence checks still apply.
- Stage-3's Testable TaskScope is synchronized to its stage-2 base
  `TaskScope` and `TaskScopeTest` blob SHAs, incorporating the newer
  callback-outside-admission-lock fix rather than testing an obsolete snapshot.

**Real-runtime JUnit tests (not yet executed):**
`ActorTaskTerminationCascadeTest` adds two focused cases. An actor has a
completed owned Task and a still-pending `ownCurrentActorFuture`. A
cancellation subscriber blocks. The test asserts `ActorRef.done` stays
unpublished until the subscriber unblocks, with one and two concurrent host
stop requests. ActorOwnedTaskScopeTest contains nine prior real-runtime cases.

**Local compiled Java 21 model only:** 20,000 successful finalization-gate
iterations, including concurrent scope/cascade completion, physical carrier
exit, and overlapping stop requests. A further 5,000 fail-closed iterations
verified a fatal/interrupted cascade prevents premature finalization even
when another overlapping cascade completes. Total **25,000/25,000** simplified
model iterations. Neither model executes production `ActorRuntime` or
`OresScheduler`; exact-head Maven/GraalVM execution remains unverified.

**CI safety:** Issue #962 continues to disallow cross-organization private
source transfer without independently reviewed two-repository identity
preflight, pinned source/ref and destination IDs, and the approved protected
allowlist. No private source snapshot is transferred as part of this PR.
Resetting a temporary Git ref alone cannot erase historical private bytes.
