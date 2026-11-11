# `maybe` /`?=` source admission fence (2026-11-10)

This repository **does not implement** the newly approved AttoLang `do maybe` / `return maybe` scopes. A production parser must not silently reinterpret them as ordinary expressions, an existing `try` with host exceptions, or the existing `return match` ARM_RESULT construct. Companion cross-backend **draft** [conformance PR #674](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/674) provides candidate `.atto` fixtures and a SOURCE-FREE (not compiler) reference oracle.

The Java parser now gives an actionable **fail-closed diagnostic** for `do maybe` and block-shaped `return maybe` instead of depending on incidental downstream parse failure. `maybe` remains a legal identifier; there is **no global keyword reservation**. This fence is not executable language support. `?=` outside `maybe` is not admitted.

## Release requirements

1. Parse dedicated `MaybeStmt`, `MaybeExpr`, and `FallibleBind` nodes; make `?=` an atomic operator without changing ordinary ternary `?`.
2. Source-ordered, exactly-once evaluation; short-circuit on first failure; statically complete `Err`/`None` alternatives and no implicit error conversions.
3. Every inner `return` is a FUNCTION_RETURN from the enclosing callable, **not** an expression-arm result. Divergent `return maybe` must typecheck with `Never`, and codegen must not emit a second return.
4. LIFO lexical `defer` with explicit scope boundary between `maybe` body and `else`; cleanup and error effects may not intercept actor panic/CONTROL, process fatal, or cancellation incorrectly.
5. Ownership/lifetime, branch definite-return, no host exceptions and no reflection/dynamic codegen; honor current `ReturnCleanupChecker` restrictions for deferred mutation of a returned binding.
6. Add positive and negative Java/GraalVM executable `.atto` tests at an exact PR-head tree, and LLVM O0/O2, .NET NativeAOT, Wasm, Forge guest lanes. Source-only admission, Java-only unit tests, or zero-step workflow results **cannot** be promoted as compiler parity.

This change intentionally adds no guest library functionality: proper implementation requires parser/typechecker and runtime/compiler work, not new Java business logic. Once implementation lands, replace the expected parser-rejection tests with compiler-backed success and negative coverage.
