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


## Actor finalization lock-order hardening — October 18, later pass

Review found an additional pre-existing lock-order hazard in `ActorCell.finalizeStopLocked()`:
it called `sourceScheduler.close()`, the actor exit hook, output closure, actor
group deregistration, and terminal `OresFuture` subscribers while holding
`lifecycleLock`. A synchronous host callback could therefore block another
thread from acquiring the actor lifecycle monitor or create a cross-actor
deadlock during termination.

**Correction on the stage-3 draft branch:**

- `finalizeStopLocked()` now only evaluates existing physical carrier,
  continuation, managed child-actor, TaskScope and cancellation-cascade gates
  under `lifecycleLock`, then claims a single `finalizationClaimed` transition.
- After the lock is released, one **host virtual finalizer per terminating
  actor** performs scheduler close and runtime-owned teardown independently
  of dispatchers being shut down. No thread is created per Task, continuation,
  or TaskScope. Normal Task scheduling is unchanged.
- Output closure, exit hook, actor deregistration, and terminal Future
  notifications are also outside the actor lifecycle monitor.
- A concurrent stop observes `finalizationClaimed` and cannot duplicate
  cleanup or overwrite the actor's terminal cause.
- A trusted exit hook calling `stop()` on its own actor does not wait on
  its own finalizer: `awaitFinalized` recognizes the current finalizer thread.
- Source scheduler shutdown retains its earlier best-effort RuntimeException
  handling. A graceful actor is not converted into a guest failure solely
  because closing an already-quiescent scheduler throws.
- The actor's `finalized` witness remains gated on runtime teardown rather
  than just on Task cancellation requests.

**Five real-runtime JUnit tests now exist in**
`src/test/java/dev/oreslang/runtime/ActorTaskTerminationCascadeTest.java`
(the earlier two cascade tests plus three new lock/finalizer tests). The
stage-3 ActorOwnedTaskScopeTest still contains nine tests. New cases assert
that a blocked actor exit hook does not hold the lifecycle monitor, two
concurrent host stops run only one exit hook, and a reentrant stop from the
exit hook does not self-deadlock.

**Local verification:** a standalone OpenJDK 21 asynchronous-finalization
model compiled with `javac -Xlint:all` and passed **2,000/2,000** cases
(blocking hook/monitor isolation and finalizer self-reentrancy). Earlier
synthetic cascade-model exercises passed 25,000 iterations. These models do
**not** compile production ActorRuntime, do not replace the 14 new actual
source JUnit cases, and are not Maven/GraalVM integration evidence.

**Known follow-up risks:** Creating one host virtual finalizer per actor
termination should be capacity/storm-tested; finalizer thread-creation
failure is not yet covered by a fallback. Abnormally slow host Future
subscribers may still delay terminal callback draining. Audit
`ActorRef.awaitTermination` versus the temporary actor-registry removal and
`doneFuture` publication interval under real scheduling stress. Untrusted
Task admission continues to use the existing quota-checked implementation.

**CI evidence:** The latest inspected jobs on the stage-2 and stage-3 PR
heads failed with no executed steps, while requests for job logs returned
missing-blob errors. This does not establish a Java compiler failure or
prove the cause is exhausted GitHub Actions minutes. Security issue #962
remains open; no private-source cross-organization mirror was attempted.


## Terminal-witness consistency hardening — current pass

Auditing the post-unlock actor finalizer found a second correctness issue:
`ActorRef.awaitTermination(timeout, unit)` previously treated the absence
of an `ActorCell` from the runtime actor map as a successfully finalized
actor. But `completeFinalizationOutsideLock()` first unregisters the actor
and only later publishes the non-cancellable `ref.doneFuture`. A different
host thread could therefore get `true` from `awaitTermination` while
`ref.getDone().isDone()` was still false. A similar gap existed when
`ActorCell.finalized` was set before `doneFuture` publication.

The fix waits for the actual `ref.doneFuture` terminal state, both while an
`ActorCell` remains registered and after registry removal. The helper
handles failed actor outcomes as completed terminations, preserves bounded
timeouts and interruptions, and avoids joining from the actor's own host
finalizer callback. This also updates supervisor/host `stop` and runtime
`close` paths that use `ActorCell.awaitFinalized`.

Three real-runtime JUnit tests were added to
`ActorTaskTerminationCascadeTest` (now eight methods) for successful
`awaitTermination` followed by an immediately observable `done` Future
over 100 actors, exceptional completion after actor failure, and zero-timeout
polls during blocked exit hooks. Together with nine
`ActorOwnedTaskScopeTest` methods, this stage now has **17 committed
production-source JUnit tests**, all pending real JDK/GraalVM CI.

