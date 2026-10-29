# Sealed actor state forward-port — reconciliation record (2026-10-29)

**This branch is a draft integration candidate, not a passing compiler build.**

Source of feature hunks:
- [#1804](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1804), sealed single state schema
- [#1806](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1806), scoped lexical state loan
- [#1807](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1807), prototype actor integration

Current-main base: `9f4f4a3b75dd4b124e8509b65a88a98213c11ab5`.
The #1806 branch diverges from current main by over 1,700 intervening commits.
**It is not safe to replace whole compiler files with the older versions.**

## Source-level reconciliation performed

- `OresEvalRootNode.java`: all six old feature patch hunks matched current main without conflicting context.
- `OwnershipChecker.java`: both hunks matched.
- `TypeChecker.java`: seven hunks matched.
- `Parser.java`: older actor-class parser signatures and concurrency/lifecycle code
  differed, so the single-schema parser rules were transplanted at current
  actor-member boundaries. Keep the current `ActorPrefix`, transport,
  concurrency, `tear_down` and supervision rejection logic untouched.
- Restored original `ActorSingleStateSchemaTest` and `ActorStateLoanTest`
  into the new tree for Java/Graal execution. No build/guest test has yet
  run on this exact reconciled tree.

## Current implemented subset vs mandatory target

The port stores one schema as sealed field annotations, rejects mixed
legacy ordinary actor fields and duplicate schemas, rejects direct
state-field reads/writes and offers the first inline lexical callback.
The callback is currently type-checked as `void` and cannot suspend
or capture/escape nested callbacks. Java host admission is only the
prototype; inspect generation fencing and physical isolated actor arenas
before production.

**Not satisfied yet:** the mandatory lexical
`self.handle_state(critical |s| -> { ... })` spelling/critical effect
admission. #1806's positive JUnit example instead uses
`self.handle_state(|s| -> { ... })`; its callback returns `void`.
Neither returning a value from the callback nor treating
`return self.handle_state(...)` as a value-bearing actor API is currently
supported. The 20 AI userland actor sources use these unsupported
value-returning method patterns. Rewrite their public actor APIs to typed
mailbox messages and explicit post-loan result publication **only after**
compiler-backed return/loan semantics are agreed.

Do not silently use a noncritical callback as a substitute for a critical
write. Before merging, reconcile the canonical `with state.write` effects
and scoped `using state.write(critical |s| -> ...)` contract from
[#760](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/760),
preserving all mainline effects, and verify no unexpected permission widening.

## Verification required before promoting drafts

1. Clean authorized GraalVM/JDK build with full Maven compile of the *exact*
   reconciled tree, source/tree SHA, dependency provenance and nonzero CI steps.
2. Execute the two imported JUnit suites, and add explicit negative tests
   for multiple schemas, ordinary field escape, `self.state`, forged
   `handle_state`, live-loan suspension, nested loans, mutable reference
   escape, private arena denial and stale actor generation.
3. Add critical-write enforcement and typed effect checking; require
   one immutable state snapshot or exclusive critical write loan per
   mutation. Guest sends, LLM calls and network I/O must be outside loan.
4. Run the step-function coordinator state guest and the AI graph/memory
   guest against the compiler with exact stdout/negative diagnostics.
5. Repeat identical guest fixtures on LLVM, .NET and Wasm when supported.
   In-memory state is not durable WAL/outbox recovery.

No new private source was mirrored to another organization in this work.

## Critical-loan transitive-effect hardening (2026-10-29)

The original #1806 checker recursively inspected call arguments but **admitted the call itself**. That allowed guest I/O such as `stdio.stdout.write` or an actor method with unknown effects while holding an exclusive state loan. The reconciled branch now fails closed on *all* calls, object construction, trap expressions and unbounded `loop` forms inside a live state callback. This is an intentionally restrictive P0 rule until a transitive pure/nonblocking effect proof and bounded resource admission exist.

The imported positive loan JUnit fixture moves stdout **after** closing its loans. Three new negative JUnit cases cover stdout, unverified helper calls, and unbounded loop. These tests have **not executed** on this exact head because GitHub Actions continues to fail before job admission; they are requirements, not passing evidence.

All for-of, for, do/while and bare loop forms are also rejected inside a loan until a checkable finite bound is available. This intentionally blocks the graph actors' current unbounded traversal callbacks; the corrective design must separate an immutable snapshot/traversal phase from a brief critical commit and enforce bounded admission.
