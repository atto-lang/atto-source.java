# Task cancellation, defer/recover, and automatic lock retirement — v1

Status: source semantics DESIGN plus a narrow Java HOST prototype of
transferable logical lease retirement in draft PR #1887. This document
does NOT claim that source `raise`, `recover`, asynchronous `defer`
continuations, lock guard lowering, or actor/LLVM parity execute today.

## User-directed decisions

- Task cancellation requests termination of the owned Task and its
  structured descendants, not unrelated Future/Actor producers.
- Every source Task must have a compiler-owned unwind/cleanup chain.
  A Task can register ordinary `defer` cleanup (source parser already
  recognizes `defer <expression>;`) and a typed `recover` handler.
- **RAISE** in a Task bypasses ordinary `catch` and `trap`, unwinds
  scopes (including defers and lock guards), then reaches the nearest
  eligible typed `recover` for this raise category. A recover handler
  may consume/rethrow/transform the raise only if checked effect
  signatures and ownership allow it. Otherwise RAISE propagates to the
  parent TaskScope or actor supervisor.
- **THROW** is ordinary, typed catchable error: try/catch/trap may
  handle it. Unhandled throw fails the Task with its original cause,
  after cleanup. At the appropriate actor boundary the existing
  source exceptional-effect policy may escalate throw to raise.
- **PANIC** is untrappable and never recoverable locally; it reaches
  supervisor/CONTROL with original cause and actor/task ancestry.
  Verified runtime resource guards unwind where possible, but
  `recover`, `catch`, `trap`, Rx onError, and allSettled must NOT
  absorb panic or turn it into an ordinary Result.Err.
- **CANCEL** is an authenticated terminal control signal, NOT a THROW
  or RAISE. It bypasses catch/recover, revokes new admissions,
  stops downstream chain stages, unwinds deferred cleanup and
  runtime-owned guards exactly once, and settles Cancelled. Guest
  code may not suppress cancellation or forge the signal using
  `CancellationException`.

## Ordering and precedence

For a Task with a checked failure/cancel signal:

1. Mark cancellation/requested terminal state and stop admitting new
   child Tasks or executing later waterfall/queue stages.
2. At a safe cancellation checkpoint or resumed unwind continuation,
   exit the running critical section. Never unlock another thread's
   thread-affine mutex while it still executes user instructions.
3. Unwind lexical scopes inside-out. For each scope:
   a. execute compiler-owned release guards with exactly-once tokens;
   b. execute registered synchronous `defer` actions in LIFO order,
      regardless of normal return, throw, raise, panic or cancellation.
      Release-order relative to user defer is determined by lexical
      registration and must be consistent across Java/LLVM/native.
   c. run `finally` and iterator/generator close hooks in a single
      explicit state machine, not by replaying callback stacks.
4. On RAISE (only), after the protected resources of the unwinding
   scope are released, deliver the original typed raise to the nearest
   permitted matching `recover` handler. A `recover` MUST NOT
   observe a held lock inherited from the interrupted region.
   Its own newly acquired resources have a fresh scope.
5. If unhandled RAISE or PANIC: deliver failure to the structured
   Task owner/supervisor; detached failures MUST NOT silently vanish.
6. Wait nonparking for every owned child to physically retire,
   including deferred async cleanup and actor/FFI loan release.
7. Publish the separate `get_done()` physical-quiescence barrier ONLY
   after unwinding and resource retirement. A result Future can settle
   earlier but grants no memory/lock reclamation right.

A `defer`/cleanup failure must NOT silently replace an existing
THROW/RAISE/PANIC/CANCEL primary cause. Preserve original identity,
record bounded secondary cleanup diagnostics, and route an untrappable
cleanup panic to CONTROL. If cleanup fails to RELEASE a lock, do not
publish a successful cleanup/reclamation witness. A background
watchdog must not fabricate completed cleanup after a timeout.

## Lock categories: automatic release without unsafe external unlock

