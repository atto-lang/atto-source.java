# Entry values

Oreslang distinguishes positional products from key/value products.

Tuples have a nominal runtime carrier, `OresTuple`, distinct from Array/List
values. A tuple and an array with identical elements **do not compare equal**
and cannot satisfy each other's runtime type tests or tuple patterns. The
carrier is immutable and retains its tuple-kind tag across synchronous and
suspendable evaluation, typed returns, async value detachment, and actor
freeze/private-heap copy. Returning an array from a tuple-declared function
(or a tuple from an Array-declared function) fails closed at the return boundary
rather than silently retagging a Java list. Builtin fallback `tuple(...)`
produces the same nominal tuple carrier as `tuple { ... }`; a user-defined
`tuple` function still wins by the existing lexical/arity rules.

Java host `OresTuple.equals/hashCode` are identity-only, just as for
`OresEntry`; Oreslang `==` uses explicit guest tuple value semantics. This
is an internal tagged List adapter, **not** a generic Java List value-equivalence
API, and Java host equality must never substitute for guest matching.


```ores
const pair = (1, 2);              // Tuple<int,int>
const explicit = tuple {1, 2};    // Tuple<int,int>
const entry = Entry.of(1, 2);     // Entry<int,int>
```

`Entry<K,V>` is a compiler-known **nominal**, readonly key/value product. It exposes the two fields:

```ores
{
  key: K,
  value: V
}
```

`Entry.of(key, value)` infers both type arguments. Entry fields are immutable and preserve their names through member and checked computed access. A structural record or struct with fields named `key` and `value` is **not** assignable to `Entry<K,V>`; callers must construct an Entry explicitly.

Standalone `null` is not an Entry component value. Oreslang represents optional absence with `Option<T>`, so use `Entry<Option<K>, V>` / `Entry<K, Option<V>>` when either component may be absent. The host `OresEntry` carrier enforces the same non-null invariant.


### Equality and host-runtime boundary

Oreslang `Entry` equality compares `key` and `value` recursively using **guest-language**
value equality. The Java host carrier uses **identity-based** `equals`/`hashCode`
and an opaque `toString`; those Java operations intentionally cannot invoke
payload callbacks or traverse cyclic/hostile object graphs. Code implementing
future typed Maps must use the explicit Oreslang key equality/hash contract,
**not** `java.util.Map<OresEntry, ...>` or Java `Object.equals` as a semantic shortcut.
The built-in `Entry` is immutable at its top level; ownership and actor
isolation rules still govern mutable nested payloads.

Guest equality is alias-insensitive structural/value comparison: distinct
`Entry` carriers with equal key/value graphs compare equal, regardless of
sharing. Cyclic value graphs are compared using an identity-pair visited set
(coinductive graph traversal); cyclic structure must not recurse through the
Java call stack. The evaluator uses a bounded worklist (65,536 comparison
steps), raising a language-level error if the limit is exceeded rather than
silently treating an incomplete comparison as equal or unequal. A guest
Map key hash must eventually agree with these equality semantics; the Java
identity hash is not a valid substitute.

The equality evaluator only traverses known guest List and record carriers,
including actor-transport snapshots. A foreign Java List/Map implementation
is opaque, even if it implements those interfaces: guest equality must never
implicitly execute its user-defined size/get/entrySet/containsKey callbacks.
Structural record keys must be strings. This is intentionally distinct from
the future typed Map contract, whose keys may have other approved domains.


An array of entries is distinct from an array of arrays or tuples:

```ores
const arrays = [[1, 2], [3, 4]];                    // Array<Array<int>>
const pairs = [(1, 2), (3, 4)];                     // Array<Tuple<int,int>>
const entries = [Entry.of(1, 2), Entry.of(3, 4)];   // Array<Entry<int,int>>
```

Entries do **not** impose key uniqueness. This is valid:

```ores
const entries = [
  Entry.of(1, "first"),
  Entry.of(1, "second")
];
```

Uniqueness is a collection invariant of a Map, not a value invariant of Entry.
A future/current typed-Map integration may consume entry sequences and reject or explicitly resolve duplicate keys, but Entry construction itself never performs global uniqueness checks. Actor freeze/private-isolation preserves the Entry tag while recursively validating and copying its key and value.

There is no separate `Pair<A,B>` built-in in this slice. A two-element tuple is
the positional pair representation; use Entry only when the semantic roles are
specifically key and value.

## Map integration boundary

This change deliberately does not introduce a second guest Map runtime. Current
typed-Map work owns Map storage, key-domain, mutation, ownership and actor
transport semantics. `Map.from_entries` must be implemented on that foundation
rather than by reusing Java `Map`/record storage as guest collection policy.
