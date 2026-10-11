# Source-task cancellation and cleanup: release gate

Status: **BLOCKED / NOT YET IMPLEMENTED** (October 11, 2026)

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
