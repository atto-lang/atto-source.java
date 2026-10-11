# Source-task cancellation and cleanup: release gate

Status: **DRAFT IMPLEMENTATION / NOT YET RELEASE-VERIFIED** (October 11, 2026)

This document is an executable admission checklist, not evidence that
Oreslang already guarantees cancellation-safe `finally` / `defer`.

## Audited cancellation gap

`OresScheduler.TaskRunner.cancelFromFuture()` currently detaches
`activeAwaitRegistration`, transitions `phase` to `TERMINAL`, clears the
pending resume and removes the task. `cancelFromSchedulerClose()` invokes
`completion.cancel(false)`. The `SourceTask` keeps lexical cleanup in
heap-owned continuations, and the runtime does **not** unwind those
continuations after either terminal cancellation path. Cancelling while
awaiting `finally` or `defer` can therefore skip cleanup altogether.

Passing `await`-in-`finally` conformance proves *ordinary completion*
only, not cancellation, timed teardown, or context close.

## Required implementation architecture

1. Represent `CANCEL_REQUESTED` separately from `TERMINAL`. Cancellation
   and shutdown must not release the task/context lifetime while owned
   cleanup remains pending.
2. On cancellation, detach the abandoned upstream await, then enqueue a
   cancellation resume onto the **same owning scheduler/actor logical lane**.
   Never run guest finalizers on Future completion, producer, host, or timer
   threads. A carrier thread must be free while cleanup itself awaits.
3. Source-task block scopes must own a single-shot finalization token and a
   pending lexical flow. Unwind every registered defer/finally exactly once
   in LIFO/nesting order. Once cancellation is requested, reject unrelated
   user work; permit only the bounded cleanup subcontinuations needed to
   discharge obligations.
4. A subsequent cancel, timeout, scheduler close, actor crash or upstream
   completion must not race a second finalization onto the lane. Clearly
   define bounded cleanup deadlines, resource-release fallback, and
   explicit diagnostics if cancellation cleanup cannot complete.
5. Publish task cancellation only after the cleanup drain is completed or
   a documented failed-cleanup terminal outcome is recorded. Context and
   actor teardown must wait for (or explicitly fail) the drain without
   silently discarding pending obligations.
6. Preserve an earlier failure as suppressed if cleanup itself fails.
   A pending return is preserved if cleanup finishes normally; no `return`
   is permitted in the lexical body of `finally`.

## Mandatory executed tests before merge

- Cancel before first dispatch, cancel during active user turn, cancel while
  suspended on a Future, and cancel while the finally/defer Future is pending
- Double cancellation and concurrent Future resolution/cancellation races
- Scheduler.close / context-close / actor shutdown under suspended cleanup
- Nested `finally` and multiple `defer` obligations with exact invocation counts
- Cleanup errors, panics, cancellation timeouts and fault-injected guard release
- Owned borrow/mutex/actor message finalization and no double-free across
  carrier migration
- Repeated stress with hundreds of isolated actors and randomized interleavings;
  assert original-result and suppressed-failure identity
- Run JVM, Graal native-image AOT/hybrid, LLVM once implemented, and other
  supported target profiles through shared source fixtures

**Until all of these pass, keep the PR draft and do not advertise
exactly-once cleanup for cancelled source tasks.**

## October 11 experimental execution-context integration (stacked draft)

The Java draft now has an **opt-in** scheduler protocol,
`OresScheduler.UnwindingTask`, and the heap-owned `SourceTask` opts in.
`OresFuture.cancel()` for these tasks is a single-shot *request*, not
terminal publication. The scheduler detaches abandoned await registrations,
places a cancellation resume on the original task domain, and permits
future completion threads only to enqueue resumption. Existing task ownership
is retained until after the final carrier turn leaves the context.

The execution context is the existing heap-owned SourceTask, not a second
Java interpreter stack. `cleanupDepth` marks live `defer`/lexical
`finally` regions, and `SourceFlowKind.CANCEL` is a non-catchable control
exit. A task already awaiting *inside* cleanup continues that await instead
of receiving another cancellation throw. A pending cancellation is published
after the cleanup chain and mutex guards finish, or a cleanup failure is
reported with cancellation suppressed. Scheduler.close rejects new tasks,
requests a drain, waits up to five seconds, and **reports a failure without
forcibly shutting down carriers** if an owned cleanup task remains.

**Defer/recover semantic separation:** `defer` owns lexical finalizers and
must run in LIFO order. `recover` is currently implemented as
`SharedMutex.recover(callback)`, an owner-controlled poison recovery
operation, NOT a general-purpose cancellation handler. Future language
`recover` control-flow syntax must never swallow a task CANCEL signal or
fabricate successful cleanup. Panic/throw recovery is separate from
cancellation and from force termination of an isolated actor.

**Important caveats / next audit targets:**

- This experimental change does not yet certify cancellation-safe guest
  `defer`/try/`finally` execution on the actual Graal source evaluator.
  Source integration, randomized interleavings, native-image AOT/hybrid and
  complete merge-tree CI still need to run.
- Busy CPU loops need cancellation control at existing compiler/runtime
  safepoints; do not add wall-clock checks to every iteration. Logical task
  cancellation still requires a source-loop continuation conformance matrix.
- Cooperative timeout does not magically release stalled FFM loans or native
  borrows. The runtime must report incomplete drain and retain ownership
  rather than claiming resources have been reclaimed.
- The cancellation-aware task Future intentionally remains pending between
  acceptance of the request and terminal cleanup publication; user-visible
  semantics and callbacks must be covered by compatibility tests.
- `OresScheduler.UnwindingTask` is an internal host capability; guest source
  cannot forge `CANCEL`, grant itself a cleanup lane, or bypass the actor
  supervisor's ownership authority.

**Keep the PR draft.** The mandatory executed tests listed above are still
release gates, not evidence already obtained.
