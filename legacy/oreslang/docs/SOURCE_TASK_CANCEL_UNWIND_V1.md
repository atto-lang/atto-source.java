# SourceTask authenticated cancellation unwinding — Java implementation slice

Date: 2026-10-22. Status: **DRAFT / NOT EXECUTED ON EXACT HEAD**.
Source Java PR #1887. This work refines issue #883 and the
Task cancellation / lock-retirement contract.

## Implemented host + source protocol

- `OresScheduler.CleanupAwareTask<T>` declares that a Task owns a
  heap-resident lexical unwind continuation. SourceTask implements it.
- `OresFuture` has a trusted scheduler-only deferred-cancellation mode.
  `future.cancel(false)` atomically REQUESTS cancellation and invokes
  its internal revoker but does not complete its value Future yet.
- For a Task with an already executing SourceTask continuation, the
  scheduler detaches only the current await observer, queues a
  terminal cancellation resume on the original Task/scheduler lane
  (not the cancelling/producer thread), and injects it ONCE.
  Cancellation prior to initial execution has no lexical scopes.
- The injected CancellationException is uncatchable under the
  existing source TrapFailurePolicy and is routed through SourceFlow
  THROW to the ordinary lexical defer/finally/MutexGuard unwind.
  It does not establish the proposed *source typed raise/recover*
  effects; compiler-negative tests for those effects are separate.
- A defer may itself `await` or `rt cooperate`. After injection,
  successive resumed cleanup turns remain in cleanup mode; no
  additional external cancel() call can restart the flow.
- The Future becomes truly cancelled only after SourceTask returns
  from its cleanup continuation AND the current carrier/physical
  scheduler retirement callback has run. Borrowed awaited producers
  are only unsubscribed, not automatically cancelled.
- An Oreslang panic implements runtime marker
  `UntrappableTaskFailure`; fatal cleanup outcomes override the
  pending cancellation. Java Error alone is not a source panic
  classifier.
- The source interpreter's `for`, `loop`, `for-of` and
  destructuring for-of cooperative continuation callbacks now
  propagate resume failure instead of silently starting the next
  iteration. `do-while` already propagated it.
- The untrusted actor quota wrapper retains CleanupAwareTask rather
  than erasing its marker behind a plain lambda. Quota denial
  during source cleanup is classified as incomplete retirement.
- A bounded limit of 4096 cleanup-only dispatches prevents endless
  cooperative cleanup from running indefinitely; exhaustion is
  `CleanupIncompleteError` and QUARANTINES any loans, not unlocks.
- On scheduler close, deadline, fatal quota denial or rejected cleanup
  scheduling, a Task with incomplete lexical cleanup may fail its
  Future but MUST NOT invoke onQuiescent, release its arena
  or advertise a safe join. `hasIncompleteCleanup()` is the
  scheduler's actor-finalization veto.
- ActorRuntime finalization checks that veto before releasing the
  actor's memory slice, unregistering its ActorCell or publishing
  actor-done. The ActorCell stays in the actor registry on failure.
  ActorRuntime.close() is allowed to fail instead of silently
  reclaiming an unsafe actor arena.

## Terminal states are not interchangeable

| Condition | Caller result | Physical resource claim |
| --- | --- | --- |
| Normal Task success | Success | Only after Task/child quiescence |
| Ordinary source throw | Failure, original cause | Defers/guards must unwind |
| Typed raise (pending compiler) | Escalated after cleanup | Not yet proven |
| Source panic during cleanup | Untrappable failure | Never demote to cancellation |
| Cancel with finished cleanup | Cancelled | No early unlock; physical barrier |
| Cancel while in-flight cleanup | Pending | No memory reclamation |
| Cleanup abort, exhausted quota, or shutdown | CleanupIncompleteError | Arena quarantined; no clean receipt |

A Future `isDone` check is NOT itself evidence of safe reclamation.
The authoritative TaskHandle.getDone / ActorRef.getDone and
cleanup failure diagnostics must agree.

## Focused tests (committed, not passed)

`CleanupAwareTaskTest`: cancellation at await, while a Task turn runs,
multiple awaited cleanup stages, prestart cancellation, fatal cleanup
error, guest-thrown CancellationException, scheduler close with a
suspended defer, untrusted actor quota wrapper, and endless
cooperating cleanup bounded by a finite budget.

Also execute:
- `SourceTaskCleanupHardeningTest`
- `SourceLoopSoftPreemptionTest`
- `TrapOptionSemanticsTest`
- `TaskOwnedLeasesTest`
- `TaskOwnedMutexRetirementTest`
- `ActorContinuationTeardownTest`
- `ActorStructuredCancellationTest`
- all scheduler/actor/LLVM conformance and sanitizer suites

The private repo's GitHub Actions jobs repeatedly failed with
no recorded Maven test steps. As of this document, no exact-head
JDK25/GraalVM/JUnit/LLVM execution proof is available.
Other-GitHub-organization private Git-tree mirroring remains
blocked by the checked-in source security policy until PR #1538
has an independently approved exact destination.

## October 23 independent safety audit and hardening

