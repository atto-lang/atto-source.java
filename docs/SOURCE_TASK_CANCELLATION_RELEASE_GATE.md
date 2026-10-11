# Source-task cancellation and cleanup: release gate

Status: **PARTIAL IMPLEMENTATION / RELEASE BLOCKED** (October 11, 2026)

This document remains an executable admission checklist. Scheduler-lane
cancellation unwinding has a draft implementation, but the language and
runtime have **not** certified all cancellation-safe `finally` / `defer`
paths, actor teardown or native-image behavior.

## Original audited cancellation gap (historical)

The original `OresScheduler.TaskRunner.cancelFromFuture()` detached
`activeAwaitRegistration`, transitioned `phase` to `TERMINAL`, cleared the
pending resume and removed the task. `cancelFromSchedulerClose()` invoked
`completion.cancel(false)`. This previously discarded suspended cleanup.
The new task-specific cancellation admission preserves the task lifetime and
enqueues cancellation through the owning scheduler, but further end-to-end
conformance is still required.

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

## Draft implementation progress (October 11)

- `OresFuture` now has a task-specific deferred cancellation mode; other
  Futures retain their immediate settlement semantics. The task Future becomes
  terminal only after its scheduler marks cleanup complete or failed.
- `OresScheduler.TaskRunner` admits a single cancellation request, delivers
  it to cancellation-aware source tasks on their original scheduler lane,
  leaves an already-running cleanup await intact, and does not publish terminal
  completion until after the carrier turn exits.
- `SourceTask` maintains a heap-owned LIFO lexical cleanup stack. One owner
  drains each scope's `defer` and native mutex guards. A scheduler cancellation
  bypasses guest `catch`, but enters `finally`. Cancellation in a cooperative
  loop is observed at sparse safe backedges and unwinds the active lexical
  scope rather than replaying the loop body.
- Scheduler shutdown stops new tasks, requests cancellation, and waits up to
  five seconds for outstanding cleanup tasks. A timeout is **reported as a
  failed drain**, not silently relabeled a successful guest finalizer.
  Runtime-owned context/actor teardown needs independent integration review.
- Queued non-source tasks without lexical cleanup are cancelled without
  consuming the actor CONTROL lane, preserving actor-termination responsiveness
  under high backlog.

**Remaining release blockers:** real source-level cancellation interleavings
through awaited `try/finally/defer` with nested errors and nested `recover`,
fault-injected owned borrow/guard retirement, actor/context shutdown races,
shutdown timeout and fatal error policy, independent scheduler/continuation
review, Graal native-image AOT/hybrid and LLVM parity. A host shutdown deadline
is a failure boundary, not proof that a stuck guest finalizer can always run.

Run the newly-added scheduler cancellation and ActorEndCleanupTest tests, then
the entire JVM suite against the exact source head. Do not merge this draft on
focused test success alone.

## Physical queued-carrier cancellation fence (October 11, 2026)

A queued turn has an atomic carrier-admission ticket. Cancellation may revoke
a ticket that has **not** claimed entry to the VM, release its outstanding
reservation, settle the task without entering the VM or running guest code, and
leave the queued executor callback as a harmless no-op. This is necessary for
fast actor termination even when the CONTROL queue contains thousands of tasks.

Once a carrier has claimed VM entry, its ticket is no longer revocable:
`outstandingCarrierTurns` delays task Future publication and scheduler/context
retirement until the entered turn fully exits. Installing each ticket and
publishing QUEUED are serialized with cancellation admission so competing
producers cannot replace a live ticket. A tagged await generation independently
rejects stale callbacks after resumption into a different cleanup Future.
Concurrent `close()` callers share a completed drain or its original failure,
rather than returning prematurely.

**Verified JVM gate:** [funded run 38178592661](https://github.com/zed-pkg-test/java-app/actions/runs/38178592661)
matched 388 source Git blobs and passed full Maven: **1,429 tests, zero failures,
zero errors, one skipped**. This includes queued-cancellation, concurrent-close,
producer/cancellation races, and actor end under CONTROL/backlog saturation.

Suspended source lexical cleanup under actor/context teardown, cancellation
timeouts and fatal-error policy, independent physical ownership review,
GraalVM native-image AOT/hybrid, and LLVM semantic parity remain separate
release gates. Do not merge this draft solely on JVM success.
