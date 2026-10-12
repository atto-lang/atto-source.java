# Source execution-context cancellation contract (draft, 2026-10-11)

**Status: partial JVM implementation. Not release-certified.** This document is a
runtime and compiler admission contract, not a claim that a single, unified
execution-context object or guest `recover` keyword already exists.

## Execution context and ownership

A source task's persistent continuation must carry its own immutable
scheduler/task identity, logical actor/incarnation when present, current
lexical scopes, outstanding cleanup obligations, pending control-flow result,
cancellation reason, and await registration generation. Moving to a different
physical carrier must **not** transfer execution ownership to that carrier.

The current Java implementation uses `SourceTask`, `SourceCleanupScope`,
`TaskRunner`, and tagged `OresFuture` await registrations to represent
these pieces. They are not yet one generalized, portable ABI shared with LLVM.

States/allowed transitions:

- `NEW -> RUNNING -> WAITING | COOPERATING | TERMINAL`
- `WAITING | COOPERATING | RUNNING -> CANCEL_REQUESTED -> UNWINDING`
- `UNWINDING -> CLEANUP_WAITING -> UNWINDING` repeatedly as needed
- `UNWINDING -> TERMINAL` only after obligations drain or explicit failed drain

Cancellation is a *control signal*, not an ordinary catchable guest error.
Repeated requests cannot reset the unwind cursor, replay cleanup, or reset the
cleanup deadline. A producer callback carries its generation and cannot
deliver into an abandoned or newer cleanup wait.

## Lexical unwind

1. Abandon the ordinary pending await without canceling a shared producer.
2. Resume cancellation on the issuing scheduler/actor lane, never on a
   producer, timer, JNI, or arbitrary host callback thread.
3. Claim each lexical scope exactly once. Execute `defer` in LIFO order,
   `finally` on all exits, and release native/guard resources after obligations.
4. A cleanup Future may suspend; retain its continuation and owning lifetime.
5. Cleanup failure replaces the pending cancellation as the terminal failure,
   but must retain the earlier cancellation as suppressed evidence, without
   self-suppression or duplicate entries.
6. A nested cleanup failure cannot make an enclosing, unrelated guest
   `catch` eligible after cancellation was admitted.
7. Publish a successful/cancelled/failed task outcome **after** the entered
   guest turn exits and its physical carrier reservation is released.

`recover` as a general language keyword is **not implemented** in the
current parser. `SharedMutex.recover(callback)` is a separate operation,
not evidence of general cancellation recovery. If general `recover` is
introduced, its design must explicitly forbid swallowing scheduler
cancellation and permit only ordinary recoverable failures under lexical
ownership rules.

## Shutdown and physical teardown

Logical cancellation may immediately revoke new DATA/user-message
admission. That **does not** authorize early physical actor or context
finalization. The eventual `get_done()`/context termination barrier must
account for outstanding source cleanup, guarded borrows, native FFM loans,
and physical carrier exits.

The current actor runtime's `terminateTree()` clears pending-operation
tracking and seals its continuation mailbox before source cleanup has been
independently proven drained; `finalizeStopLocked()` may settle the actor's
done observer without checking all deferred source obligations. **This is an
identified release blocker.** It needs a dedicated cleanup-only CONTROL
admission path and source-owned drain accounting. Do not simply allow
arbitrary queued guest continuations to execute on a stopped actor.

A deadline cannot forcibly prove a stuck guest finalizer completed.
It must leave a **failed teardown result**, revoke guest capabilities,
retain physical resource reservations until the actual final release, and
report all incomplete cleanup obligations. Never silently turn a deadline
into a successful `get_done()` or completed context shutdown.

## Distinct STOP observation and physical termination

The Java draft now keeps `ActorRef.get_done()` pending when source cleanup
times out and retains actor/context physical resources. A new runtime-owned
host observer `ActorRef.get_shutdown_result()` reports the failed drain
exceptionally without pretending that physical termination has completed.
Successful physical finalization settles both observers. If physical
termination completes with an actor error, `get_done()` may complete
exceptionally, but only after finalization.

The host observer method is not yet a documented guest-source method or
compiler ABI. The draft JVM revision needs fresh exact-head CI, and GraalVM
AOT/hybrid runtime execution remains unverified. Outstanding native FFM
loans can still physically outlive actor shutdown under the existing
loan-retaining memory-slice design; extending the actor physical done barrier
to wait for all such loans requires a separate owner-lifetime callback
protocol and conformance tests. Do not claim the stronger FFM invariant from
the current host change alone.

## Executable gates

- Real guest-source nested `defer`/`finally` cancellation: tested on JVM
  with a pending channel receive; exactly-once output and no unrelated catch.
- Real failing awaited Future in nested `finally`: tested on JVM; prior
  cancellation must survive as a suppressed cause.
- Real pending await *inside* `finally` on context shutdown: source-level
  failed-drain regression added; certify only after exact-head CI passes.
- Actor-owned outstanding Future, FFM loan, mutex and borrow shutdown:
  **not certified**.
- Java native-image AOT/hybrid, LLVM source execution parity, synthesized
  merge trees and independent review: **not certified**.

Until these remaining gates pass, keep Java PR #567 and the LLVM/conformance
companion PRs in draft.
