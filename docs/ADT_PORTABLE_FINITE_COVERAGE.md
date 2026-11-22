# Java/GraalVM portable ADT subset gates

The current Java main compiler handles built-in `Option<T>` and
`Result<T,E>` constructor patterns with recursive finite-exhaustiveness
proofs. It has **not** integrated the full user-defined nominal enum
proposal on its current main branch.

`AdtPortableFiniteCoverageTest` pins four Git-blob-identical `.ores`
sources from `oreslang-platform-conformance` #270: a runnable
Option/Result bool partition and three semantic negative cases.
It additionally checks a nested product-of-sums coverage proof, guarded
fallthrough, foreign constructor rejection and invalid guard typing.

The positive fixture requires an actual GraalVM `Context.eval` with
byte-exact stdout, not a parser-only or fake evaluator pass. The three
negatives require *semantic* diagnostics, not generic unsupported syntax.
These tests are authored, not claimed as executed until JDK 25 exact-head
Maven/JUnit CI produces a stepful result.

Separate user-defined `define enum` parsing and nominal owner/tag/payload
lowering must be reconciled from existing draft #623/#907/#1049 without
overwriting modern compiler code. Portable six-backend parity (Java,
LLVM, Wasm, .NET Native AOT, Cranelift, Forge) still requires checked
guest execution on each target before certification.
