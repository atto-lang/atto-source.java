# Typed empty Map.of: staged guest construction contract

Canonical `Map.of<K,V>()` and directly context-typed `Map.of()` represent an
empty Map<K,V> with the existing OresKeyedMap guest runtime. They replace
retired `~map<K,V>()`, `~map()`, and `~<Map<K,V>>()` constructors.

- Only verified key/value types are admitted. Empty maps have size 0.
- `Map.of()` without a supported type witness, incompatible declared types,
  and unverified generic domains must fail during compiler/typechecker
  admission; ordinary call parsing alone is insufficient.
- `Map.of<K,V>()` is checked for the exact number and domain of type args.
- `Map.of(...)` nonempty values follow the separate scalar Map contract.
- Class shadowing must not redirect nominal/user-defined factories into
  compiler-owned Map lowering; integrated symbol-resolution tests are a
  merge blocker.
- Positive and negative fixtures here are **AUTHORED / NOT_EXECUTED**.
  Java #2735 factory lowering is stacked and not present on the removal branch;
  JUnit/guest/Native Image and cross-backend parity are **not certified**.
