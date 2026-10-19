# Computed property access: classes, records, structs, tuples, pairs and entries

This is the Java/GraalVM reference contract on the current record-inference
parent branch. LLVM, Wasm and Native Image parity still require separately
executed tests.

| Receiver | Key type | Static result | Write contract |
| --- | --- | --- | --- |
| Closed record/struct | `string` | Exact field for known singleton key, otherwise union of data field types | Respect readonly state, mutability and declared field set |
| Tuple/pair-shaped tuple | `int` | Exact slot for integer literal **or proven constant binding**, otherwise union of finite slots | Ordinary tuples immutable; fixed arrays/lists follow separate rules |
| Ordinary class instance | `string` | Declared visible data field or a safe union of fields | Class visibility, inheritance, initialization and `let` must be enforced |
| Entry-shaped closed record | `string` | Same closed record semantics for `key`, `value`, etc. | Same record rules |
| Module namespace / actor or non-data member | arbitrary | No arbitrary computed introspection | Cannot bypass private access, method dispatch or actor isolation |

Computed indexing evaluates the receiver, then key, once each. A runtime
missing key or out-of-range dynamic tuple index fails without fabricating a
value. A known-invalid literal/constant index is a compile-time error. Empty
closed products cannot produce unchecked `Unknown` indexing types.

When an explicit struct contains uninitialized fields, a dynamic lookup must
fail closed until all fields are initialized. A statically known initialized
field can still be read. A dynamic write must not initialize unknown fields
or mutate a fixed/readonly slot. Computed *field declarations* such as
`record{[key]: value}` remain prohibited; extensible dictionaries use
`Map<K,V>` instead.

```ores
fnc structX(): float {
    const Vec2 d = infer<Vec2> struct{x: 3.0, y: 4.0};
    const key = "x";
    return d[key];                  // float 3.0
}

fnc pairLabel(): string {
    const Pair pair = tuple(3.0, "second");
    const index = 1;
    return pair[index];             // string "second"
}

fnc entryValue(): string {
    const Entry entry = record{key: "id", value: "payload"};
    const key = "value";
    return entry[key];              // string "payload"
}
```

`const int v = d["x"]` is invalid because `3.0` is `float`.
Similarly `record{x: 3.0, y: "boo"}["y"]` is `string`, not `float`.

For heterogeneous values, a general dynamic key yields a union that the
caller must narrow. The parent's integer-constant propagation can retain an
exact index for a `const` variable. Indexing ordinary class **data** fields
is permitted only through the normal visibility checks; class methods
cannot be extracted as computed fields. A two-slot tuple is a pair *shape*,
and an entry is a `{key, value}` record *shape*; nominal third-party
`Pair<K,V>` / `Entry<K,V>` types are not automatically indexable.

## Conformance and provenance

The Java parser/type checker/ownership smoke and JUnit boundary tests must
pass at one exact Git tree, including short tuple alias proof identity,
readonly and visibility rules, inherited fields and runtime bounds errors.
Short tuple aliases follow explicit `infer<T> tuple(...)` admission and do not
manufacture an alias for the empty product. Ownership consumes the same proven
constant integer tuple index used by type checking, while move-only indexed
projections remain fail-closed until projection-level move tracking exists.
Guest tests use nonconstant function-derived keys for runtime error cases,
so constant folding does not reject them at compilation.
Rust formatter, AOT, LLVM and Wasm parity need independent execution proof.
Cross-organization private source mirroring is prohibited unless the
effective security-owner-reviewed destination authorization permits it.