- `publishTerminalIfReady` now checks actual carrier quiescence and
  completion of physical `onQuiescent` retirement callbacks before it
  settles the Task Future. An unrelated scheduler-close thread must not
  complete a Future while an actor loan callback still executes.
- `retireWhenQuiescent` now uses a single-claim `quiescenceState`.
  Tasks remain in the scheduler's live set until their retirement callback
  returns successfully. A throwing retirement callback keeps the Task
  registered and sets `cleanupIncomplete`; a normal `Done` can no longer
  override failed physical retirement. Its original exception remains the
  direct failure for an ordinary Task or the cause of a cleanup-incomplete
  error for a cleanup-aware Task.
- `hasIncompleteCleanup()` is intentionally conservative: **any Task still
  in the scheduler's live set** vetoes actor arena reclamation, even when
  its logical `Future` has already reached TERMINAL. This now covers both
  a cancelled source defer and an ordinary Task's still-executing physical
  retirement callback.
- Fatal `Error` during authenticated cancellation unwind marks lexical
  cleanup incomplete and pins outstanding actor/FFI loans rather than
  allowing an ordinary successful physical retirement. The original
  fatal error identity remains observable.
- New unexecuted JUnit regressions in
  `CleanupAwareSchedulerCancellationTest` exercise concurrent close,
  the in-progress quiescence callback, ordinary and cleanup-aware
  callback failures, and panic-dominant resource quarantine.

**No exact-head JDK25/Maven/JUnit/GraalVM execution:** source-org workflows
still fail at runner admission (`runner_id=0`, `steps=[]`). These changes
are committed source-level hardening and require an executing test receipt.

**Important unresolved concurrency audit:** `ActorRuntime.finalizeStopLocked`
still calls `OresScheduler.close()` while the actor's `lifecycleLock` is
held. Before enabling a post-STOP cleanup-only scheduler lane, restructure
this as a two-phase close outside that monitor, then re-enter and revalidate
actor generation, remaining Tasks and physical carrier/loan retirement.
Otherwise arbitrary completion callbacks may form a re-entrant locking
cycle with a foreign carrier. Do not treat bounded quarantine as successful
or live actor shutdown.

### Two-phase ActorCell scheduler close (October 23)

`ActorRuntime.ActorCell.finalizeStopLocked` no longer invokes
`OresScheduler.close()` under the actor lifecycle monitor. It atomically
claims a one-shot close responsibility, starts a runtime-owned virtual
close worker, and defers finalization. The worker runs arbitrary Task
cancellation observers outside `lifecycleLock`, then re-enters the
monitor to verify the **same scheduler** issued the receipt and rechecks
carrier activity, children and remaining physical source Tasks before
heap release or successful actor `done()`.

A new `ActorTwoPhaseSourceSchedulerCloseTest` uses a blocked cancellation
subscriber and an independent concurrent STOP request. The concurrent
STOP must return while the subscriber remains blocked, and actor DONE must
remain unsettled until shutdown completes. This is an exact-head JUnit
regression **committed but not executed**.

The virtual close worker's creation is intentionally one-shot. Failure to
launch or failure in scheduler close quarantines the actor and leaves DONE
pending, rather than inferring a physical cleanup receipt from a logical
terminal Future. This staging implementation still does **not** provide
post-STOP guest defer/finally admission: those cases fail closed until a
separately authorized CONTROL-only lane and actor-generation handoff are
implemented and executed.

## Unresolved release gates

1. **Post-stop actor cleanup lane**. This base PR (#1887) closes
   continuation admission at STOP and quarantines unfinished defers.
   The stacked actor STOP draft adds a bounded SourceTask-only
   continuation mailbox, a sealed scheduler cancellation snapshot,
   and physical Task quiescence joins; see
   `docs/ACTOR_STOP_CLEANUP_ONLY_ADMISSION_V1.md`.
   Exact-head tests and complete native/FFI receipt integration remain
   required before this gate can be considered proven.
2. **Per-cleanup wall clock/fuel/heap deadline.** The 4096 turn limit
   bounds cooperative dispatches but does not bound a single stuck
   FFI/native call or a defer that waits forever on one Future. An
   isolation authority must eventually revoke or quarantine it,
   never pretend to have unwound it.
3. **Real source tests exercising external actor cancellation while
   inside a suspended source `defer`**, including FIFO mailbox
   ordering, no guest catch and zero stale-resume replays.
4. **Typed `raise` and `recover` lowering**, with effect checking
   and cleanup failure precedence. Reserved source syntax remains
   intentionally unsupported until executable conformance.
5. **TaskHandle / ActorRef physical and arena proof** across Java,
   GraalVM native image, LLVM, JS/Wasm and .NET, including fencing,
   async mutex handoff, fault injection and ownership-domain races.
6. **No customer merge** until exact-head Java build and tests,
   source compiler/AST guest tests, actor shutdown checks and cross
   backend parity pass on an AUTHORIZED runner.

The prior lexical-unwind repairs in #1887 (single-owner finishBlock,
all-distinct-MutexGuard attempts, primary error and fatal panic
precedence) remain required; this protocol does not replace them.
