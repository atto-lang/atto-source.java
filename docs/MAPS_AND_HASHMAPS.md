# Maps and HashMaps: lookup, ownership, and iteration (design RFC)

> **Status:** proposed language/standard-library contract, **not** a claim that Map, HashMap, or the `in` operator are already implemented. Changes to lexer/parser, type checker, runtime, formatter, tests and stdlib remain separate implementation work.
>
> The public Oreslang interface uses **no C/C++ pointer operators** such as `&` or `*`. Ownership follows Oreslang `rt borrow`, `rt copy`, `rt share`, `rt take` and compiler-verified actor boundaries.

## Design sources and the choices we adopt

- **C# `Dictionary.TryGetValue`**: use a *single* lookup to test and extract a value; no default-value sentinel and no mandatory double lookup.
- **Rust `HashMap`**: explicit `Option` for absence; `entry`-style conditional insertion; defined ownership of replaced/removed values; `Eq`/`Hash` key contracts; salted hashing. Do not import raw-reference syntax.
- **Java `Map`**: separate abstract map operations from concrete hashing and provide lazy compute-if-absent; *do not* import Java's ambiguous `null`-as-absence behavior.
- **Erlang `maps:find` and persistent maps**: explicit present/missing results and immutable snapshots/copy-on-write maps for isolation; keep mutable and persistent APIs distinct.
- **JavaScript/Node.js `Map.has/get/set`**: familiar method names, but *do not* import `undefined`-as-absence or JavaScript `in` (which tests object properties, not `Map` keys).

References:
- https://learn.microsoft.com/en-us/dotnet/api/system.collections.generic.idictionary-2.trygetvalue
- https://doc.rust-lang.org/std/collections/struct.HashMap.html
- https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Map.html
- https://www.erlang.org/doc/apps/stdlib/maps.html
- https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Map/has

## Names and types

- `Map<K, V>`: read-only map interface/protocol (no mutating methods). Not equivalent to a structural `obj{...}` record; map keys are dynamic values, object field names are not map type parameters.
- `MutableMap<K, V>`: extended capability to mutate a map.
- `HashMap<K, V>`: hash-backed, actor-owned mutable map; implements the above interfaces.
- Future: `ImmutableMap<K,V>` (persistent functional updates) and `OrderedMap<K,V>` (explicit iteration order). Do not promise stable insertion order from `HashMap`.

Use a single generic representation with no implicit nullable values. The keys and values in `HashMap<string,int>` are strings and ints, **not** `Option<string>` and `Option<int>`.

### Read API (proposed)

| Operation | Type | Contract |
| --- | --- | --- |
| `has(key)` | `bool` | Key membership, never value truthiness. |
| `key in map` | `bool` | Exact alias of `map.has(key)`; key on left, map on right. |
| `get(key)` | `Option<V>` | Safe lookup; absence is `None`, present value is `Some(value)`. Subject to ownership rules below. |
| `getOr(key, fallback)` | `V` | Default only for missing key. A supplied fallback expression is evaluated normally before the call. |
| `getOrElse(key, factory)` | `V` | Lazily evaluate `factory` only for a missing key. |
| `getOrThrow(key)` | `V` | Explicit missing-key failure; never silently return a default. |
| `size()`, `isEmpty()` | `int`, `bool` | Number of entries / emptiness. |
| `keys()`, `values()`, `entries()` | iterators/views | Explicit iteration; entry iteration yields tuples `(K,V)` under safe borrowing rules. |

Canonical safe single lookup using **already documented** Oreslang `matches` syntax:

```ores
if counts.get("widget") matches Some(count) then
  stdio.println(count);
else
  stdio.println("missing");
fi
```

The new `in` syntax should be:

```ores
if "widget" in counts then
  stdio.println("present");
fi
```

The `in` infix is **key membership only**, not an identity, substring, sequence-value, prototype or property-inheritance test. Keep it distinct from the existing `for ... of` iteration syntax. Prefer a *contextual* operator token if necessary to avoid breaking existing identifiers; parse at comparison/membership precedence and reject ambiguous unparenthesized predicate chains.

### Absence and optional values

For `Map<K, V>`: `get(key): Option<V>` (subject to borrowing restriction below).

For `Map<K, Option<V>>`: `get(key): Option<Option<V>>`:
- `None` => absent key;
- `Some(None)` => present key, explicitly empty value;
- `Some(Some(value))` => present key with payload.

Never conflate `None` with missing just because the stored value is itself an `Option`. No bare `null` or `undefined` sentinel and no truthiness test.

A `has(key)` branch does **not** change the declared return type of `get(key)`. It is generally a two-lookup idiom and may race with mutation or become invalid after an alias call. Prefer `if map.get(key) matches Some(value) then ... fi`. Optimizers may remove redundant lookups only when effect, ownership and version proofs justify doing so.

### Ownership: critical prerequisite before implementing `get` for all V

Rust's `HashMap.get` borrows the stored value; it **does not move** owned `V` out of the map. Oreslang must preserve the same principle **without raw pointer syntax**.

Current `docs/LANGUAGE.md` explicitly prohibits hiding lexical borrows inside owned `Option`/`Result` until lifetime parameters exist. Therefore **do not simply implement `get(): Option<V>` by copying/moving a non-Copy value**. A compiler/runtime that did so would be unsound or unexpectedly expensive.

