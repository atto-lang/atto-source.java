# Entry values

Oreslang distinguishes positional products from key/value products.

```ores
const pair = (1, 2);              // Tuple<int,int>
const explicit = tuple {1, 2};    // Tuple<int,int>
const entry = Entry.of(1, 2);     // Entry<int,int>
```

`Entry<K,V>` is the compiler-known readonly structural product:

```ores
{
  key: K,
  value: V
}
```

`Entry.of(key, value)` infers both type arguments. Entry fields are immutable and
preserve their names through member and checked computed access.

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
A future/current typed-Map integration may consume entry sequences and reject or
explicitly resolve duplicate keys, but Entry construction itself never performs
global uniqueness checks.

There is no separate `Pair<A,B>` built-in in this slice. A two-element tuple is
the positional pair representation; use Entry only when the semantic roles are
specifically key and value.

## Map integration boundary

This change deliberately does not introduce a second guest Map runtime. Current
typed-Map work owns Map storage, key-domain, mutation, ownership and actor
transport semantics. `Map.from_entries` must be implemented on that foundation
rather than by reusing Java `Map`/record storage as guest collection policy.
