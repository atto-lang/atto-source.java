# Actor execution context, heaps and stacks

How AttoLang's logical `ExecutionContext` relates to actor-owned memory and to
Vert.x's `Context`. Labels: **IMPLEMENTED** means present on this branch;
**PROPOSED** means a required follow-up, not an implementation or parity claim.

## Relationship to Vert.x Context

AttoLang's `ExecutionContext` is analogous to the *logical identity* carried
through a Vert.x `Context`, but the types are deliberately not equivalent.

Vert.x `Context` is an execution context: it associates callbacks and handlers
with a context/event loop and offers context-local data and execution helpers.
AttoLang splits those responsibilities across narrower abstractions:

| Concern | AttoLang owner | Vert.x analogy |
| --- | --- | --- |
| Stable logical task/dispatch identity | `ExecutionContext.ExecutionId` | Context-associated logical work |
| Callback/task placement | `OresScheduler` and scheduler admission | Context executor / event-loop affinity |
| Actor serialization and authority | private `ActorExecutionContext` + physical actor-turn lease | Verticle/context-confined handler execution |
| Actor heap ownership | `ExecutionContext.AllocationDomain` + actor arena | No direct equivalent |
| Context-local values | ALS / Task-LS / RLS (language/runtime work) | `Context` local data |
| OS-thread-local values | TLS | Thread-local state (not a substitute for Context data) |
| Continuation storage across suspension | compiler-lowered task frame and generation-fenced receipt (**PROPOSED**) | Suspended callback/task state |

Do not introduce a second general-purpose AttoLang `Context` merely to copy
Vert.x. Keep the stable identity, scheduler placement, actor authority,
allocation ownership, and local-storage scopes distinct. An
`ExecutionContext` is not a Java thread, carrier, scheduler, actor reference,
actor arena, mutex lease, or permission token.

## Required semantic invariants

1. **Stable identity, changing dispatch.** One logical task keeps its
   `ExecutionId` across `await` and `cooperate`; each physical scheduler
   dispatch may use a different carrier and dispatch identifier.
2. **No carrier affinity guarantee.** Correctness must not depend on resuming
   on the same OS thread or CPU core. Affinity is an optimization only.
3. **Actor identity is stronger than ambient metadata.** Actor-only authority
   must be validated against the private runtime-issued actor-turn witness,
   actor incarnation, generation, and physical admission. Reading or copying
   an `ExecutionContext` must never grant actor authority.
4. **Nested resolution.** Inside an actor turn,
   `ActorRuntime.currentExecutionContext()` returns the actor dispatch
   identity; otherwise it falls back to the current scheduler task identity;
   outside both it returns null.
5. **Ownership is independent of execution.** Multiple executions may share
   one actor allocation domain. Different actors/incarnations must not share
   that domain. A scheduler task with no persistent allocation owner uses
   `AllocationKind.NONE`.
6. **Callbacks only signal readiness.** Future, timer, I/O, and producer
   callbacks may enqueue an authenticated continuation/resume. They must not
   execute guest code directly, restore guest locals, or borrow an actor stack
   from a foreign thread.
7. **Suspension is an ownership boundary.** Compiler-lowered live locals and
   cleanup state must be stored in a bounded task/actor-owned continuation
   representation. Borrow-scoped views and physical loans may not silently
   cross suspension.
8. **Retirement is physical, not merely logical.** A settled Future or cancelled
   task is not proof that callbacks have quiesced. Continuation roots and
   allocation charges remain alive until the scheduler/runtime supplies the
   physical retirement receipt. Reclamation is generation-fenced.
9. **No arbitrary context transport.** Functions, closures, methods, thread
   locals, actor authority, and lexical environments are not serializable
   messages. Distributed messaging carries explicitly Sendable data only.

## Current model

| Concern | Owner | Mechanism |
| --- | --- | --- |
| Logical identity of a task or actor dispatch | `ExecutionContext.ExecutionId` | `(runtimeDomain, execution)`, survives carrier migration |
| Heap / arena | actor incarnation | `AllocationDomain(ACTOR, domain, owner, generation)` |
| Physical dispatch | scheduler/actor runtime | admitted carrier turn; not a stable identity |
| Native stack during a synchronous turn | carrier thread | guarded per-carrier stack |
| State live across `await` | actor/task-owned frame (**PROPOSED**) | compiler-lowered continuation record and authenticated handle |
| Ambient authority | private runtime state | opaque actor-turn witness; never guest supplied |

