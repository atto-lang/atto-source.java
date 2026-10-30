# Critical actor state-loan integration decision and executable gates

**2026-10-29 — draft, NOT_EXECUTED.** This note records a source-level
reconciliation decision; it is not a native compiler pass or release approval.

## Two competing AST shapes

- Java/Graal PR #2245 currently represents `critical[read|write]` as an
  intrinsic-only `Ast.CriticalStateModeExpr(StateLoanMode)`, followed by an
  inline `Ast.LambdaExpr`. The type checker establishes read-only or mutable
  structural borrow types and requires a synchronous, void, nonescaping
  callback. The evaluator uses an actor-owned revocable state view.
- Java/Graal PR #2257 uses a different ABI: a single
  `Ast.LambdaExpr(..., CriticalLoanMode)`. Its type checker,
  ownership checker and evaluator deliberately **reject** tagged callbacks
  until checked loan lowering exists. That branch is stacked on #2253.

**Do not blindly merge the two AST implementations.** Doing so would either
duplicate selector authority, break parser/compiler agreement or accidentally
turn a deliberate fail-closed rejection into unchecked runtime admission.

## Narrow implementation decision

Use PR #2245's typed selector, lexical callback and revocable view as the
existing implementation candidate. Keep `critical[read]` and
`critical[write]` the canonical accepted spelling; do not import the legacy
bare or hyphenated alias behavior from #2257 without a separate parser
compatibility decision and tests that show exact AST/borrow equivalence.
No user-provided String, annotation or expression may impersonate a
compiler-minted selector. Existing one-callback `handle_state` compatibility
writes should be deprecated separately rather than silently normalized.

Preserve nonreentrant actor handlers, no suspension/side effects while a loan
is open, no escaped state references, no direct sealed-field access and no
operator overloading. Read mode may not yield a mutable composite alias.
Physical actor-owned isolated heaps/arenas and generation-fenced revocation
must be demonstrated independently; a lexical view is not heap isolation.

## Regression additions

`ActorCriticalStateModeTest` now also exercises six **negative source
admissions** inside its existing critical-mode method, covering both
`critical[read]` and `critical[write]` against:
`self.send(...)`, `self.end()` and `stdio.stdout.write(...)` under an
active state loan. This does not increase the number of JUnit methods.
The generated Java fixture string syntax was checked locally with JDK 21
(`JAVA_21_NEGATIVE_FIXTURE_STRING_SMOKE_PASS cases=6`).
This is a **Java string-construction check only**, not a Graal or type-checker
execution.

## Required real compiler and deployment proof

1. Execute the exact PR head on an authorized GraalVM 25/JDK25 runner:
   `mvn -B -ntp -Dtest=ActorCriticalStateModeTest,ActorCriticalModeConformanceTest,ActorSingleStateSchemaTest,ActorStateLoanTest,SealedActorStateSourceSpoofingTest test`.
   Verify actual JUnit XML counts, all positive guest results, exact failure
   diagnostics, zero skips and zero test failures.
2. Reconcile the Java #2241/#2246 actor-demo stack against the same compiler
   tree. Require the **14 exact demo resources plus four actor baseline
   guests** to compile and execute with exact stdout/exit oracles.
3. Prove no reentrancy, read-to-write escalation, dangling loan,
   callback capture escape, host I/O, blocking, nested future flattening,
   or external actor publication under a live critical loan.
4. Obtain separate physical arena/heap isolation proof, same-source runner
   identity and trusted compiler SHA-256/build mode. Record exact source
   commit/tree, real step IDs, JUnit XML and artifact identities.
5. Enforce protected-main required checks and independent reviewer approval
   before merging. Do not copy private source into another organization
   without independently approved exact-source and destination authorization.

## Runner-admission checkpoint

Java #2245 earlier `Synthetic CI runner smoke` run `40920491623` and
`Critical actor-state modes — native Graal guest` `40920491694`
completed failure with zero recorded job steps and no runner ID.
The attempted source-free smoke rerun again ended without executed steps.
Those are **NOT_EXECUTED** and cannot establish anything about compiler
behavior or GitHub billing cause.
