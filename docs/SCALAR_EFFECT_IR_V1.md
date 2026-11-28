# Java/GraalVM scalar source → effect-IR v1 (model only)

`ScalarEffectIrV1.fromSource(source)` runs the ordinary Java compiler
`OresCompiler.parseAndTypeCheck`, including ownership validation, then
performs a separate closed-world effect check. It exports sorted
functions as schema v1 JSON. Every signed i64 literal or binary `+`,
`-`, `*` node yields `compute(units=1)`; no other operation is
assigned a cheap or empty effect by default.

The exporter rejects modules with imports/annotations/contracts,
non-function declarations, actors, parameters, generics, async, await,
suspension, traps, FFI, calls, memory operations, unsupported operators,
multiple return statements, malformed AST and excessive nesting. It caps
input bytes (1 MiB), functions (1024), nodes (100,000), and nesting (64).

`ScalarEffectIrV1Test` checks the actual source-to-IR path against the
same golden JSON used by the Rust compiler and analyzer, including
SHA-256 `8ee1adbf6faef704c1eb6d2b2075c3b2d723097037f646f9e6e518a69d005463`.
It also checks sorting, nested arithmetic and fail-closed cases.

Run `mvn -B -Dtest=ScalarEffectIrV1Test test` on exact PR head.
No JVM runtime traces, codegen costs, target timing guarantees,
authenticity of independently supplied JSON, or cross-backend type/ABI
equivalence are proved by this exporter.
