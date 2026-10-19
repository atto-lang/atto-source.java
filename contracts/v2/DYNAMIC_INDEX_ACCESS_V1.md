# Dynamic bracket access — checked field-view contract (Java/Graal v1)

Status: draft implementation on Java/Graal only. The compiler and runtime have **not** passed exact-head Maven/JUnit/GraalVM/native-image verification. LLVM/Wasm/formatter and other frontends are not certified. This document does not enable bare `record {...}`, `Pair<K,V>`, or `Entry<K,V>` in the parser.

## Read contract

- A statically resolved closed structural record or struct accepts a string key. Literal keys select one exact declared field type. Runtime-selected string keys produce the union of all fields visible through the receiver's **static** type.
- A nominal non-actor class accepts a string key for visible data fields. Public fields are available outside the class, private fields only within their declaration owner. Inherited generic field types are substituted before union formation. Methods, actor state and reflective metadata are not indexable.
- A tuple or fixed positional product accepts an integer index. Literal indexes must be within arity; dynamic keys return a union of element types and require runtime bounds checks.
- Arrays and lists retain their existing integer indexing semantics. No implicit conversion of strings to indexes, or indexes to strings, occurs.
- The runtime evaluates the receiver and key once in left-to-right order, then dispatches. Missing, inaccessible and uninitialized fields fail explicitly.
- For a partially initialized explicit struct, a compile-time-known literal key can read an already initialized field. An unknown key cannot be proven to avoid uninitialized fields and is rejected until the entire value is initialized; an explicitly uninitialized literal key is always rejected.

## Hidden-field security

A closed record's static type is a **view**, not a promise that the physical backing container has no extra fields. Width projection through a cast, parameter, or return must never enable access to omitted fields via a dynamic key.

Every closed-record/class IndexExpr is checked by the frontend and receives a weak AST-identity-bound immutable set of permitted field names. The evaluator requires that proof *before* performing Map/OresObject access. Same-text AST nodes never share authority; compiler rewrites explicitly propagate only existing checked key sets, never mint new authority. Class runtime access independently repeats nominal field visibility and uninitialized-field checks. No host/Java interop is permitted through this path.

**Backend obligation:** Any compiler pass cloning IndexExpr must either propagate the checked access proof or re-run full static checking on the rewritten AST. Failure to propagate must fail closed, not turn into unchecked access. For native lowering, a proof becomes a layout-specific fixed key table/bounds check, not a free reflective map lookup.

## Writes

Tuple indexed assignment must be statically rejected, as fixed tuple runtime values are immutable products. Arrays and lists retain mutable indexed assignment when types and owner permits. Bracket writes to nominal class fields or structural fields remain disabled in this phase; a future admission gate must prove mutability, borrow exclusivity, field-type assignment for each runtime-selectable key, and constructor definite-assignment constraints before enabling writes. No runtime fallback to an unchecked map write is allowed.

## Additional planned product types

- `Pair<A,B>` is a positional two-field product: `p[0] : A`, `p[1] : B`, and `p[i] : A | B` for a runtime integer, with checked bounds.
- `Entry<K,V>` is a named pair: `entry["key"] : K`, `entry["value"] : V`, and `entry[s] : K | V` for a runtime string. Numeric aliases `0`/`1` are a separate syntax decision.
- Neither type is declared or lowered by this PR; tuple-of-two already supports the positional subset.

## Verification gates

1. Run all `CheckedDynamicIndexAccessTest` tests under JDK 25 + GraalVM with exact source SHA.
2. Execute standard Maven/JUnit, interpreter guest execution, Native Image AOT/hybrid and polyglot-isolate gates.
3. Add LLVM equivalent access plans with the same static-view enforcement and signed bounds safety.
4. Exercise inheritance, structural width-narrowing through parameters/casts/returns, non-escaping borrows, private fields, actor isolation, cancellation, and optimizer cloning.
5. Fail closed when source-only semantics and backend implementations are missing; do not merge a security change on runner-admission failures.