A *standalone model*, which does **not** compile production ActorRuntime,
used OpenJDK 21 to verify **800/800** registry-removal-vs-done-publication and
exceptional-completion orderings. The Java 21 static-helper compilation
check also passed. These are local ordering checks, not source CI evidence.

This Stage-3 branch was cleanly rebuilt as a new commit on the latest
Stage-2 TaskScope head, preserving the original four Stage-3 source/test/doc
blob identities and inheriting the current Stage-2 source tree. Old
Stage-3 revisions remain recoverable on
`backup/actor-task-stage3-audit-20261018`. GitHub reported a four-file
Stage-3-only diff immediately after the ref update.

**CI:** At the rebased source head, 13 GitHub Actions workflows failed, and
six sampled `ci`, `fast-ci`, and heap-placement jobs each had **zero
executed steps**. No compiler verdict, runner-minute balance or actual
Maven/JUnit execution can be inferred from those failures. The protected,
reviewed cross-organization private-mirror authorization remains unresolved
in #962; the previous source-leak incident is tracked by #832. No private
source bytes were copied into a different GitHub organization.

**Further review required:** the finalizer's one-virtual-thread-per-actor
admission needs stress/failure-injection coverage; actual host terminal
callback dispatch should be tested under large actor teardown storms.
Stage-1 and Stage-2 cancellation changes continue independently and must
be kept synchronized before merge.


## Termination cascade applies without TaskScope

Follow-up source audit identified a lifecycle hole: previous Stage-3 gating checked
`activeTerminationCascades` and `terminationCascadeComplete` only when an actor
had previously created `actorOwnedTasks`. Actors with no tracked Tasks can still
own pending `OresFuture`s and descendants; synchronous cancellation callbacks
could be running when actor `done` was incorrectly published.

`finalizeStopLocked()` now requires the termination cascade barrier for EVERY
actor. `terminateTree()` counts concurrent/reentrant cascades and closes the
barrier in `finally` unconditionally, while the additional TaskScope quiescence
check remains conditional on an actual owned scope. An incomplete/fatal
cancellation cascade still fails closed.

`ActorTaskTerminationCascadeTest` now contains **9 JUnit methods**, including
an explicit no-TaskScope actor with a deliberately blocked owned-Future
cancellation callback; this test asserts that `done` and zero-timeout
`awaitTermination` remain incomplete until the callback is released.
`ActorOwnedTaskScopeTest` retains 9 methods (18 total Stage-3 JUnit methods).
These production-source JUnit cases are committed, **not yet executed**.

CI remains unusable at source-org workflow level until runner admission is
diagnosed; do not infer whether minutes or billing is the actual cause from
zero-step failures. Cross-org private mirroring remains prohibited by #962.


## Authenticated checkpoint cancellation inherited from Stage 2

Stage 3 now incorporates the latest Stage-2 cancellation changes in its
source tree. Compiler-selected safe checkpoints carry the identity of their
own TaskCancellation authority and OresScheduler logical Task domain, so
an unrelated or nested Task cannot replay a cancellation marker to cancel
its own result. A Task may complete a running synchronous `finally`
before cancellation, but cannot enter another normal resume afterward.

`OresScheduler.TaskRunner` records a successful checkpoint's terminal
transition first and publishes its actual `OresFuture.cancel(false)`
after the guest turn and scheduler-bound context unwind. Therefore the
underlying TaskHandle result Future reports `isCancelled()` as well as
the TaskHandle `Cancelled(reason)` outcome, including synchronous inline
self-cancellation before Future binding. A previously winning wall deadline
retains failure precedence. Stage-3 actor `done` continues to require
TaskScope physical quiescence and full termination-cascade completion.

Stage-2 exact source currently has 37 TaskHandleTest and 17
TaskScopeTest methods; Stage 3 additionally has 9 ActorOwnedTaskScopeTest
and 10 ActorTaskTerminationCascadeTest methods. These committed tests have
not executed against the complete GraalVM runtime while Actions jobs continue
to fail without executing steps. Local simulation evidence is not a substitute
for real source CI. No cross-organization private source mirror has been made:
security issue #962 remains the blocker.


## Finalizer multi-resource teardown hardening (2026-10-19)

The previous `ActorCell.completeFinalizationOutsideLock()` used a **single**
outer `try` around scheduler shutdown, mailbox reservation draining,
select-cursor clearing, output stream close, memory-slice close and
actor exit hook. If an early resource action threw, later resources were
skipped, although actor deregistration and its done-Future publication
would still proceed. That is unsafe for Task cancellation and actor
lifetime reclamation.

