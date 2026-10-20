# Typed Map.from_entries v1

This slice introduces a small, current-main typed Map contract built on the
nominal `Entry<K,V>` value.

## Construction

```ores
const entries = [
  Entry.of(1, "one"),
  Entry.of(2, "two")
];

const Result<Map<int, String>, DuplicateKey<int>> built =
    Map.from_entries(entries);
```

`Map.from_entries(Array<Entry<K,V>>)` infers `K` and `V` and returns:

```ores
Result<Map<K,V>, DuplicateKey<K>>
```

Entry sequences may contain duplicate keys. Uniqueness is enforced only when
constructing the Map. The first duplicate causes construction to return
`Err(DuplicateKey<K>)`; values are never silently overwritten.

`DuplicateKey<K>` is immutable and exposes `.key`.

## Key domains

v1 supports exactly one key domain per Map:

- `int`
- `String`
- `Symbol`

Integer keys use canonical integral identity internally, so host integer
representation differences cannot create multiple logical key `1` values.
The original guest key is retained for `entries()` and iteration.

String and Symbol remain nominally distinct. A String-keyed Map rejects a
Symbol lookup and vice versa. PROCESS and STABLE Symbol identity continues to
follow `OresSymbol` logical equality.

The storage is insertion ordered and bounded:

- at most 4,096 entries;
- at most 16,384 bytes for one key;
- at most 1 MiB aggregate encoded key footprint.

## Values

v1 is deliberately read-only after construction. Non-consuming reads therefore
require recursively copy-safe values. The compiler admits scalar values,
Symbols, nominal Entries, Options/Results and finite products only when their
components satisfy the copy-safe v1 contract. Mutable Lists/Arrays, ordinary
class instances, capabilities, Futures, Channels, mutex guards, and other
move-only values are rejected.

This restriction can be relaxed later when Map borrows / `take` are part of
the public ownership model.

## Reads and iteration

```ores
const map = built.unwrap();

const String required = map[1];       // raises when key is absent
const bool exists = map.has(2);
const Option<String> maybe = map.get(2);
const int count = map.size;
const entries = map.entries();

for let entry of map {
  stdio.println(entry.key);
  stdio.println(entry.value);
}
```

Iteration and `entries()` preserve deterministic insertion order and yield
nominal `Entry<K,V>` values.

No Map equality operator is defined in v1. Code must compare the required
keys/values explicitly rather than accidentally receiving JVM identity
semantics.

## Isolation and ownership

`Map.from_entries` consumes the input sequence in the ownership checker.
The resulting immutable Map is copy-safe only when its key and value types are
copy-safe.

Async task detachment creates a fresh immutable mapping object. Actor mailbox
transport, actor callable boundary types, `SharedMutex` state, and
`process.share_readonly` are **not** enabled by this slice. The compiler and
runtime fail closed instead of treating a JVM object as a proven actor-safe
container.

## Runtime representation

Guest Map uses dedicated `OresKeyedMap`; it intentionally does **not**
implement `java.util.Map`. Record/struct member dispatch and Java mutable-map
escape paths therefore cannot impersonate guest `Map<K,V>`.

The runtime stores an internal canonical lookup key separately from the
original guest `Entry`. Canonicalization is used for duplicate detection and
lookup only; it does not rewrite user-visible keys.

## Deferred work

This v1 slice does not claim:

- Map mutation / `put`, `remove`, `take`, or `clear`;
- HashMap or a general Hashable trait;
- heterogeneous union-key Maps;
- actor transport or shared-memory Map state;
- map serialization/wire ABI;
- schema/open-key Map subclasses from historical draft #1104;
- LLVM backend parity;
- Map equality.

Those should be layered onto the current implementation with exact-source
cross-backend tests rather than reviving the stale #1104 compiler/runtime
files wholesale.
