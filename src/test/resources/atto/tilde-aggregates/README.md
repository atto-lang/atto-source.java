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
