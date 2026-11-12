# Actor call continuation chain — M2 foundation

Status: **host/runtime continuation-owner slice authored; exact-head execution
and compiler-backed guest suspension NOT EXECUTED**.

This change addresses the ownership conflict identified in issue #2700:
the 64-byte `ActorSourceTaskFrame` task root and nested call activations share
one LIFO `ActorFrameArena`. They must not independently suspend their own
frame capabilities.

## Implemented in this slice

- `ActorSourceTaskFrame` lazily creates exactly one `ActorCallContinuation`
  for its logical task.
- Nested activations are pushed above the task-root frame in the same
  `ActorFrameArena`, with a single ordered chain owner.
- Each activation has a bounded typed scalar image: magic, function ID,
  program counter, return PC, local count, operand capacity, operand depth,
  then canonical tagged scalar slots.
- Long and Boolean locals/operands are encoded using
  `GuestScalarFrameCodec`; arbitrary references, closures and host pointers
  are rejected.
- Scheduler suspension asks the chain owner to suspend the current top frame
  once. Scheduler re-entry restores that same top frame once after the
  existing actor/task admission checks. The task ExecutionContext object is
  unchanged while the dispatch ID changes.
- The resume witness is private, one-shot, generation-bound and tied to the
  prior physical scheduler dispatch.
- Physical task retirement invalidates the owner and lets the existing
  `ActorFrameArena.cancel` zeroize the task root and all remaining activations
  together.
- Foreign-thread inspection is rejected without poisoning the legitimate
  actor owner. The admission check deliberately does not acquire the task
  frame monitor, avoiding lock inversion between host-held stale handles and
  scheduler retirement.

## Host regression

`ActorSourceTaskFrameTest.nestedCallFrameChainRestoresAsOneUnitAcrossAwait`
uses the actual actor-bound scheduler path:

1. A SourceTask creates a caller and callee activation.
2. It stores caller/callee PCs, return PCs, locals and operands.
3. The task awaits a pending OresFuture; all three frames remain charged.
4. A host thread cannot inspect the suspended chain.
5. Completion only queues the task; the scheduler re-enters the same task.
6. The same chain owner and logical ExecutionContext are observed, with a
   different physical dispatch ID.
7. The callee and then caller are read/popped in LIFO order; the task finishes.
8. Physical retirement returns arena bytes/frame count to zero.

This is a **host scheduler/runtime regression**, not a compiled `.atto`
guest acceptance test. The Java scalar call machine remains synchronous-only;
this change does not add an Await opcode, lower async callees, preserve arbitrary
object roots, lower defer/finally state, or prove compiler/native stack parity.

## Next M2 implementation

1. Integrate the compiler-approved call machine with this task-owned chain so
   its activation PC, operand slots and locals are the durable suspended image,
   rather than a private synchronous activation list.
2. Extend the typed ABI with authenticated reference-root slots and cleanup
   records only after ownership/barrier semantics are defined.
3. Lower `suspends fnc` calls and await inside a non-tail callee. Preserve
   caller return PCs and operands across more than one suspension depth.
4. Add compiled `.atto` fixtures for nested await, sibling actor progress,
   carrier migration, cancellation cleanup, duplicate callback, stale
   generation and borrowed-value rejection.
5. Execute exact-head nonzero-step Java/GraalVM CI and record JUnit XML,
   canonical source outputs, frame accounting and mutation receipts.
6. Port the same portable ABI semantics to LLVM, .NET NativeAOT, Wasm and
   Forge/Cranelift. Host-only frame tests and source-free contract tests do not
   establish backend guest parity.

Do not enable unsupported source syntax by routing it through the legacy
recursive evaluator. Keep the guest feature fail-closed until the compiler
lowering and exact-head receipts exist.


## M2.1: synchronous scalar dispatcher uses the SourceTask chain

The restricted synchronous scalar `ActorScalarCallMachine.execute` now detects
an authenticated current `ActorSourceTaskFrame` and executes its CALL/RET
instructions through that task's existing `ActorCallContinuation`:

- No second `ExecutionContext` is minted on the SourceTask path.
- No `ArrayDeque<Activation>` is used on the SourceTask path; function ID,
  PC, return PC, locals and operands are read/written through the unified
  actor-owned chain.
- The root SourceTask frame remains the arena stack base. CALL pushes one
  activation; RET pops it and restores the parent's return PC; TAIL_CALL
  replaces the top activation while preserving the caller's return PC.
- After a complete synchronous scalar call, the chain is empty and the
  SourceTask root remains available for a later scheduler await/cooperate.
- Ordinary actor turns without an actor SourceTask still use the existing
  synchronous-only activation path. This avoids minting a fake task or
  granting SourceTask authority to an arbitrary actor turn.

A scheduler-backed test invokes a two-function nested CALL/RET program through
`startActorTask`, awaits after the call returns, then invokes a second program
after resumption. It checks that the same continuation owner and
`ExecutionContext` are reused and that only the SourceTask root remains
charged between calls.

This is **CALL/RET integration**, not yet suspension *inside* a callee. The
compiler still rejects `suspends` functions in this scalar lowering. No AWAIT
opcode, suspended callee PC, cleanup record or arbitrary reference root is
implemented by M2.1.

## M2 host continuation follow-up (not guest AWAIT lowering)

- Correct the missing actor-frame owner variable in the existing nested-await JUnit test. Without this fix, the draft test class cannot compile.
- Add a scheduler/actor integration regression for **two successive real pending Future awaits at different nested call depths**. The test asserts immutable ExecutionContext identity across three physical dispatches; activation PC, return PC, locals and operand tags across both awaits; foreign-thread inspection denial; exact frame quota charges while suspended; and complete physical retirement without poisoning the actor.
- This still proves **only** the host continuation substrate. Actual compiler-lowered .atto AWAIT in a nested scalar actor call, AOT, and backend parity remain unimplemented and must be tested separately. Runner admission is required; code review is not test evidence.
