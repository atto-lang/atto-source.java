# Closed typed Map language surface

Status: **v1 closed/local-only surface**, stacked on the hardened runtime foundation in #1727.

This document describes the compiler-visible subset implemented before Map
ownership transfer, actor transport, recursive value descriptors, or
`Map.from_entries` are enabled.

## Type

`Map<K,V>` is compiler-known and nominal. It requires exactly two explicit
type arguments.

V1 key domains:

- `String`
- `Symbol`
- `String | Symbol`

String and Symbol keys are distinct even when their spelling is identical.
PROCESS and STABLE Symbols retain the runtime identity contract from the
runtime foundation.

V1 key/value descriptors must be spelled directly at the Map site. Type aliases and generic wrappers around Map, K, or V are rejected until runtime type descriptors are compiler-lowered rather than reconstructed from source AST.

V1 value domains are deliberately reifiable scalars:

- integer
- string
- bool
- Symbol

Nested/recursive value domains such as `Option<T>`, `Result<T,E>`,
records, structs, classes and other Maps remain disabled until the runtime
descriptor and ownership rules are proved end to end.

## Construction and access

The only constructor is:

```ores
val Map<string, int> values = new Map<string, int>();
```

It takes no arguments. Indexed access is typed by K and V:

```ores
values["answer"] = 42;
const int answer = values["answer"];
```

Missing indexed reads fail at runtime rather than yielding host `null`.
The closed member surface is:

- `map.size: int`;
- `map.has(key): bool`;
- `map.get(key): Option<V>`;
- `map.put(key, value): Option<V>`;
- `map.take(key): Option<V>`;
- `map.remove(key): bool`.

`get`, `put` and `take` preserve absence explicitly and therefore do not flatten a future nested value domain. `put` is insert-or-replace and returns the previous value as `Option<V>` (None for a new key). `put`, `take` and `remove` are direct-call-only mutators: the compiler, ownership checker, and evaluator all require a directly owned mutable Map binding. Both synchronous and suspendable lowering verify that authority before evaluating any key/value argument expression. Their bound methods cannot be extracted.

Indexed assignment follows Oreslang's general assignment evaluation rule: the RHS value is evaluated first, then the target receiver and key are evaluated exactly once. For a directly named Map receiver, the evaluator can prove mutation authority from the runtime slot before the RHS runs, so an unauthorized write cannot trigger RHS side effects. Named `put(key, value)` remains an ordinary member call: receiver/authority first, then key and value arguments left-to-right. Both surfaces commit through the same `OresKeyedMap.put` storage/quota path; sharing a storage primitive does not change their language-level evaluation order.

## Ownership

V1 Maps are affine local objects. Every Map binding must own a fresh direct
`new Map<K,V>()` value.

Entry mutation requires mutable-referent authority:

- `val` may mutate entries;
- `let mut` may mutate entries;
- `const` may read but cannot mutate;
- plain `let` may read but cannot mutate.

Whole-Map rebinding is disabled even for `let mut`. This prevents Java
reference aliasing from standing in for a language-level move.

The compiler/ownership/runtime layers fail closed on:

- Map aliases and transfers between bindings;
- nested Map storage in arrays/lists, tuples, records/structs, sum values or
  generic objects;
- callable arguments, parameters, returns and yields;
- closure/deferred callback capture;
- ordinary lexical closure creation while a live Map remains in the snapshotted lexical environment (even when the closure body does not reference it); `nlex` remains valid because it captures no values;
- module/global state;
- class or actor fields;
- channel/select payloads;
- actor-local allocation and actor transport;
- generic type parameter shadowing of the builtin `Map` name.

These restrictions are intentionally stronger than the eventual ownership
model. They can be relaxed only when an explicit move/borrow/copy rule has
executable parity across the type checker, ownership checker, evaluator,
Graal/native backend and actor boundary.

## Suspension

An owned local Map may survive an ordinary `await` in the same async
activation. It may not be captured into a detached callback or closure
snapshot. This distinction keeps ordinary continuation-local ownership while
preventing hidden aliases into independently scheduled work.

## Runtime representation

Guest Maps lower to `OresKeyedMap`; they never lower to `java.util.Map`.
The Java host boundary remains identity-only and opaque. Guest Map equality,
iteration, bulk construction and higher-level methods are separate language
operations and are not inferred from Java collection behavior.

## Deferred API

The next closed-local additions may expose deterministic entry iteration and richer recursively reified value domains. They must preserve the same ownership and missing-value contracts.

Move-only recursive values (including future `JsonValue`) remain gated beyond this scalar v1, but the runtime now has the ownership-preserving commit primitive needed by that lowering.

`OresKeyedMap.tryPutOwned(key, candidate)` has exactly two outcomes:

- **committed** — the candidate is owned by the Map and the result exposes only the previous mapping as `Lookup<V>`;
- **rejected** — the live Map is unchanged and the result owns the **exact candidate object** so compiler lowering can restore its source place (or return it in an error) before rethrowing the original admission failure.

All recoverable domain/graph/quota/policy checks happen before the commit point. The final Map publication uses runtime-owned String/Symbol hashing and invokes no guest callbacks. Fatal VM `Error` conditions such as out-of-memory are outside recoverable guest ownership semantics.

The ordinary scalar `put` API delegates to this primitive and immediately rethrows a rejected admission. Move-only source lowering must not use that convenience path because it would hide the returned candidate.

The remaining compiler gate is **exceptional-flow-aware transfer**: on the normal committed edge the source becomes moved; on a caught admission-failure edge the returned candidate must restore the original owner before the catch body observes it. The existing ownership checker conservatively poisons every move seen inside a try, so source `Map<String, JsonValue>` stays disabled until that move-after-last-recoverable-throw rule is implemented and tested.

`Map.from_entries` remains gated on the nominal `Entry<K,V>` stack and the
separate `MAP_FROM_ENTRIES_CONTRACT.md` design contract.
