# AttoLang tilde aggregates — Java/Graal reference fixtures

These are **real guest-source .atto fixtures** for the first readonly record
parser/compiler implementation slice, not a claim that guest execution passed.

- `records-guest-pass.atto` targets real Truffle guest execution and compares
  stdout with `records-guest-pass.stdout`.
- `records-nested-pass.atto` and `records-explicit-empty-pass.atto`
  target positive compiler/typecheck admission.
- `records-*-reject.atto` target parser or type/ownership rejection,
  while `unary-tilde-pass.atto` guards the existing unary `~` operator.
- Every `maps-*-not-enabled.atto` fixture **must fail** at the parser stage
  while typed Map<K,V> guest admission, equality/hash eligibility, atomic
  duplicate detection, ownership cleanup and AOT lowering are pending.
  Promote these to positive/negative tests only after implementation review.

The JUnit runner is `TildeRecordLiteralGuestTest`. No new legacy `.ores`
fixtures are introduced. Cross-backend runtimes, formatting, structural
record patterns, and AOT parity remain independently gated.

**Status: source fixtures committed; real guest execution NOT_EXECUTED until
exact-head JVM/Graal runs provide step receipts and outcomes.**

## Explicit tuple and typed-empty extension

- `tuples-empty-pass.atto` and `tuples-two-pass.atto` cover `~tuple()`
  and `~tuple(1, "two")`. They reuse the canonical tuple AST.
- `typed-empty-{map,record,tuple,generic}-parser-only.atto` are parser-positive
  **and typechecker-negative for now**. The lexer/parser preserves the full
  `~<T>()` type witness in `Ast.TypedEmptyAggregateExpr`, but no compiler
  backend may construct it before the kind/zero-initialization proof and
  ownership-safe lowering are implemented.
- `typed-empty-{argument,missing-type}-reject.atto` are grammar-negative.
  An explicit type witness does not permit arbitrary positional defaults.
- The shared design authority is
  `oreslang-common-source/contracts/v3/ATTO_TILDE_TYPED_EMPTY_AGGREGATES_V3.md`.
  Its positive semantic expectations are **NOT IMPLEMENTED** by this slice.

Current JUnit file: `TildeRecordLiteralGuestTest` (10 source-authored test
methods; no claim of executed tests or backend parity).

## Concrete typed-empty guest lowering (new first implementation slice)

- `typed-empty-guest-pass.atto` exercises `~<{}>()` and `~<[]>()` alongside
  `~record()` and `~tuple()` through the real Truffle guest root.
- `typed-empty-guest-pass.stdout` is the expected stdout receipt.
- `typed-empty-required-{record,tuple}-reject.atto`,
  `typed-empty-unresolved-reject.atto`, and
  `typed-empty-nominal-reject.atto` are negative typechecker fixtures.
- Only *syntactically concrete* zero-field readonly-record and zero-arity
  tuple witnesses are admitted. Generic aliases, unresolved types, nominal
  classes and typed Maps must still fail before code generation. A future
  compiler-proven alias/generic-construction feature requires its own
  lowering and lifetime checks; no runtime reflection is permitted.
- All guest tests remain **NOT_EXECUTED** while source CI fails at zero
  steps; authored assertions and golden files are not execution evidence.
