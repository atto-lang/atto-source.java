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

### Proposed explicit optional binding in conditions

**Preferred new syntax** (requires parser/type-checker/runtime work; not currently implemented):

```ores
if val count = counts.get("widget") then
  // count: int, newly declared only in this success branch
  stdio.println(count);
else
  // count is NOT in scope here
  stdio.println("missing");
fi
```

The `val` keyword is essential: it announces the introduction of a **new temporary lexical binding**, in the same spirit as `if x is type Foo f then`. This avoids accidentally treating `value` in `Some(value)` as a reference to a preexisting variable. It also avoids overload of `is` (which already has identity and nominal-type-test meanings).

**Formal rule:** `if val name = expr then ... fi` requires `expr: Option<T>`; evaluate `expr` **exactly once**, enter the success branch iff it evaluates to `Some(x)`, and initialize `name: T` from that payload. `None` enters the `else` or following `elif` branch. The bound name exists **only** in the successful branch, not in `else`, other `elif` branches, or after `fi`. Do not interpret this as generalized truthiness or equality assignment, and reject non-`Option` expressions. The `let` spelling may later support explicit mutable local rebinding, but start with immutable `val`. Do not use `const`, whose ordinary initializer requirements differ.

**Currently documented alternative** until the optional-binding condition syntax exists:

```ores
if counts.get("widget") matches Some(count) then
  // 'count' is an implicitly declared pattern variable, NOT a prior variable
  stdio.println(count);
fi
```

Both forms perform one lookup and bind only on success. Their typing and scoping must be identical. The formatter should not claim the new syntax is active until the compiler actually supports it; once implemented, prefer the `if val` spelling for single-Option-binding map lookups.

Note: Oreslang brace-form condition bodies **still require the final `fi`** under the current compiler contract, e.g. `if val count = map.get(key) { ... } fi` once optional-binding headers are implemented.


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

A `has(key)` branch does **not** change the declared return type of `get(key)`. It is generally a two-lookup idiom and may race with mutation or become invalid after an alias call. Prefer `if val value = map.get(key) then ... fi` **after** the new optional-binding syntax is implemented (today the existing spelling is `if map.get(key) matches Some(value) then ... fi`). Optimizers may remove redundant lookups only when effect, ownership and version proofs justify doing so.

### Ownership: critical prerequisite before implementing `get` for all V

Rust's `HashMap.get` borrows the stored value; it **does not move** owned `V` out of the map. Oreslang must preserve the same principle **without raw pointer syntax**.

Current `docs/LANGUAGE.md` explicitly prohibits hiding lexical borrows inside owned `Option`/`Result` until lifetime parameters exist. Therefore **do not simply implement `get(): Option<V>` by copying/moving a non-Copy value**. A compiler/runtime that did so would be unsound or unexpectedly expensive.

Implementation staging:
1. First support safe owned `Option<V>` lookups for `Copy` values or explicitly permitted cheaply shareable handles. No implicit cloning of `V`.
2. For move-only or mutable values, provide nonescaping, compiler-enforced scoped access (e.g. a `withValue(key, callback)` API whose callback gets a temporary `rt borrow` and cannot retain it, await, cross an actor boundary, or reenter/mutate the same map).
3. Expand `get` to non-Copy values **only after** an explicit lifetime-aware borrowed-result carrier or equivalent proven ownership mechanism is implemented and tested. Keep the surface spelling `get(key)` if possible; do not pretend `Option<V>` magically owns an interior borrow.
4. `take(key)` / `remove(key)` and replacement are consuming operations and can return an *owned* prior `Option<V>` without cloning. The API must not hide a borrowed payload in an owned `Option`.
5. Iterator entries and borrowed map keys/values must not outlive the map or its borrow, and may not survive unsafe mutation, suspension, or cross-actor transfer. `rt share` only on frozen/share-safe data.

The type-system and standard-library implementation must settle the exact safe borrowed-result representation before claiming unrestricted `get` support.

### Mutation API (proposed, only on MutableMap)

