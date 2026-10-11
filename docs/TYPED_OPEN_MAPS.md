# Typed open Maps (GraalVM implementation, first iteration)

A class extending `Map<string, unknown>` may declare a static schema for certain string keys without allocating corresponding object fields:

```oreslang
define class MyMap extends Map<string, unknown> as
  key fieldX: Option<string>;
  key fieldY: Option<bool>;

  on_get(string key): void {
    // Audit an attempted read.
    return;
  }
  on_put(string key, unknown value): void {
    // Audit a validated write; throwing aborts the write.
    return;
  }
  on_remove(string key): void {
    return;
  }
end

fnc example(): void {
  val m = new MyMap();
  m.put("fieldX", "hello");
  m["fieldY"] = true;
  m.put("other", 42);
  val Option<string> value = m.get("fieldX");
  val Option<unknown> anything = m.get("other");
  return;
}
```

- `key` is contextual within a class declaration; it does not create a field, constructor parameter, or storage slot.
- `Option<T>` describes the *presence* of a key; the stored value is `T` (not an Option wrapper).
- Literal-key accesses and `get` / `put` / `remove` are statically typed. A nonliteral key returns `Option<unknown>` and accepts unknown values.
- Dynamic writes are validated again at the storage boundary. Invalid writes do not invoke `on_put` and do not modify storage.
- Reads and removes of known keys also validate their current values before returning a typed `Option<T>`; this detects mutable alias corruption rather than exposing a mismatched value.
- `void`/null payloads, cyclic runtime values during recursive schema checking, and recursively nested values deeper than 64 levels are rejected. A finite nested `Option`/`Result`/`Array`/`List` value is validated recursively; `unknown` remains an open top type.
- Typed key payloads must be runtime-reifiable. Bare Map construction, Map subclasses with type parameters, second-generation Map subclass inheritance, unsupported parameterized payload types and suspending/mutable-parameter hooks are rejected statically.
- Open maps are mutable, identity-bearing objects. A map insertion moves non-Copy values according to ordinary Oreslang ownership rules. Mutable aliases must not be used after moves; implicit actor/async value transport of map objects is not a supported isolation boundary.
- Hook calls apply to top-level map operations, **not** in-place mutations of nested values. The actor's critical/borrow guard remains a separate requirement before sharing actor state.
- Bracket access uses the same read/write hooks as `get` and `put`. `remove` invokes `on_remove`.
- Hooks execute synchronously in the calling actor/task, may raise an error to reject an operation, and must not suspend.
- `put`, `remove`, and indexed writes require exclusive mutable receiver access; immutable borrows cannot mutate a map. Both method calls and indexed writes reject borrowed references, callable closures, and unresolved generics as stored payloads, including borrows nested in sum types.
- A `get` hook runs before the validated read and may have observable effects; it cannot reenter the same Map. Safe readonly-hook effects and actor `critical` guard enforcement require further compiler integration.
- The underlying map retains arbitrary additional string keys.
- The compiler owns map operations: classes cannot override `get`, `put`, or `remove`; interception is through the three hooks.
- All supported public operations route to the same storage implementation; no iterator-based mutable entry API is exported in this iteration.

Current scope: direct `Map<string, unknown>` inheritance in Java/GraalVM. No general `Map<K,V>` constructor, mutation-view APIs, inherited map subclass behavior, LLVM lowering, JS/WASM lowering, or actor critical-guard enforcement is claimed yet. This feature must not be treated as cross-backend parity until those workstreams and their tests land.
