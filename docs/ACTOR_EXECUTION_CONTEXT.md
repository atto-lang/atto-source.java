# Actor execution context, heaps and stacks

How `ExecutionContext` relates to actor-owned memory in the Java reference
runtime. Labels: **IMPLEMENTED** means it is on this branch and tested;
**PROPOSED** means it is not yet built.

## Model

| Concern | Owner | Mechanism |
| --- | --- | --- |
| Logical identity of a task or actor dispatch | `ExecutionContext.ExecutionId` | `(runtimeDomain, execution)`, survives carrier migration |
| Heap / arena | the actor incarnation | `ExecutionContext.AllocationDomain(ACTOR, domain, owner, generation)` |
| Native stack during a turn | the carrier thread | guarded per-carrier stack (spec chapter 18, "Stacks") |
| State live across `await` | the actor's arena (**PROPOSED**) | compiler-lowered continuation record, handle in `ExecutionContext.continuation()` |

Actors do not get their own native stacks. Turns run to completion, so no
native frames survive between turns. Per-actor stacks are needed only if a
backend suspends an actor mid-frame, and at hundreds to thousands of actors
per tenant VM they would cost too much memory.

## IMPLEMENTED

- `ExecutionContext.allocateRuntimeDomain()` is the single VM-wide source of
  `runtimeDomain` values. `OresScheduler` and `ActorRuntime` both draw from it,
  so task and actor execution ids never collide.
- Every physical actor dispatch (`ActorCell.runBatchEntered`) mints
  `ExecutionContext.actor(runtimeDomain, execution, storageActorOrdinal, 0,
  generation, 0, 0)`:
  - `domain` is the cell's runtime-minted `storageActorOrdinal`, one per
    incarnation;
  - `owner` 0 is the actor's root allocation owner;
  - `generation` is the `ActorRef` incarnation generation, so a restarted
    actor never shares an allocation domain with its predecessor;
  - `parentExecution` and `continuation` stay 0 until continuation storage
    exists.
- `ActorRuntime.currentActorExecutionContext()` returns it inside an actor
  turn. `ActorRuntime.currentExecutionContext()` returns the innermost
  logical execution: the actor dispatch, else the `OresScheduler` task, else
  null.
- The private `ActorExecutionContext` record still carries the runtime,
  `ActorId`, kind, policy and execution domain. Those are authority
  references, deliberately kept out of the pointer-free `ExecutionContext`.

## PROPOSED: continuation storage in the actor arena

1. When an actor turn awaits, the compiler lowers the live locals into a
   continuation record allocated in the actor's arena (`ActorLinearArena` or
   `ActorOwnedGraphArena`), charged to the actor's heap quota.
2. The `ActorContinuationLedger.Admission` receipt for the callback holds that
   record's handle. `ExecutionContext.continuation()` carries the same handle
   when the callback resumes, and `parentExecution()` names the dispatch that
   suspended.
3. STOP (`sealOrdinary`) discards queued ordinary receipts and frees their
   records in the same step. Lexical cleanup receipts (`SOURCE_TASK`) keep
   their records until retirement.
4. Restart moves the actor to a new generation, so every record from the old
   incarnation fails the generation check. This matches "the failed turn's
   uncommitted work is discarded" in spec chapter 18.
5. A resumed callback still runs on a carrier stack. Stack exhaustion inside
   it is attributed to the owning actor and restarts that actor, not the
   carrier.

Open questions: whether the record format is shared with the LLVM and Wasm
backends (pointer-free handles suggest yes), and how borrow-scoped views
are rejected at suspension points (see `USING_BORROW_SCOPES.md`).
