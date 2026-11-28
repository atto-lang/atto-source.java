# AttoLang Java/GraalVM first checked-i64 Truffle backend kernel

**Status:** draft, source-authored, tests NOT_EXECUTED until private-repo
runner admission is restored. Stacked on #2843 → #2834 → #2826 → #2821
→ #2817 → #2759.

## What is now executable *when the Java tests actually run*

`VirtualActorTruffleI64KernelV1.lower` accepts only the private-constructor
`VirtualActorProtectedOpsMirV1.ProgramPlan` minted from the exact checked
protected-state source grammar. It rechecks every instruction and overflow
exceptional-exit obligation, then emits one pure scalar Truffle `RootCallTarget`
per typed method with an internal `Node` for `Math.addExact`.

This is a **real candidate Truffle arithmetic call target**, not a Java
numeric-reference oracle or a hand-written Cart host actor. It is a narrow
backend operation: given two exact i64 inputs, it yields an i64 candidate
or throws on signed overflow. It owns no state pointer, actor ID, loan,
mailbox, supervisor, storage capability or physical execution context.

The compiler-only `requireCandidate` selects exact typed methods; the
returned candidate is **not** a guest-visible `VirtualActorRef[T]` or public
invocation slot. Existing local ordinals are NOT a stable hot-upgrade ABI.

## What remains unimplemented

The pure Truffle numeric target cannot read, lock or commit actor state.
On an overflow it has no state to change, but it also does not physically
terminate an actor or send supervisor CONTROL. The explicit requirements
in the parent MIR must be enforced by a future actor-owned, linearizable
transaction and protected physical handler. No test can claim overflow
→ STOP → terminal CONTROL → async ack → generation N+1 until that exists.

Production `TypeChecker` and `OresLanguage.parse` remain fail-closed for
`define virtual actor` with `VIRTUAL_ACTOR_MIR_UNSUPPORTED`. Do not turn
this pure root into an unrestricted callable guest function. Do not pass
caller-controlled values into a state write without a verified actor-owned
exclusive loan, generation/identity fencing, cancel/STOP quiescence, and
runtime-managed exceptional exits. Do not use `ReusableLongStateRegion.read`
and `write` as separate steps: that would open a TOCTOU race against STOP.

## Tests / evidence

`VirtualActorTruffleI64KernelV1Test` includes six JUnit cases:
actual internal Truffle `RootCallTarget` execution on parsed `.atto`
Cart source; signed extrema and overflow; 512 deterministic BigInteger
oracle vectors; strict nominal method selection; no forgeable constructors
or actor authority; and preservation of production execution rejection.

The same focused CI job demands exact PR-head readback and Surefire XML
from the new suite and the full prior Cart/CONTROL/generation suites. It
also checks Git tree and file blob identities.

**No official compilation or JUnit evidence exists until a GitHub job
acquires a runner and executes real steps.** CI `steps=[]` is
`NOT_EXECUTED`, not success and not a source regression. The private
mirror must not run until security issue #962 and protected-reviewed
destination policy authorize the exact source head/tree and destination
repository ID. Private Git objects are durable even after deleting refs.

## Next engineering task

Wire the candidate `RootCallTarget` into an actor-owned *atomic* state
transaction with compiler-proven lifetime, generation, quiescence and
supervisor CONTROL; prove overflows never commit and enforce exactly-once
cleanup. Then add typed logical `VirtualActorRef[Cart]`, lazy activation
and durable CAS epochs. Test real guest `define virtual actor` execution
before removing the TypeChecker gate and pursuing other backends/AOT.
