# AttoLang actor task execution context / stack binding (M1.5 draft)

This stacked source change builds on Java actor logical stack M1 PR #2624 at
commit `0dfefc05af09e743fd14fb56e7dcbd21ba231915`. It is an **actor-host scheduler and interpreter admission
slice**, not an implementation of physical per-actor stacks.

## Implemented in this draft

- `ActorRuntime.startActorTask` reserves one bounded, actor-incarnation-owned
  `ActorGuestStack.TaskRoot` before scheduler admission. Both SHARED/PRIVATE
  and UNTRUSTED actor task paths use it.
- Every Task resume authenticates the exact actor stack object **and**
  generation. A completion/timer/I/O thread cannot install either authority.
- A resumed actor source Task has a stable `ExecutionContext` whose logical
  `AllocationDomain` kind is ACTOR. Its identity persists across Await and
  Cooperate, including carrier migration. This is an identity, **not** a heap
  allocation capability or actual native stack pointer.
- The logical task root remains charged while suspended. It is retired
  idempotently only by the scheduler's physical-quiescence callback (or failed
  admission), never merely because a Future was completed/cancelled.
- Cancellation cleanup retains `CleanupAwareTask` and the UNTRUSTED
  wall-time/quiescence gate is preserved.
- Actor finalization explicitly waits for zero live task roots before native
  memory release; a leaked root holds/quarantines the incarnation instead.
- Task roots have a separate 64-root cap; actor synchronous call-depth limits
  and native carrier stack limits remain independently enforced.

## Deliberate non-claims

- Native JVM carrier frames still execute the interpreter. No independently
  allocated/switchable guest frame bytes, stack guard pages, guaranteed
  `StackOverflowError` recovery, or Java Native Image proof is provided.
- Compiler-lowered `.atto` lexical values may still be held in JVM objects;
  this change does **not** prove actor-private physical heap placement.
- A task root is currently **one logical Task activation root**, not a byte
  charge per nested/recursive lexical frame. M2 needs exact per-frame byte
  accounting in compiler HIR/MIR and native backends.
- Sibling actor tests are host/runtime evidence only, not compiler-backed
  guest output. Cross-backend parity and supervisor restart remain unverified.
- Task cancellation and nested suspension need step-bearing hosted tests;
  `steps=[]` / `steps=null` mean NOT_EXECUTED.

## Required source-local test receipt

```sh
mvn -Dtest=ActorTaskStackOwnershipTest,ActorGuestStackTest,GuestCallDepthGuardTest,GuestCallDepthLanguageTest,GuestCallDepthActorHostModelTest,ProperTailCallTest test
```

Require exact head SHA, tree SHA, nonzero executed steps, JUnit count,
negative wrong-actor/stale-root tests, and independent review before merge.

## M2 follow-up

1. Compiler-lower owned stack frames and suspended lexical captures into
   bounded per-actor-generation backing memory with generation-fenced handles.
2. Charge live and suspended frame *bytes*, not only one task root.
3. Preserve `rt borrow` / `rt take` rules across Await, Cooperate,
   select/channel wakeups, cleanup and hot reload.
4. Verify stack-exhaustion fail-stop + supervisor restart + actor B survival
   using two compiled `.atto` actors on independently guarded execution stores.
5. Replicate the fixture on LLVM, .NET NativeAOT, Wasm and Forge/Cranelift.