A. Lexical, thread-affine/native mutexes (JVM ReentrantLock/monitor,
   OS pthread mutexes): compiler lowers lock acquisition into a
   guard with `try/finally` and SAME-CARRIER unlock. Suspend,
   `await`, `select` and continuation migration while held are
   statically rejected, unless the lock is explicitly designed as
   a task-owned suspend-safe logical lock. Forced cancellation from
   another thread may MARK cancellation but must NOT call unlock().
   An executing uncooperative/host native section retains the lock
   until its safe unwind/termination boundary; otherwise early
   unlock would expose concurrently modified state.
B. Task-owned logical async locks: bound to (TaskId, lock generation,
   lease/fencing token), not an OS thread. Their unlock is a
   bounded NONBLOCKING operation valid from the runtime retirement
   notifier after all guest execution/owned descendants quiesce.
   The compiler registers a hidden runtime lease on successful
   acquire, and retires it automatically even if user forgets a
   `defer`. Explicit early close is legal only in the originating
   logical Task turn. Each token is retired at most once.
C. Actor `critical-read`/`critical-write` and sealed state:
   preserve serial actor-state exclusivity through await as specified
   by actor policy, but never let a task's cancellation release a
   shared state lock before its turn and guarded borrows retire.
   For any suspend-safe actor-state lease, gate actor-stop and arena
   reclamation on Task/continuation child quiescence.
D. FFI, irreversible OS operations, and untrusted isolate
   termination: guard retirement may require an explicit
   acknowledgement from the owning kernel thread or terminated
   isolate. If it cannot be proven, do not free arenas/locks just
   because a Future is cancelled. A lease-holder may have to be
   quarantined rather than falsely marked released.

No runtime can safely promise immediate automatic release of an
arbitrary native/thread-affine lock held by an uncooperative Task.
The enforceable guarantee is release by the language/runtime at the
first SAFE completion/unwind/physical-retirement boundary, and
no `get_done` success ahead of that.

## Java host implementation checkpoint

`TaskOwnedLeases.register(TransferableRelease)` registers a verified,
nonblocking, cross-thread-safe logical lease from the CURRENT
tracked OresScheduler Task. It rejects calls outside a tracked Task,
and rejects early `Lease.close()` by another Task/thread.
A per-Task registry holds at most 4096 leases and releases them
in reverse order after scheduler-confirmed quiescence AND child
Task retirement, before TaskHandle.getDone publication.

Manual early close and later retirement use an exact-once token.
A failed release cannot be disguised as successful reclamation:
TaskHandle.cleanupFailures() reports its immutable failure list;
the physical TaskHandle.getDone() outcome becomes Failed(
TaskOwnedLeases.CleanupFailure) even if result() previously
published a normal value. The original result()/failure identity
remains untouched, and another sibling release still runs.
Do NOT interpret getDone().isDone() alone as successful release:
check its outcome and the cleanup errors.

Only trusted host leases with truly thread-independent release
implementations may be registered. NEVER pass a Java monitor or
ReentrantLock::unlock into this mechanism. Release callbacks are
host-only nonblocking code; the compiler must not let untrusted
guest `defer` run on the release notifier thread.

Java tests in TaskOwnedLeasesTest cover terminal success, throw,
cancel while awaiting, cancellation while a guest turn is still
physically running, LIFO ordering, exactly-once early close,
task-domain confinement, cleanup failures, missed-release
prevention and uncancellable physical cleanup observation.
There is NO executed exact-head JUnit compilation proof yet.

## Required source compiler implementation (blocked)

1. Add explicit terminal unwinding signal: THROW, RAISE, PANIC,
   authenticated CANCEL; distinct from Java Throwable subclasses.
   A CancellationException thrown by guest is still ordinary
   THROW, not a cancellation capability.
2. Lower `defer` into continuation-owned cleanup frames stored in
   the appropriate actor arena. A suspended Task must retain its
   unwind stack and never replay an already-run defer.
3. Support typed `recover` for RAISE only, preserving distinct
   throws/raises/panics effects in method, generic callable,
   Task/Future/ActorRef.ask and Stream signatures. Defer and
   recover themselves have checked effects.
