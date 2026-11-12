# Namespace actor closed-scalar MIR + physical read/write loans (draft)

## Scope and provenance

This draft reconciles the **restricted namespace actor compiler** from
[#2939](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2939)
with the existing **physical actor-state RW loan manager** from
[#2796](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2796).

The exact upstream `ActorStateRwLoans.java`, `ExecutionContext.java`,
`ActorStateRwLoansTest.java`, and
`ActorStateRwLoansCarrierMatrixTest.java` files were ported, not
reimplemented with a second lock or a Java broker state machine.

## Runtime-owned execution

`NamespaceActorScalarTurnCompiler` continues to admit only one declared actor,
one actor-attached typed `int` or `bool` literal state field, private
`external mtd init/receive`, and one exact, token-validated critical method:
either `s.count = s.count + 1` (checked signed 64-bit integer) or
`s.closed = true`. It rejects all other source effects before execution.

On runtime-owned `init`, each actor creates a separate
`NamespaceActorScalarRegion` with its own globally unique allocation
domain, actor ID, CodeImage identity, generation-bound
`ExecutionContext.actorBound` view, and `ActorStateRwLoans(0)` manager.
Only actual `ActorRuntime` turns may acquire loans: the adapter checks the
active runtime, actor ID and exact source CodeImage, and never accepts a
guest-supplied execution handle or allocation domain.

Each critical write:
1. obtains a **WRITE** loan with zero pending capacity (no scheduler parking);
2. verifies that the same execution authority still holds the loan;
3. performs the single compiler-admitted scalar mutation;
4. releases the loan in `try-with-resources` even for overflow/exception.

An internal runtime-only read validates a **READ** loan before obtaining a
scalar snapshot, without exporting a guest getter, raw pointer or supervisor
state capability. Separate #2796 tests exercise concurrent reader cohorts,
writer fairness, cancellation, retirement and carrier matrices.

The terminal failure path revokes the actor's region even if the existing
runtime skips graceful teardown on failure. Normal source-actor STOP closes
its loan manager, checks `physicallyRetired()`, then clears scalar fields.
Any unretired physical loan blocks cleanup rather than silently reclaiming
still-referenced state.

## Evidence and limitations

- `NamespaceActorScalarTurnCompilerTest`: the already-authored seven
  closed scalar compile/runtime JUnit cases, including two independent actor
  instances, negative mutations, int/bool mismatch, forged mail and TypeChecker
  fail-closed behavior.
- `NamespaceActorScalarRegionTest`: three new actual ActorRuntime host/VM
  tests for independent actor domains, cross-owner READ denial, wrong typed
  mail and sibling survival, STOP cleanup, and denial outside physical turns.
- `ActorStateRwLoansTest` and `ActorStateRwLoansCarrierMatrixTest` are the
  physical loan implementation's original concurrency/negative-test suites.
- The exact-head workflow includes all these suites and checks their non-skipped
  Surefire XML reports using their actual Java package names.

**The authored tests are not an executed JUnit result until actual runner
steps, reports, and exact-head/tree receipt exist.**

These scalar allocations are actor-affine Java VM objects with physical
loan accounting; they are **not yet native per-actor heap arenas**,
full compiler-generated destruction, an executable public
`Context.eval` namespace actor, a general typed MIR instruction set,
runtime-suspending fairness, a trusted guest ActorRef API, or an AMQP broker.

The remaining P0 is actual JDK25 Maven/JUnit execution, Native Image/guest
validation, full state-loan integration with actor-owned heaps/stacks,
generation-fenced task continuation cancellation, and independently reviewed
negative semantic proofs. Do not merge or enable `src/main.atto` broker
startup based solely on source-file, parser, or host-model tests.
