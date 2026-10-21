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
Nonthrowing lookup APIs will be added as explicit Map operations in a later
slice.

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

The next closed-local additions may expose operations such as `has`,
nonthrowing `get`, `take`, `remove`, `size` and deterministic entry
iteration. They must preserve the same ownership and missing-value contracts.

`Map.from_entries` remains gated on the nominal `Entry<K,V>` stack and the
separate `MAP_FROM_ENTRIES_CONTRACT.md` design contract.
