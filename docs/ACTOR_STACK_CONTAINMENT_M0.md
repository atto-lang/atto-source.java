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

## Stacked M1 actor-owned budget slice (2026-11-07)

The separate `feat/actor-owned-logical-stack-m1-20261107` branch builds
on M0 without replacing its native-carrier guard:

* `ActorRuntime.ActorCell` allocates one `ActorGuestStack` per actor
  **incarnation**, and carries its identity through
  `ActorExecutionContext` into each physical mailbox turn. Stack accounting
  does not use a map keyed by recyclable integer actor IDs.
* `GuestCallDepthGuard.enter/leave` now reserve and release both the
  actor-owned logical frame budget and the carrier-local synchronous recursion
  cap. Both must admit the frame; a proper tail transfer remains one entry.
* Once the actor stack is exhausted or an impossible unwind is observed it is
  **poisoned permanently**, even after all frames unwind. A new supervised
  incarnation gets a fresh stack. Carrier context switches while a guest frame
  remains live fail closed.
* At mailbox dispatch boundaries, `ActorRuntime` converts even a swallowed
  guest stack-budget failure into fail-stop actor termination rather than
  accepting another message. Other actor cells can reuse the carrier with a
  distinct budget and an empty native recursion scope.
* `ActorGuestStackTest` covers quota exhaustion, invalid unwind, actor
  identity across mailbox turns, host swallowing, and a sibling actor on the
  single-worker shared carrier. These are **host-model tests**, not proof that
  a compiled `.atto` actor survived stack exhaustion.

### Still missing — do NOT claim per-actor physical stack isolation

The interpreter's *actual local/return-address storage is still on Java
carrier-thread native stacks*. The new `ActorGuestStack` is actor-owned
logical frame *accounting*, not a segmented heap-resident execution stack.
The 64-entry limit is not proven safe against all expression/AST recursion,
Truffle, callbacks, JVM stack sizes, JNI/FFI, or OOM. Actor-owned suspended
frames do not yet debit this object. Until all guest expression/call/continuation
paths lower to bounded actor-owned frame segments, the full user-requested
physical per-actor stack guarantee remains **NOT IMPLEMENTED**.

Next: replace recursive evaluator paths with an explicit bounded frame
interpreter or heap-resident continuations, add per-actor byte/frame quotas
and generation-fenced retention on suspend/resume, and run compiler-backed
`.atto` actor A/B + supervisor restart fixtures under reduced native stack
limits. A host/native callback that bypasses checks requires rejection or a
killable OS worker, never catch-and-continue after `StackOverflowError`.

### Validation for this stacked change

```sh
mvn -Dtest=ActorGuestStackTest,GuestCallDepthGuardTest,GuestCallDepthLanguageTest,GuestCallDepthActorHostModelTest,ProperTailCallTest test
```

This command is a required verification gate; it has **not** been executed
with actual project dependencies/CI in the current source-side environment.
Do not merge merely because the authored tests exist or because unrelated
source-free jobs passed. Preserve exact-head, nonzero-step, real guest receipts.
