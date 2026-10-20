# Entry values

Oreslang distinguishes positional products from key/value products.

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

`null` is preserved as an ordinary Entry component when the inferred or declared component type admits `null`; the host carrier must not reject a value that the guest typechecker accepts.


### Equality and host-runtime boundary

Oreslang `Entry` equality compares `key` and `value` recursively using **guest-language**
value equality. The Java host carrier uses **identity-based** `equals`/`hashCode`
and an opaque `toString`; those Java operations intentionally cannot invoke
payload callbacks or traverse cyclic/hostile object graphs. Code implementing
future typed Maps must use the explicit Oreslang key equality/hash contract,
**not** `java.util.Map<OresEntry, ...>` or Java `Object.equals` as a semantic shortcut.
The built-in `Entry` is immutable at its top level; ownership and actor
isolation rules still govern mutable nested payloads.

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
