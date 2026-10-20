# Closed typed Map guest contract

Status: **first guest/compiler slice**, stacked on the hardened runtime foundation in #1727.

This slice exposes only a closed, ownership-conservative `Map<K,V>`. It deliberately does not expose typed-open Map schemas or `Map.from_entries`.

## Reified domains

Supported key types:

- `String`
- `Symbol`
- `String | Symbol`

Supported value types in this slice:

- `int`
- `bool`
- `String`
- `Symbol`

Nested `Option`/`Result`, aggregate, generic and user-defined value domains are deferred until they can be runtime-reified without reopening arbitrary host validation callbacks.

## Construction and ownership

Canonical construction is:

```ores
val m = new Map<String, int>();
```

A mutable Map is affine and identity-bearing.

Until explicit container moves and non-lexical Map loans are implemented:

- a local Map binding must own a fresh `new Map<K,V>()`;
- aliases and rebinding are rejected;
- Maps cannot be returned;
- Maps cannot be captured by closures;
- Maps cannot be passed as ordinary call arguments;
- Maps cannot be stored in arrays/lists, tuples, structs/records, class/actor fields, or module/global state;
- Map allocation inside actor code is rejected;
- actor transport remains rejected by the runtime foundation.

`val` grants mutable referent authority without permitting binding rebinding. `let mut` may also mutate the referent. Ordinary `let` and `const` bindings cannot mutate or consume Map entries.

## Operations

- `map[key]` reads a required present value of type `V`;
- `map[key] = value` requires the exact directly owned mutable binding;
- `map.has(key) -> bool`;
- `map.get(key) -> Option<V>`;
- `map.take(key) -> Option<V>` and requires the exact mutable owner;
- `map.remove(key) -> bool` and requires the exact mutable owner.

Map operations are sealed direct operations. They cannot be extracted as first-class members or routed through host interop.

Missing indexed reads remain errors; non-throwing presence is represented by `get`/`take`.

## Runtime boundary

The compiler lowers directly to `OresKeyedMap` from #1727. The evaluator independently checks that destructive operations target the exact runtime object stored in the authorized mutable owner slot.

The runtime preserves nominal String/Symbol key separation, bounded storage, atomic lookup/take, transactional bulk admission, opaque host identity and fail-closed actor/untrusted transport.

## Deferred work

A later stack may add recursively reified `Option`/`Result` values after a sealed descriptor mechanism exists. Nominal `Entry<K,V>` ingestion and guest `Map.from_entries` remain separate and must be reconciled with the current Entry/aggregate stack before exposure.
