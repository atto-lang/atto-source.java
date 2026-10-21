# Immutable structural-record `with` updates

This Java/Graal slice implements the source semantics selected by the
cross-backend JSON/record contract in `oreslang-common-source#111`.

## Surface

```ores
const base = record{name: "Ada", age: 37};
const updated = base with {
    age: "thirty-seven",
    active: true
};
```

`with` is a contextual infix operator. Its right-hand side is a compiler-owned
record-literal body with statically named fields. It is not dynamic property
mutation and it is not a method call.

## Type semantics

The result is a fresh closed readonly structural-record row:

- fields missing from the update are retained;
- an existing field is replaced and may receive a different static type;
- a new field widens the result row;
- update names are compile-time static and duplicate names are rejected;
- structs remain fixed-shape values and are not valid row-extension bases;
- classes, Maps, borrowed records and readonly structural contract projections
  are not valid bases.

Runtime-computed keys belong to the typed Map surface instead.

## Ownership

Current record containers are move-only. Therefore `base with {...}`
**consumes `base`**. The implementation must not fake persistence by leaving
two writable/owning aliases to nested move-only values.

A future optimization may preserve/reuse the base only when an explicit
copy/share proof says every retained value is safe to duplicate or structurally
share.

## Evaluation and failure

The base is evaluated once. Update expressions are evaluated exactly once,
left-to-right, including across `await`.

The runtime never mutates the base or the update record. It builds a fresh
`ReadonlyRecordValue` only after the complete RHS record has been constructed.
Therefore an update-expression failure cannot publish a partially updated
record.

Existing field positions are retained by the runtime `LinkedHashMap`; newly
introduced fields append in update-source order. Static structural typing is
field-name/type based and does not depend on runtime field order.

## Backend parity

LLVM/native must reject this syntax before partial lowering until it implements
the same row typing, ownership consumption, evaluation ordering, and runtime
construction semantics. Existing copy-only record/tuple spread work is not a
substitute for `with`.