4. Generate implicit lock guards for ALL compiler-recognized
   lock acquisition, not only optional programmer `defer`.
   Definite release and no-yield safety proofs are compile-time
   requirements (include `rt mut borrow` state aliases).
5. For async defers that need an `await`, introduce a supervised
   cleanup-continuation mode with finite time/fuel/memory budget and
   temporary cancellation shielding. No blocking scheduler carriers,
   no infinite cleanup retries or fresh unbounded child spawns.
6. Prove that a Task's `get_done` waits for all guarded resources,
   pending futures/channels/select registrations, TaskScope children,
   actor turn exit and FFI/native loans. Actor memory finalization
   must use authoritative physical leases (#1566).
7. Create executable negative tests rejecting lock-guard suspension
   for thread-affine locks and forbidden panic catches; positive tests
   for try/catch/defer/recover ordering, nested raises,
   suppressed cleanup errors, cancellation while suspended and
   cleanup at most once after hot reload.
8. Cross-backend JVM/GraalVM AOT/JIT, LLVM and other targets must
   implement the same state machine and actor supervision behavior,
   with model-checked adversarial cancellation/lock races.

Related existing blockers: source terminal unwind #883, TaskScope
ownership #1403, actor memory physical leases #1566, checked
effects #1010, trap contract #1132, async composition draft #1887.
The parser already recognizes source `defer` expressions but
currently rejects source `raise`/`recover` before runtime lowering.
Do NOT claim source execution until those blockers are resolved.


## 2026-10-22 additional failure-closed hardening

- A successfully **explicitly closed** lease is removed from the registry's
  live-lock stack. The 4,096 limit now caps simultaneously outstanding
  leases instead of lifetime registrations; a Task can sequentially acquire
  and close many thousands of leases without false quota exhaustion.
- Any lease release failure still remains diagnostic even if its early
  exception is caught and the entry is removed. No automatic unsafe retry
  is attempted: a release may have partially succeeded. Failed physical
  cleanup is NOT proof that a lock was released.
- TaskOwnedLeases.CleanupFailure now retains originalOutcome() so a Task
  whose primary status was Failed(original error) or Cancelled(reason)
  does not lose its identity when physical lease cleanup also fails.
  The original result() Future retains its terminal state independently.
- TaskScope.getDone() now completes exceptionally on failed descendant
  logical lock releases; TaskScope.cleanupFailures() is a separate
  uncancellable detailed observer. TaskMapParallel.MapHandle.getDone()
  propagates that failed scope cleanup instead of manufacturing success.
  Scope getDone *settlement* and scope getDone *successful retirement*
  are distinct; consumers must never use isDone() alone for lock/arena
  reclamation when a cleanup error is possible.
- New regressions cover repeated acquisition with cooperative scheduler
  yields, simultaneous lease caps, failure propagation through scopes and
  bounded maps, preserving prior Task throw/cancel outcome, and no early
  release while guest execution is active.

This is host-only Task-owned transferable lease retirement, NOT source
`defer`/typed `raise`/`recover` implementation, ordinary mutex auto
unlock support, supervisor panic parity, or verified zero-UAF actor
finalization. The source compiler still needs continuation-unwind work
tracked by #883 / #1010 / #1566.

### Runner/privacy constraint

The source repository's approved private CI destination list on main is
empty, and policy PR #1538 remains unmerged. Repository governance
forbids cross-organization transfer (even private temporary refs) until
independent review and approved exact destination/carrier contract.
A destination with Actions minutes does NOT supply that authorization.
The local runner cannot retrieve the complete private source tree;
exact-head GitHub workflows continue failing without recorded build/test
steps. New tests are committed, NOT claimed passing.


## SourceTask focused hardening (October 22, 2026)

Java/GraalVM `OresEvalRootNode.SourceTask` now has two additional
**source-level** correctness changes:

- `runBlock` passes the underlying `SourceFlowCont` directly into
  `runStatements`. Before the fix, both `runStatements` and the extra
  `runBlock` continuation called `finishBlock`, so a lexical block
  could attempt `env.releaseMutexGuards` twice after the same defer
  stack had already been emptied. There must be exactly ONE owner of
  lexical unwinding for both normal completion and abnormal flow.
- `mergeCleanupFailure` preserves the original ordinary Task error
  when a later defer/guard cleanup produces an ordinary error, retaining
  secondary diagnostics as suppressed. Existing panic policy is NOT
  weakened: an untrappable cleanup panic/authority failure still
  supersedes a prior merely recoverable throw; a preexisting fatal
  primary is never downgraded. Cleanup ProcessExitSignal cannot erase
  an already pending error.

`SourceTaskCleanupHardeningTest` adds four guest-execution tests for
post-cooperation defer LIFO order + finally once, implicit MutexGuard
scope release without programmer defer, original bounds error
precedence with a bad defer, and fatal Option::unwrap cleanup
precedence over an ordinary failed cast. These test sources are
committed, **not verified passing**, and should run alongside
`TrapOptionSemanticsTest`, `SourceLoopSoftPreemptionTest`, and
`SourceActorEndCleanupExecutionTest` on the actual GraalVM runtime.

### P0 still unresolved: externally cancelled suspended SourceTask

The canonical scheduler's TaskRunner can move WAITING directly to
TERMINAL on cancellation, detach its await registration, and publish
the task Future without dispatching `SourceTask.resume` again.
SourceTask presently holds `awaitingContinuation` and heap-owned
lexical state; its `resume` catch clears continuation fields without
running an async cleanup trampoline. Therefore the above changes DO
NOT ensure cancellation while suspended executes every `defer`,
`finally`, iterator close, or guard release.

Required next stage is an **authenticated terminal-unwind mode**
owned by the scheduler/SourceTask, not `SourceFlow.throwing` delivered
to guest code:
1. On cancellation, forbid new ordinary Task work while permitting
   one bounded, cancellation-shielded cleanup continuation on the
   original scheduler/actor domain.
2. Track the live lexical scope/iterator/guard frames in the
   SourceTask heap-owned continuation state. Execute each scope's
   cleanup exactly once in LIFO order, allow only budgeted cleanup
   suspensions, and never route terminal cancellation into guest
   `TryStmt.catch`.
3. Join all owned descendants; only after the cleanup continuation
   and physical carrier/arena leases retire may the source Task
   publish a **successful** physical cleanup witness. If a lock
   fails to release, report failed cleanup and quarantine its memory
   rather than claiming safe finalization.
4. Add deterministic cancel-at-await, cancel-after-cooperate,
   cancel-while-guarded, cancel-during-defer-await, and actor
   supervisor/restart tests. Use model-checked interleavings to
   forbid duplicate cleanup, stale continuation revival, or early
   memory release. LLVM needs equivalent lowering.

The SourceTask continuation gate remains tracked as #883 and the
actor arena finalization gate as #1566. Keep PR #1887 draft until
these guarantees are actually implemented and executed.


### Additional lexical MutexGuard release hardening

`Env.releaseMutexGuards` previously aborted its traversal if the first
guard release threw. This could prevent later independent guards from
receiving any unlock attempt. It now traverses all slots and nested
Option/Result/list/map/object/array fields, identifies each guard by
identity so aliased handles are attempted at most once, and records
individual release/traversal failures while continuing the remaining
cleanup work. Once all attempts finish, it throws the primary cleanup
failure with other failures retained as suppressed; panic/host fatal
errors are never demoted behind an earlier ordinary cleanup error.

The source-level `SourceTaskCleanupHardeningTest` adds a fifth test
checking two independent MutexGuard values automatically release at
lexical-block exit and can be reacquired subsequently. If a release
fails, this change makes the other unlock attempts but **does not**
falsely assert that every resource was actually released. Failed
resource retirement still demands physical lease accounting and
failure-closed actor heap reclamation.

No proof of automatic cleanup on EXTERNAL cancellation of a
SUSPENDED SourceTask exists: scheduler WAITING->TERMINAL bypasses its
normal continuation, as tracked by #883. Run guest JDK25 and LLVM
equivalent tests before merge.


## Update: authenticated SourceTask cancellation cleanup (2026-10-22)

**Supersedes the earlier statement above that a suspended root
SourceTask has no cancellation continuation.** The current Java draft
now has a partial, real scheduler-to-SourceTask unwind protocol.

1. `OresFuture` has an opt-in deferred cancellation request for
   `OresScheduler.CleanupAwareTask`. Request admission is linearized
   with ordinary terminal settlement under a short lock. The Future
   remains logically pending during cleanup; only a scheduler-owned
   physical terminal handoff can publish cancelled or incomplete.
   Ordinary `OresFuture` cancellation semantics are unchanged.
2. `OresScheduler` injects exactly one terminal cleanup resume only
   on the original scheduler, with a private thread-local admission
   marker. Awaited producer Future registrations are detached without
   cancelling borrowed producers. A cleanup continuation may suspend
   again; the cancellation marker is not reinjected.
3. `SourceTask` now implements `CleanupAwareTask` and retains heap-owned,
   LIFO lexical frames for `defer`, implicit `MutexGuard` retirement,
   and `finally`. On an AUTHENTICATED cleanup resume, it discards the
   interrupted ordinary continuation, bypasses source catch/trap, and
   unwinds frames using an iterative trampoline. Nested cleanup that
   itself awaits uses the same scheduler-owned continuation mechanism.
4. `async for` and AutoCloseable synchronous iterator scopes now
   register a close-once cleanup capability. Original completion and
   cancellation share ONE cached async close Future to prevent duplicate
   close of a producer racing with cancellation.
5. Cleanup-time Oreslang panic implements a runtime-only untrappable
   marker; a true panic still dominates cancellation. Ordinary
   secondary cleanup errors are retained as bounded suppressed
   diagnostics. A trapped function cannot turn cleanup errors into
   normal `None`.
6. The scheduler fails closed when a cleanup lane is unavailable or a
   finite cleanup-execution budget is exceeded. It must NOT release
   an actor arena under a false clean-up success witness.
7. Root scheduler unit tests now include `CleanupAwareTaskTest`,
   `CleanupAwareSchedulerCancellationTest`, and
   `DeferredFutureCancellationRaceTest`; source-language tests
   `SourceTaskCleanupHardeningTest` cover lexical cleanup and
   ordinary async generator close. Tests are SOURCE-COMMITTED, NOT
   verified executed or passing on exact head.

### Remaining P0 and proof boundaries

- **Actor STOP cleanup admission.** ActorRuntime currently marks its
  continuation queue stopped/closed and drops normal queued work. A
  suspended source cleanup resume needs a scheduler-minted,
  actor-incarnation-bound cleanup-only mailbox permit, backed by
  a physical-lifetime count that postpones actor finalization.
  Current fallback quarantines actor arena on incomplete cleanup;
  this is safe failure handling, NOT working actor STOP cleanup.
  No guest user messages or arbitrary callbacks may be admitted
  after stop. Force-revocation must require actual isolation proof.
- **Exact-head compilation and tests.** GitHub Actions run jobs fail
  with no recorded steps. Run focused JDK25/JUnit, GraalVM Native Image,
  actor mailbox cancellation and LLVM/TSan parity on an approved
  runner. The source-org private CI destination allowlist is empty,
  so cross-organization source mirroring remains prohibited until
  the security-policy approval is complete.
- **Compiler-enforced checked effects.** `raise`/typed `recover`
  remain fail-closed until parser, callable signature typechecker,
  backend unwinder and supervisor semantics are implemented.
  Automatic source guard release is now better staged but does
  not prove arbitrary native/FFI mutexes are safely releasable.
- **Adversarial model.** Check cancel-vs-await settle, cleanup-await,
  stop-vs-queued cleanup, iterator close races, panic during cleanup,
  task-child physical retirement, executor rejection, and identity/
  generation fencing. The Java implementation does not establish
  LLVM or native actor-arena parity by itself.

Keep PR #1887 DRAFT. Neither logical cancellation nor an
exceptionally completed cleanup Future alone is a clean physical
reclamation receipt.
