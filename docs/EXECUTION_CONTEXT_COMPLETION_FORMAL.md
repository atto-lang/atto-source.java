# Execution-context completion contract and bounded verification

Scope: PR #661 Java/GraalVM. This document distinguishes **current runtime
behavior**, the **required unified contract**, and **future refinements**. It
does not claim support for suspendable value-match lowering or LLVM parity.

## Identity, ownership, and state

An *execution context* is a logical callable/actor turn with an execution
identity, incarnation, lexical frame stack, cleanup stack, and pending
completion. It is **not** a physical Java worker thread.

For each pending completion, model at least:

- `NORMAL`
- `FUNCTION_RETURN(value)` (escapes only the callable owner)
- `ARM_RESULT(value, matchFrameId)` (consumed by exactly its lexical match)
- `BREAK/CONTINUE(loopFrameId)` (consumed by the intended loop)
- `THROW(primary Throwable)`, with ordered distinct suppressed cleanup causes
- `CANCEL(contextIdentity, incarnation)`

Only cleanup may intervene between an arm's `return` and completion at the
nearest *matching* value-match boundary. Guest `catch` handles guest errors,
not these lexical completion tokens; a panic bypasses ordinary guest `catch`
unless a future explicitly defined recover handler authorizes otherwise.

`ARM_RESULT` must **not** share the `FUNCTION_RETURN` discriminant. The
current synchronous evaluator has separate `ArmResultSignal` and
`ReturnSignal`; the current suspendable `SourceFlowKind` has only
`NORMAL/RETURN/BREAK/CONTINUE/THROW`. There is no `ARM_RESULT` lowering in
`SourceTask` yet. The compiler is expected to keep it fail-closed for
suspending/async/generator callables until ownership and cleanup equivalence
are implemented, not translate arm completion into function return.

## Completion algebra for cleanup

Let `combine(pending, cleanup)` be the completion passed to the next outer
lexical frame:

| pending | cleanup succeeds | cleanup fails |
| --- | --- | --- |
| NORMAL | NORMAL | THROW(cleanup) |
| FUNCTION_RETURN | same return | THROW(cleanup) |
| ARM_RESULT | same arm result | THROW(cleanup) |
| BREAK / CONTINUE | same loop completion | THROW(cleanup) |
| THROW(original) | THROW(original) | THROW(original, suppressed += cleanup) |
| CANCEL | CANCEL | CANCEL with cleanup failure recorded (future protocol gate) |

Duplicate or self-suppressed cleanup causes must not accumulate. Every defer
registered in a frame executes **once**, in LIFO order, including after a
preceding defer fails. A guard-release failure is a cleanup failure and cannot
mask a prior error. The cleanup outcome is committed **before** a value/result
is exposed to a caller, mailbox, or Future consumer.

Current Java/GraalVM paths:
- The synchronous `executeBlock` and `try/finally` use
  `preserveCleanupFailure`.
- Suspendable `SourceTask.runOneDefer`, `finishBlock`, and `try/finally` now
  also preserve the first failure through `sourceCleanup`/`sourceFinally`.
- `runSuspendableStatement` carries a single-delivery fence (ported from PR
  #567). An exception thrown by the downstream completion consumer cannot
  be reclassified as a second producer outcome. Duplicate completion attempts
  are rejected, including when a callback arrives on a later source turn.
- Generalized cancellation cleanup semantics, structured recover, and
  suspendable `ARM_RESULT` remain unimplemented here.

## Suspension obligations

For future `await` lowering, the owning execution context (not the thread)
must carry:
- current `matchFrameId`, parent frame, owner task/actor/incarnation,
- pending typed completion and its result's ownership/lifetime,
- cleanup program counters and already-executed cleanup registrations,
- resume authorization ticket/generation and terminal-publication bit.

A producer completion must only queue an authorized continuation. It cannot
execute guest code on the producer callback thread. Cancellation/stop fences
future resumes from older incarnations. A duplicate or late callback cannot
repeat a subject evaluation, a cleanup, or the completion publication.

When the outer frame receives an `ARM_RESULT` belonging to a child frame, it
must consume **only that child's** token. A tagless generic `RETURN` or an
unidentified value is insufficient for nested match, `try`, or actor/HTTP
continuations. For serial actors the scheduler's logical turn ownership
continues across `await`; the single mailbox lane is not released early.

## Bounded model and its limits

`FormalExecutionContextCompletionModelCheckTest` exhaustively enumerates the
reachable states of two nested result owners, one defer per frame, a bounded
number of failure causes, a single publication, cancellation, and arbitrary
late/duplicate callbacks. It checks:

1. No completion is delivered more than once.
2. An inner arm result can be consumed only once by its own match boundary.
3. No result is published until cleanup has finished.
4. A primary failure cannot be replaced by cleanup failure.
5. Cancellation fences publication and stale callbacks cannot mutate state.
6. Every reachable live state has a finite path to terminal completion
   (existential progress; **not** scheduler fairness or wait-freedom).

This is an **abstract bounded protocol proof**, not a mechanized refinement
proof for real `SourceTask`, actors, scheduler tickets, or LLVM. JUnit
guest-execution regressions in `SourceExecutionContextFailurePrecedenceTest`
exercise the *implemented* suspendable defer/finally failure precedence after
`await`; `TypedValueMatchCleanupPrecedenceTest` exercises the *implemented*
synchronous value-match path.

## Remaining release gates

1. Add a typed, frame-identified `ARM_RESULT` to the real continuation IR,
   not a Java exception crossing a suspended task.
2. Ensure cleanup and user failure outcomes are represented independently
   enough to preserve the original error through callback races.
3. Bind resume to runtime-issued actor/task/incarnation tickets and a one-way
   terminal bit, with fault injection for completion vs cancellation.
4. Add compiler and runtime differential conformance fixtures for JVM, AOT,
   hybrid, and LLVM, including nested match, `defer/recover`, and owned
   actor-message payloads.
5. Reconcile with PR #567 only after executing the **actual merge tree** under
   the full verification matrix.

No green abstract model check can waive any of these release gates.
