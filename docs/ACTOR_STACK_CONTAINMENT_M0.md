# AttoLang actor stack containment — M0 Java admission slice

Status: **reference interpreter guard, not process fault isolation** (2026-11-03).
The public language name is AttoLang; historical `.ores` fixtures remain compatible
while `.atto` parser/import admission is separately promoted.

## Contract

* Every actor owns its heap allocation domain, **but the carrier owns its OS
  stack**. A heap quota cannot prevent carrier stack exhaustion.
* Synchronous guest calls must be budgeted before entering another Java/native
  frame. A saturated budget raises a typed, stackless
  `GuestCallDepthExceededException` in Java. The exception is not a
  `StackOverflowError`, and guest `trap` or `catch` cannot consume it.
* Tail calls handled by the existing trampoline do not increase the call-depth
  count. Non-tail calls, cross-code-unit calls, methods, and callable values
  must share the same synchronous frame budget.
* The Java M0 guard is **per carrier thread** and resets in `finally`. It is
  not a per-actor virtual-stack allocator; it limits synchronous nesting while
  an actor/task is on a carrier. The fixed 64-call bound is a conservative
  reference implementation detail, **not** a portable byte limit.
* Supervisor restart only occurs after the actor turn, cleanup, ownership
  release, and carrier handoff have completed. Retained actor generations and
  failure cause must be fenced. No automatic retry of an incomplete side effect.
* Ordinary Java `StackOverflowError`, `OutOfMemoryError`, arbitrary FFI/native
  faults, and host-signal failures are **not** recoverable actor events. Never
  catch them and return the carrier to the pool claiming isolation.

## M0 limitations / must not promote

The guard covers `Evaluator.invoke`; it does not prove maximum native stack
bytes consumed by nested expressions/AST walkers, JVM/Truffle implementation
frames, Java interop, suspended SourceTask resumptions, compiler-generated code,
native stacks, or stack-overflow signal recovery. `ActorTurnWatchdog` is an
observer and does not enforce stack safety. Per-actor heaps do not change this.

## Required M1 and M2

1. **Java/GraalVM:** compile guest calls and expression evaluation to
   bounded explicit frames/continuations (or verified stack checks on every
   path); use logical actor identity and generation to charge the frame budget;
   prove cleanup and non-reentrant continuation after failure.
2. **LLVM / .NET NativeAOT / Forge-Cranelift:** bounded compiler-managed
   frames or segmented stacks with safe admission checks. Verify native stack
   reserves/guards, no unsafe recovery from SIGSEGV, SIGBUS, or CLR
   StackOverflowException, and no shared-carrier recursion escape.
3. **Wasm:** bounded Wasm call depth/fuel and per-instance stack budgets; classify
   engine stack-exhaustion traps without poisoning other instances/carriers.
4. **Untrusted guest/actor:** JIT is permitted where policy allows, but arbitrary
   native/JIT guest code must run inside a killable OS-isolated worker when its
   control-flow and host calls cannot be proven bounded. Per-actor heap, virtual
   stack, or catchable exception alone cannot contain arbitrary native faults.
5. Add exact-head cross-backend `.atto` fixtures: mutual non-tail recursion,
   non-tail recursion through interface and closure, 50k proper tail calls,
   nested conditionals, cleanup and lock release, actor A failing while B
   responds, restart generation fencing, low-stack carrier, and hostile FFI.
   Distinguish parser acceptance, compiler link, actual guest execution,
   native process survival, and supervisor recovery receipts.

## M0 validation

`GuestCallDepthGuardTest` is deterministic host evidence.
`GuestCallDepthLanguageTest` is a Java interpreter guest regression when
run; its authored existence is **not** execution evidence.
Run `mvn -Dtest=GuestCallDepthGuardTest,GuestCallDepthLanguageTest,ProperTailCallTest test`
with JDK 21+, capture exact head SHA and non-empty job steps.
CI runner admission errors or `steps=[]` are **NOT_EXECUTED**.
Do not label Java or any other backend process-fault-isolated from these tests.