The updated finalizer attempts **every** release independently, outside
`lifecycleLock`. `ActorRuntime.appendCleanupFailure` retains the first
resource error and suppresses subsequent independent errors; a duplicate
Throwable is not self-suppressed. Scheduler `RuntimeException` close
handling and nonfatal external exit-hook exceptions remain best-effort
by policy. Fatal exit-hook errors are captured until release and
deregistration have also been attempted. The actor's primary termination
cause still has precedence over incidental shutdown errors.

Two production-source JUnit methods in
`ActorTaskTerminationCascadeTest` validate first-error identity,
suppressed-order preservation, duplicate-error handling and null
validation. This class now has **12** JUnit methods, together with
**10** in `ActorOwnedTaskScopeTest`, for **22** Stage-3 methods.
They are **committed, not executed in Maven/GraalVM CI**.

Stage 3 was also merged from current Stage-2 head
`6a872ab614deeb203fa5c2b6950a0a1c788d638d`, preserving the four
actor-specific files. Stage-1 and Stage-2 heads have independently moved
and are both currently reported non-mergeable, so they were not
force-rewritten. Real release acceptance still requires reconciling
those upstream histories and obtaining exact-source JDK25/GraalVM tests.
The proposed private cross-org Git-tree mirror remains unauthorized
while security gate #962 is OPEN.


## Post-merge mainline integration — October 20, 2026

The Stage-1 cancellation authority PR #1402 and Stage-2 TaskHandle/TaskScope
PR #1407 are **merged and closed**, not outstanding drafts. Current main
contains both runtime implementations but also adds CPU physical-turn-gate
admission, shard leases and snapshot leases to ActorRuntime. Copying Stage-3's
older entire ActorRuntime would remove these mainline features.

This integration uses a three-way source patch against exact earlier Stage-3
base, applying 7 nonconflicting hunks to the latest mainline ActorRuntime and
manually reconciling the conflicting finalization block. The finalizer retains
mainline shard and snapshot lease revocation, attempts every independent
revocation even after a previous failure, and still waits for actor-owned
Task quiescence before publishing done. The source mainline physical carrier
gate and CPU pool sizing are preserved.

These are committed source artifacts but remain **unverified by production
Maven/GraalVM CI**; security issue #962 still blocks any unreviewed private
cross-organization mirror. Do not merge until exact-source CI and shard/Task
shutdown stress tests are verified.


## Direct-main integration and structured-child witness hardening (2026-10-20)

**Current dependency reality:** PR #1402 and PR #1407 have already been
merged and closed. The old Stage-3 base on the former Stage-2 branch was
stale; current main also introduced `ActorPhysicalTurnGate`, CPU-based
pool sizing, `ActorShardLease`, and `ActorShardSnapshotLease`. Blindly
copying an older Stage-3 `ActorRuntime.java` onto main would remove those
changes.

A reviewed three-way integration was created on the exact current main
commit `f4e7135b3e1b3b49abef8b340d916e9012798810`: seven Stage-3
ActorRuntime patch hunks applied unchanged; the single conflicting actor
finalization block was reconciled manually. The integrated finalizer
retains mainline shard/snapshot lease revocation as individually guarded
teardown operations, outside the actor lifecycle monitor. The mainline
physical-turn gate and scheduler pool sizing remain intact.

The existing Stage-3 PR #1412 was **retargeted to main** using a
conditional-ref update after saving the former head
`da5444672fa34810473edeed7fc96dedd753575e` on
`backup/actor-owned-stage3-pre-main-integration-20261020`.
GitHub verified a four-file-only diff against main with no missing main
commits. No private source left its original GitHub organization.

Two additional real-source regression cases cover:

- Actor shard/snapshot quotas are refunded, and retained snapshots
  invalidated, before the actor's `done` Future can settle.
- Structured parent termination never reports `done` before its
  managed child's terminal `done` Future has settled (100 shutdown races).

A related source ordering fix publishes each child's own terminal
witness before calling `parent.childFinalized(this)`, closing a window
where a parent could race to finalization before its child even had a
terminal Future.

Stage-3 now contains **24 JUnit test methods** (10
`ActorOwnedTaskScopeTest` and 14 `ActorTaskTerminationCascadeTest`).
These methods are **committed, not verified as passing** on the real
JDK25/GraalVM project because source-repo GitHub Actions jobs still
report zero executed steps. Do not equate Git mergeability with test
success. Keep Stage-3 draft pending exact-source CI and review.

Private cross-organization source mirroring remains explicitly blocked
by open security issue #962. No cross-org source mirror was attempted.
