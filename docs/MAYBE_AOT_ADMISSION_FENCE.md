# AttoLang maybe v1: typed procedural Result/Option lowering (2026-11-10)

**Stacked integration candidate: JAVA/GRAAL compiler execution NOT YET VERIFIED.**

This branch composes restricted maybe with the independently pending keyword try parser (atto-source.java#1719); it does not implement the full dedicated typed MaybeStmt/MaybeExpr CFG. Tests and final cleanup semantics must be independently validated before promotion. **The inherited maybe/try test suites have not been executed.** This draft replaces the temporary Java parser rejection with a source-to-AST lowering for a restricted but useful `maybe` subset. No result from source review or zero-step GitHub Actions establishes executed guest acceptance. It also does not establish LLVM/.NET/Wasm/Forge parity.

## Implemented candidate

- Contextual `do maybe` and `return maybe`, without globally reserving a local variable named `maybe`.
- One atomic `?=` token, distinct from the ternary `?` operator.
- Contiguous `Ok(name) ?= Result[T, E];` / `Some(name) ?= Option[T];` expressions: exactly once left-to-right and short-circuit first failed binding.
- Mandatory `else` with `Err(...)`, `None`, or `_` arms; an appropriate failure pattern is required for each success constructor. The established `checkMatch` checker must additionally prove exhaustive pattern coverage, type compatibility and unreachable handlers.
- Compiles into nested checked, **procedural** `Ast.MatchStmt` control flow, never the expression-valued `MatchValueArm.ARM_RESULT`. `return maybe` permits terminal value expressions via explicit function-return lowering; an inner `return` continues to exit the enclosing callable. The source-level outer return already establishes the return boundary.
- Function-scope `defer` executes when the real function returns. No host-language catch, panic conversion, runtime reflection or dynamic code generation is introduced by this lowering.

## Explicit v1 exclusions

The full specification remains in conformance [PR #674](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/674). In this first implementation, successful binding statements must come before all other body statements. The success tail permits expression statements and a terminal `return`; nested blocks, nested control statements, in-body `defer`, and `?=` interleaved among other statements are **rejected**, because naïvely nested matching would execute in-body cleanup after the failure handler rather than before it. Also not yet admitted: `val x = maybe ...`, nested success patterns, implicit else propagation, and async cleanup.

The `return maybe` expression form currently appears directly in a callable return statement. The parser lowers it to procedural checked matches rather than a first-class `MaybeExpr`; no unverified claim of complete expression-form parity is made. Full AST nodes and a common typed CFG with scope cleanup pads are still required for general use, LLVM/.NET/Wasm/Forge, and a verified `Never`-aware lowering.

## Verify and promote

Run the exact-head Java test suite after runner admission:

```sh
mvn -B -ntp -Dtest=MaybeAdmissionFenceTest,OptionResultSemanticsTest,ValueMatchExpressionTest,CleanupReturnFinalityTest test
mvn -B -ntp verify
```

The new tests include guest-positive cases in `.atto`, parse/typing assertions, and negative rejections. Until those jobs report executed nonzero steps and pass, mark guest/compiler status **NOT_EXECUTED**. Do not merge this draft as feature-complete parity.
