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
- Reads and removes of known keys also validate their current values before returning a typed `Option<T>`; this detects mutable alias corruption rather than exposing a mismatched value. Before `on_get`, dynamic reads additionally verify that the stored payload can be copied (rather than borrowed or moved): rejected reads do not trigger the read hook.
- `void`/null payloads, cyclic runtime values during recursive schema checking, and recursively nested values deeper than 64 levels are rejected. A finite nested `Option`/`Result`/`Array`/`List` value is validated recursively; `unknown` remains an open top type.
- Typed key payloads must be runtime-reifiable. Bare Map construction, Map subclasses with type parameters, second-generation Map subclass inheritance, unsupported parameterized payload types and suspending/mutable-parameter hooks are rejected statically.
- Open maps are mutable, identity-bearing objects. A map insertion moves non-Copy values according to ordinary Oreslang ownership rules. Mutable aliases must not be used after moves; implicit actor/async value transport of map objects is not a supported isolation boundary.
- Overwriting a stored **non-`Copy`** owned value via `put` is rejected before the hook or storage changes. Call `remove()` to transfer and handle the old owner, then `put` the replacement. Overwriting primitive or recursively `Copy` sum values is allowed. This fail-closed behavior prevents silent resource loss until native exact-once disposal and explicit replacement semantics are implemented.
- Non-consuming `get` and bracket reads return only recursively `Copy` values; typed reads of owned collections/objects fail compilation, and dynamic-key reads of such values fail at runtime. Use `remove()` for ownership transfer; a checked scoped borrow/view API is a future separate feature.
- Hook calls apply to top-level map operations, **not** in-place mutations of nested values. The actor's critical/borrow guard remains a separate requirement before sharing actor state.
- Bracket access uses the same read/write hooks as `get` and `put`. `remove` invokes `on_remove`.
- Hooks execute synchronously in the calling actor/task, may raise an error to reject an operation, and must not suspend.
- `put`, `remove`, and indexed writes require exclusive mutable receiver access; immutable borrows cannot mutate a map. Both method calls and indexed writes reject borrowed references, callable closures, and unresolved generics as stored payloads, including borrows nested in sum types.
- For present entries, `on_get` and `on_remove` hooks run **after initial value validation**. The runtime validates again after the hook before returning or removing the value. `on_put` likewise revalidates the incoming value and existing overwrite safety **after** the hook, before committing a write. Hooks still fire for missing keys and can have observable effects, but cannot reenter the same Map; reentrancy is rejected before storage access or validation. Safe readonly-hook effects and actor `critical` guard enforcement require further compiler integration.
- The underlying map retains arbitrary additional string keys.
- The compiler owns map operations: classes cannot override `get`, `put`, or `remove`; interception is through the three hooks.
- All supported public operations route to the same storage implementation; no iterator-based mutable entry API is exported in this iteration.

Current scope: direct `Map<string, unknown>` inheritance in Java/GraalVM. No general `Map<K,V>` constructor, mutation-view APIs, inherited map subclass behavior, LLVM lowering, JS/WASM lowering, or actor critical-guard enforcement is claimed yet. This feature must not be treated as cross-backend parity until those workstreams and their tests land.

## Audit note — key declarations

`key` declarations are schema metadata, not ordinary class fields. `pub key` and `private key` must not be used as access-control promises unless the compiler/runtime enforces key visibility on string/bracket lookup. The current first-iteration contract should fail closed on such visibility modifiers. Runtime interception hooks (`on_get`, `on_put`, `on_remove`) are framework callbacks and must not be callable as ordinary user methods. Direct `HashMap` subclass support is not claimed by this branch; the implemented parent is `Map<string, unknown>`.

Audit status: current-main port pending.
