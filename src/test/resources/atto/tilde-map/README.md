# Nonempty typed Map: DRAFT guest conformance

These fixture cases target a **narrow scalar-only** nonempty Map constructor
stacked on #2725, not a complete collection implementation.

- Explicit `~map<K,V>(k => v)` and directly context-typed
  `~map(k => v)` / `~(k => v)` construct the canonical guest
  `OresKeyedMap` with ordered, atomic batch commit.
- Static typechecking verifies every entry against the explicit K/V.
  Ownership checking rejects borrow and move-only payloads for now.
- Runtime preflights duplicate *logical* String and Symbol keys, then
  validates the entire staged batch with existing OresKeyedMap quotas.
  Failure cannot publish a partially built Map.
- Expressions evaluate once, left to right; **external side effects of
  user expressions are not rolled back**. Only construction/storage is
  atomic. No entries are made visible before the final commit.
- `~map()` without a directly declared `Map<K,V>`, alias/generic
  type witnesses, map spread, mutable/lookup guest APIs and actor transport
  remain rejected; `~()` still rejects as ambiguous.
- Positive guest .atto files, negative source files and stdout receipts
  are **AUTHORED, NOT_EXECUTED** unless supported by exact-head JVM
  compiler/Truffle logs. Isolated parser and Map-storage CI are distinct
  proofs and cannot certify compiled guest behavior.
