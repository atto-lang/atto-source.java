# Typed empty Map guest construction — staged compiler primitive

This fixture family is the first **empty-only**, explicitly typed Map<K,V>
guest construction slice, stacked on Java tilde record PR #2715.

- `~map<string,int>()` and `~<Map<Symbol,bool>>()` must both produce
  the existing `OresKeyedMap` runtime type. `size` is the only guest-accessible
  member of this new constructor path; both values must initially have size 0.
- Only canonical String or Symbol keys and primitive int/bool/string/Symbol
  values are initially admitted. Neither aliases nor unconstrained
  generic type parameters are inferred; host/guest key domains are not
  unified by implicit conversions.
- `~map()` is accepted only as the **direct initializer of an explicitly
  declared `Map<K,V>` local binding**, in either `const Map<K,V> m = ~map()`
  or `const m: Map<K,V> = ~map()` spelling. The parser preserves the exact
  K/V witness and the typechecker/ownership gate still validates the
  canonical storage and supported scalar domains.
- Untyped `const m = ~map()`, unsupported/unknown key/value types and
  non-Map bindings are rejected during compilation. Full contextual type
  propagation through returns, calls, branches, class fields and aliases
  is **not yet enabled**; no `Any` or runtime reflection fallback exists.
- The source-only exact-head parser verification on a private runner
  proves syntax/AST and diagnostics, **not** ownership, Truffle guest execution,
  runtime allocation, Maven/JUnit success, or five-backend parity.
- Nonempty map entries, put/get/take, map spreads, duplicate-key rollback,
  actor transport, and full serialization are **not supported** by this PR.
- Source-org Actions runner-admission failures with zero executed steps
  do **not** prove this test suite passed. Mark all guest tests NOT_EXECUTED
  until verified on the exact source commit SHA.
