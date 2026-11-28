# PR #338 — semantic reconciliation of named argument / collection syntax

Historical head: `681fcdfa8abd9fff43a5febbfbaba31ced470c75`. Current main: `1f3912a772f6aa46aacb8a745f4c3cba1015fb73`.

The original parser, lexer, AST, ownership and collection-layout diffs must not overwrite the newer main implementation (including the forward-ported tuple, shaped array and named-argument behavior). The source-language nominal-vs-layout distinction survives: ArrayList<T> is a nominal container, Array[T] denotes a shaped collection, and runtime capacity is not compile-time layout metadata.

This reconciliation adds the historical `ReservedKeywordsDynamicStructTest` as a new compatibility oracle. It covers keyword tokenization, quoted/backtick map keys and typed dynamic-struct negative cases. Modern main's `CollectionShapeMetadataTest`, `ParserTest` and tuple/spread tests remain canonical; do not restore historical snapshots of those files.

The old `TupleSyntaxTest` asserts tuple spellings that must first be compared against today's canonical Tuple/FixedArray design; it is not reintroduced as a passing test without that comparison.

**Status:** Git conflict resolution / proposed test forward-port only. Exact-head JUnit parser, type and guest execution remain mandatory; a historical acceptance test may require adaptation. Preserve current semantics and do not claim full syntax parity until the tests pass.