The Java actor mailbox currently processes a turn synchronously on its admitted
carrier. Do not infer from that that AttoLang's eventual resumable guest stack
can be the carrier's native stack: guest frames, locals, cleanup state, and
continuations that survive suspension need explicit bounded ownership. Where
the implementation uses per-actor stack/frame storage, it must remain distinct
from the host carrier's physical stack and must be charged to the owning actor.

## IMPLEMENTED on this branch

- `ExecutionContext.allocateRuntimeDomain()` is the single VM-wide source of
  `runtimeDomain` values. `OresScheduler` and `ActorRuntime` both draw from
  it, so task and actor execution IDs do not collide.
- Every physical actor dispatch (`ActorCell.runBatchEntered`) mints
  `ExecutionContext.actor(runtimeDomain, execution, storageActorOrdinal, 0,
  generation, 0, 0)`:
  - `domain` is the cell's runtime-minted `storageActorOrdinal`, unique per
    actor incarnation;
  - `owner` 0 is the actor's root allocation owner;
  - `generation` is the `ActorRef` incarnation generation;
  - `parentExecution` and `continuation` remain zero until compiler-lowered
    continuation storage is connected.
- Context minting happens before carrier ThreadLocal mutation, so a mint
  failure cannot poison the carrier's actor identity.
- `ActorRuntime.currentActorExecutionContext()` returns the logical context
  inside an actor turn. `ActorRuntime.currentExecutionContext()` returns the
  actor dispatch when present, otherwise the current `OresScheduler` task
  context, otherwise null.
- The private `ActorExecutionContext` record retains runtime, actor ID, kind,
  policy, and execution-domain authority. These references deliberately stay
  out of pointer-free `ExecutionContext`.

## PROPOSED: context-local storage and continuation integration

1. Define the AttoLang-level APIs for ALS, Task-LS, RLS, and TLS. Their
   ownership, inheritance, cleanup, and cross-suspension behavior must be
   explicit. ALS is actor-incarnation-owned; Task-LS follows a logical task
   according to explicit spawn inheritance; RLS ends with the request; TLS
   belongs only to an OS thread and must not migrate with a task.
2. At `await`, lower live locals, operands, program counter, ownership roots,
   and cleanup/defer state into a bounded continuation/frame record. Charge
   it to the correct actor/task quota. Do not claim the existing numeric
   `continuation` field is wired until it references a runtime-minted,
   generation-fenced handle.
3. The scheduler owns resume placement. Completion callbacks only enqueue;
   the scheduler re-enters the task with its stable logical identity and
   validates the task/actor generation before touching continuation storage.
4. STOP/cancellation seals ordinary admission. Cleanup-aware task frames stay
   pinned until cleanup completes and physical callbacks retire. Failed
   cleanup or uncertain quiescence quarantines the allocation rather than
   freeing memory prematurely.
5. A resumed task may run on another carrier. Any required per-actor guest
   stack/frame storage is restored from actor-owned records, never from a
   stale carrier stack.
6. Share the pointer-free identity and conformance semantics across Java,
   LLVM, .NET NativeAOT, Wasm, and Forge/Cranelift. Backend-specific thread
   IDs, pointers, and executor objects must not leak into the portable
   representation.

## Required conformance tests

- A awaits; B runs; A resumes on a different carrier with the same logical
  `ExecutionId` and allocation domain.
- Two tasks in one actor share the actor allocation domain but have distinct
  execution IDs and task-local scopes.
- Two actors and two actor generations never share allocation authority.
- An ordinary scheduler task has `AllocationKind.NONE` and cannot forge actor
  authority by constructing or copying identifiers.
- Producer/timer/I/O completion threads only enqueue; they never execute guest
  callbacks or access actor frame storage.
- Cancellation during suspension runs required cleanup, then retires the frame
  exactly once after physical quiescence.
- Stale-generation resumes, duplicate callbacks, forged continuation handles,
  leaked roots, and borrow/loan escape across suspension fail closed.
- TLS does not migrate; Task-LS and ALS follow their documented logical owners.

A host-only Java test is not compiler-backed guest evidence. Cross-backend
parity requires exact-head, nonzero-step runs, compiled guest fixtures, and
receipts for every backend. A workflow with `steps=[]` or `steps=null` is a
runner-admission failure, not a passing or failing execution test.
