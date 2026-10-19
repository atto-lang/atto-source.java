# Computed aggregate access contract (Java/Graal reference)

**Scope:** index expressions on *closed* record/struct values and fixed
tuples, including user-defined pair/entry-shaped values. This document
records the Java compiler/runtime boundary being audited; LLVM parity, AOT
conformance and standalone nominal `Pair`/`Entry` library types are not yet
proven.

## Type-directed bracket lookup

| Receiver | Index | Static result | Runtime behavior |
| --- | --- | --- | --- |
| Closed `record` / inferred `struct` | Literal string key, or immutable `const` with a singleton string type | That field's exact declared type | Lookup by exact key |
| Closed `record` / inferred `struct` | General `string` expression | Union of *all* possible field value types | Missing key throws; never creates a field |
| Finite `tuple`, pair-shaped tuple | Literal integer index | Exact slot type | Checked indexed read |
| Finite `tuple`, pair-shaped tuple | Runtime `int` expression | Union of all possible slot types | Out-of-range index throws |
| Closed `record` used as an entry, e.g. `{key, value}` | Literal/computed string key | Same as any other closed record | Same exact key lookup |
| Nominal class instance | Computed string or integer | **Not admitted** | No unchecked reflection or private-field/method access |
| Empty finite tuple/record | Any index | **Rejected** | No `Unknown` type hole |
| Mutating readonly inferred record/struct with `[]` | Any key | **Rejected** | No dynamic field insertion |

A computed key is not a computed *field declaration*. The declaration
`record{[key]: value}` remains invalid for closed structural values. A
`Map<K,V>` is a separate dynamic-key collection, not a record escape hatch.

The index is evaluated once after the receiver, whether the result is a
field, a bounds error, or a missing-key error. These errors are ordinary
checked runtime failures, not implicit `Option` values. They can be
handled using an existing `trap`/exception path; do not manufacture a
success-shaped value on failed lookup.

## Examples with correct value types

```ores
fnc read(): float {
    const T d = infer<T> struct {x: 3.0, y: 4.0};
    const key = 'x';
    return d[key];                 // 3.0 : float
}

fnc fromPair(): float {
    const T t = tuple(3.0, 4.0);
    const index = 0;
    return t[index];               // 3.0 : float
}

fnc fromEntry(): string {
    const Entry r = record {x: 3.0, y: "boo"};
    const key = "y";
    return r[key];                 // "boo" : string
}
```

The originally suggested `const int v = d[key]` is not type-correct because
`3.0` is `float`. Similarly `r["y"]` is `"boo"`, not `3.0`.

For *heterogeneous* records/tuples, a truly dynamic key produces a union.
The caller must narrow the union before assigning it to a concrete field
type; `int` cannot be assumed merely because one possible slot is an
integer. For tuples, a `const index = 0` currently remains a
**runtime-index path** in type checking (unless the index appears as a
literal in the bracket expression); homogeneous tuples still have a precise
element result, while heterogeneous tuples require narrowing. A future
constant-index data-flow proof must preserve lexical scope, mutation
barriers, control-flow joins, and identical runtime evaluation semantics.

## Required cross-backend verification

1. Java parser, type checker and ownership checker must agree on inferred
   aliases, quoted keys, union results, empty receivers and readonly writes.
2. GraalVM guest execution must confirm the field value and exact runtime
   errors for a missing key or out-of-range index. Private class fields,
   methods and import namespaces must never be exposed by string indexing.
3. The formatter must preserve `record`, `struct`, `tuple`, quoted field
   names and bracket expressions without reinterpretation.
4. LLVM/native, Wasm and AOT backends may claim parity only after executing
   the same positive/negative examples and checking ABI-safe field access.
5. Do not automatically treat library `Pair<K,V>`, `Entry<K,V>` or foreign
   class instances as a built-in structural tuple/record. They need
   explicitly verified declarations and visibility/lifetime contracts.
