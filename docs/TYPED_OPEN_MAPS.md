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
- Bracket access uses the same read/write hooks as `get` and `put`. `remove` invokes `on_remove`.
- Hooks execute synchronously in the calling actor/task, may raise an error to reject an operation, and must not suspend.
- The underlying map retains arbitrary additional string keys.
- The compiler owns map operations: classes cannot override `get`, `put`, or `remove`; interception is through the three hooks.
- All supported public operations route to the same storage implementation; no iterator-based mutable entry API is exported in this iteration.

Current scope: direct `Map<string, unknown>` inheritance in Java/GraalVM. No general `Map<K,V>` constructor, mutation-view APIs, inherited map subclass behavior, LLVM lowering, JS/WASM lowering, or actor critical-guard enforcement is claimed yet. This feature must not be treated as cross-backend parity until those workstreams and their tests land.
