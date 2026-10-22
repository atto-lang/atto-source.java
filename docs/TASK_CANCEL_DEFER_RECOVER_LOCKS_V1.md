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
