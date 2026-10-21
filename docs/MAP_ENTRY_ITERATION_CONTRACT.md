# Typed Map nominal Entry snapshot contract

Status: runtime foundation for deterministic Map iteration, stacked on the closed local-only Map surface.

This slice deliberately does **not** expose guest `Map.entries()` yet. The compiler-visible method is gated on the canonical `Entry<K,V>` language stack. It establishes the runtime behavior that the eventual guest method must lower to.

## Snapshot model

`OresKeyedMap.nominalEntrySnapshot()` returns a finite immutable `List<OresEntry>`.

The snapshot is captured atomically while holding the Map monitor. It is not a live iterator and it does not retain an iterator into mutable Map storage.

Consequences:

- order is deterministic insertion order;
- replacing the value for an existing key does not move that key;
- removing and reinserting a key places it at the end;
- later `put`, `take`, `remove`, or `clear` calls cannot change the snapshot's cardinality, order, keys, or top-level Entry payload references;
- the returned list cannot be mutated;
- each element is the nominal immutable `OresEntry` carrier, never `java.util.Map.Entry`;
- String and Symbol keys remain distinct and are never coerced.

## Ownership boundary

The snapshot detaches the **mapping**, not arbitrary mutable value graphs.

Keys are already restricted to immutable String/Symbol domains. Values are retained as their current local references. Therefore the snapshot is suitable only for local guest-value iteration in this slice.

It is **not** an actor, untrusted, serialization, or interop transport primitive. In this runtime-only stack, `ActorRuntime.freeze(OresEntry)` remains fail-closed because #1668's Entry transport integration has not been semantically merged. Any future transport must independently apply the established freeze/copy/share policy recursively to each Entry component.

## Callback and quota behavior

Snapshot construction:

- is bounded by the Map's existing maximum-entry quota;
- allocates at most one top-level `OresEntry` carrier per stored mapping;
- performs no guest equality, hashing, formatting, or policy-validator callbacks;
- never calls host `equals`, `hashCode`, or `toString` on stored values.

The existing Java `entrySnapshot()` remains a host/testing compatibility surface. Guest iteration must use the nominal Entry snapshot and must not expose Java Map.Entry mutation APIs.

## Language integration gate

The eventual guest surface should be:

```ores
const entries: Array<Entry<string, int>> = values.entries();
```

It should lower to this snapshot operation and preserve the same finite snapshot semantics across direct and suspendable evaluation.

Do not implement `Map.from_entries` in this slice. That remains gated on the separate duplicate-key, canonicalization, quota, and atomic-publication contract.