Implementation staging:
1. First support safe owned `Option<V>` lookups for `Copy` values or explicitly permitted cheaply shareable handles. No implicit cloning of `V`.
2. For move-only or mutable values, provide nonescaping, compiler-enforced scoped access (e.g. a `withValue(key, callback)` API whose callback gets a temporary `rt borrow` and cannot retain it, await, cross an actor boundary, or reenter/mutate the same map).
3. Expand `get` to non-Copy values **only after** an explicit lifetime-aware borrowed-result carrier or equivalent proven ownership mechanism is implemented and tested. Keep the surface spelling `get(key)` if possible; do not pretend `Option<V>` magically owns an interior borrow.
4. `remove(key)` and replacement are consuming operations and can return an *owned* prior `Option<V>` without cloning.
5. Iterator entries and borrowed map keys/values must not outlive the map or its borrow, and may not survive unsafe mutation, suspension, or cross-actor transfer. `rt share` only on frozen/share-safe data.

The type-system and standard-library implementation must settle the exact safe borrowed-result representation before claiming unrestricted `get` support.

### Mutation API (proposed, only on MutableMap)

| Operation | Result | Contract |
| --- | --- | --- |
| `set(key, value)` | `Option<V>` | Insert or replace, returning **owned previous value** when present (Rust `insert`-style). No implicit lost copies. |
| `remove(key)` | `Option<V>` | Remove and return **owned** previous value; `None` when absent. |
| `clear()` | `void` | Remove all entries and dispose/release values under ownership rules. |
| `getOrInsertWith(key, factory)` | ownership-safe read/entry view, TBD | One probe; invoke factory exactly once **when absent** in an ordinary actor-owned map; do not run arbitrary callbacks while exposing unsafe borrows. |
| `entry(key)` | guarded entry, later | Advanced vacant/occupied API after borrow checker and lifetime-scoped entry view are available. |

Keep a clear distinction: mutable `HashMap.set` mutates, whereas future immutable/persistent `ImmutableMap.put` returns a **new** map leaving the old map unchanged. `Map` read-only interfaces must not accidentally expose mutation via aliases.

Do not advertise the conditional insert operation as globally atomic. For an actor-owned `HashMap`, each actor's serialized turn gives local isolation, but asynchronous callbacks / suspension may invalidate a read-before-write sequence. A future `ConcurrentMap` needs explicitly documented linearizable/atomic operations with no implicit user-code `await` inside a lock.

### Hashing, equality and safety invariants

- `K` must satisfy a coherent, deterministic-for-key-lifetime `Eq + Hash` protocol. If `a.isEqualsTo(b)` is true as map-key equality, their hashes **must** match. A mutable key may not change hash/equality while stored.
- Equality for map values is independent from key equality: `Map.isEqualsTo(other)` compares mappings independent of hash iteration order, only where key/value comparability is supported.
- Class/reference keys use identity by default unless an explicit stable `Eq/Hash` implementation supplies value semantics. Strings use exact case-sensitive equality absent an explicit alternate comparer.
- Do not silently admit floating NaN values as keys without an explicit equivalence/hash policy or key wrapper.
- Default hash maps use a per-map or per-runtime randomized hash seed to resist collision DoS. No promise about iteration order or stable hashes across runs.
- Establish limits on map size, hash probe/collision effort, allocation and actor memory accounting. Avoid adversary-controlled hash function choices for untrusted actor inputs.
- Mutation during live iteration must be rejected or handled by a specified fail-fast/versioning strategy, never left undefined. Snapshot views need explicit cost and ownership semantics.
- Never expose a mutable map directly across actor heaps. Transfer ownership with `rt take`, copy eligible data with `rt copy`, or share immutable approved snapshots with `rt share`. Actor messaging remains the mutation boundary.

### Parser / formatter / testing worklist

1. Add `key in map` parsing, precedence, AST, static type checking, bytecode/native lowering and formatter, with tests for `for ... of` and identifiers named `in`.
2. Add `Map`/ `MutableMap` interface resolution and `HashMap` intrinsic or stdlib implementation. Do not treat a host Java `Map` as the normative language behavior.
3. Prove `get` returns `Option` and never takes ownership of a non-Copy map value accidentally. Explicitly negative-test escaping borrows and actors.
4. Test absent/present, `Some(None)`, false/zero/empty-string stored values, replacement and removal ownership, hash collisions, unstable mutated keys, and custom `Eq/Hash`.
5. Test single evaluation of lookup/key expression, lazy fallback (called only when absent), callback failure and reentrancy, and iterator mutation invalidation.
6. Exercise low-budget/memory limits, adversarial collisions, actor isolation, cancellation and runtime GC accounting.
7. When changing ownership docs, reconcile legacy `&`/`&mut` examples with canonical `rt borrow`/ `rt take`/ `rt copy` spelling. This RFC does not silently rewrite their current semantics.

## Explicitly non-goals for first vertical slice

- No implicit unwrap from `if map.has(key)` into `get(key): V`.
- No automatic key/value nullability and no JavaScript prototype `in` semantics.
- No implicit cross-actor synchronized mutable hash map.
- No promise that bracket `map[key]` exists. If later introduced, it must have an unambiguous safe/throwing contract.
- No silent copies of non-Copy map values, no raw pointer/reference notation, and no false claim that unimplemented stdlib methods already work.