| Operation | Result | Contract |
| --- | --- | --- |
| `set(key, value)` | `Option<V>` | Insert or replace, returning **owned previous value** when present (Rust `insert`-style). No implicit lost copies. |
| `take(key)` | `Option<V>` | **Canonical remove-and-return in one operation**. Move the old `V` out, erase the entry, and return owned `Some(value)`; `None` when absent. No clone or pre-lookup. |
| `remove(key)` | `Option<V>` | Same semantics as `take(key)` for Rust/Java familiarity; not a boolean/delete-only operation. |
| `clear()` | `void` | Remove all entries and dispose/release values under ownership rules. |
| `getOrInsertWith(key, factory)` | ownership-safe read/entry view, TBD | One probe; invoke factory exactly once **when absent** in an ordinary actor-owned map; do not run arbitrary callbacks while exposing unsafe borrows. |
| `entry(key)` | guarded entry, later | Advanced vacant/occupied API after borrow checker and lifetime-scoped entry view are available. |

### Removal and retrieval in the same action

```ores
if val job = pending.take(jobId) then
  // job: Job, now OWNED here; jobId no longer exists in pending
  process(job);
else
  // No entry was removed.
fi
```

Exactly one hash probe and one mutation (modulo implementation details); do **not** implement as `has` plus `get` plus `remove`. The returned `Some(job)` owns the removed value, so non-`Copy` values are legal if ownership transfer is permitted. `map.remove(key)` is also remove-and-return, with identical type/semantics. In an actor-owned mutable map the operation is indivisible with respect to that actor's serialized turns; a future concurrent map must define its own linearizability contract, not assume an actor guarantee.

```ores
val maybeJob: Option<Job> = pending.take(jobId);
// Plain extraction is an Option; conditional binding is only syntactic sugar.
```

For `MutableMap<K, Option<V>>`, a successful removal of a stored `None` is `Some(None)`; it is *not* a missing key. A binding `if val v = map.take(k) then` succeeds for `Some(None)` and binds `v: Option<V>` equal to `None`.

**Negative cases the compiler must reject:** (1) calling `take` on a read-only `Map`, (2) moving a value while an outstanding borrow/iterator references that slot, (3) letting a nonescaping borrow escape through an `if val` binding, (4) using the `val` binding in `else` or after `fi`, and (5) treating `Option<bool>` payload `false` as absence.

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

1. Add `key in map` parsing, precedence, AST, static type checking, bytecode/native lowering and formatter, with tests for `for ... of` and identifiers named `in`. Add **explicit Option conditional binding** (`if val name = expression then ... fi`) with dedicated scope/CFG handling, no truthiness and exactly-once expression evaluation.
2. Add `Map`/ `MutableMap` interface resolution and `HashMap` intrinsic or stdlib implementation. Do not treat a host Java `Map` as the normative language behavior.
3. Prove `get` returns `Option` and never takes ownership of a non-Copy map value accidentally. Explicitly negative-test escaping borrows and actors.
4. Test absent/present, `Some(None)`, false/zero/empty-string stored values, replacement and removal ownership, hash collisions, unstable mutated keys, and custom `Eq/Hash`; specifically test `take`/`remove` as a single remove-and-return action and invalidated borrows.
5. Test single evaluation of lookup/key expression, lazy fallback (called only when absent), callback failure and reentrancy, and iterator mutation invalidation.
6. Exercise low-budget/memory limits, adversarial collisions, actor isolation, cancellation and runtime GC accounting.
7. When changing ownership docs, reconcile legacy `&`/`&mut` examples with canonical `rt borrow`/ `rt take`/ `rt copy` spelling. This RFC does not silently rewrite their current semantics.

## Explicitly non-goals for first vertical slice

- No implicit unwrap from `if map.has(key)` into `get(key): V`.
- No automatic key/value nullability and no JavaScript prototype `in` semantics.
- No implicit cross-actor synchronized mutable hash map.
- No promise that bracket `map[key]` exists. If later introduced, it must have an unambiguous safe/throwing contract.
- No silent copies of non-Copy map values, no raw pointer/reference notation, and no false claim that unimplemented stdlib methods already work.
