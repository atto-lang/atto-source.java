# Closed inferred records and data-oriented layout inversion

Status (2026-10-18): `infer record` and `infer<T> record` parse as closed
readonly structural aggregates on this branch. **AoS/SoA is a design and
admission contract, NOT an executable language feature.** No SIMD, GPU,
native backend, or cross-actor support is claimed by this document.

## Source-level record semantics

```ores
const Person p = infer<Person> record{
    "first name": "Ada",
    age: 37
};
```

The compiler infers field names/types, widens literal fields under the
preexisting rules, and creates lexical structural alias `Person`, not nominal
identity. Field order does not affect structural equivalence, but initializer
effects retain source order. The implementation reuses `INFERRED_STRUCT`
readonly AST/runtime semantics, avoiding dynamic maps or reflective keys.
Computed keys are invalid, and mutable access cannot be recovered through
aliases, casts, borrowing, or generic substitutions.

The short `const Person p = infer record{...}` form is enabled in the
Java static checker only for an absent, uppercase-starting, simple name; a
known `Person` remains an ordinary declared type. Explicit `infer<Person>`
still creates a fresh lexical alias and fails on conflicts. This avoids
silently redefining imported, generic, built-in, or already-inferred names.
Both syntaxes still need full formatter/AOT and executable conformance.

## Executable host-side AoS/SoA scalar foundation

`dev.oreslang.dop.SoaSnapshot` is a **host Java** immutable snapshot, not
`SoA<T>` in Oreslang. It requires an explicit closed schema and accepts
only String, Boolean, Integer, Long, Float, and Double. Nulls, opaque host
Objects, capabilities, nested structures, and Option payloads are excluded.
Quoted static field names, including empty strings, are treated as data.

The implementation validates exact row/column keys and every element type,
checks equal column lengths, and caps fields at 64 and cells at 1,000,000.
It copies inputs and exposes immutable column/row projections, so callers
cannot mutate or observe mutations to backing input collections. Failures
occur before returning a snapshot. Zero-row typed schemas work; empty-schema
rows preserve their count when created from rows. Creating from an empty
set of columns is rejected because a row count is not recoverable.

`scripts/check-soa-snapshot.sh` runs the standalone JDK smoke, while
`SoaSnapshotTest` invokes the same checks through JUnit. The smoke includes
300 seeded field-order/roundtrip cases. This is intentionally independent
of actor memory domains, LLVM, guest compiler intrinsics, and FFI.

## Proposed columnar surface (future, NOT YET EXECUTABLE)

```ores
const Person person = infer<Person> record{name:"Ada", age:37};
const Array<Person> rows = arr[person];
const SoA<Person> columns = soa(rows);
const Array<Person> roundtrip = aos(columns);
```

A structurally named record remains structurally typed. A nominal struct
remains nominal. Layout transformation never converts a structural row
into nominal authority, or exposes private fields.

`SoA<Row>` has an independent column for every statically admitted field
of `Row`, with one logical row count. `AoSoA<Row, N>` is a future
vector-friendly blocked layout; neither is an automatic consequence of
`infer record`. A semantic record's quoted string keys remain exact.

## Obligations before executable admission

1. The compiler must prove a finite closed row schema, stable field IDs,
   recursively data-only element types, accessible fields, and a common row
   count across columns (including an empty dataset).
2. An owned `soa(rows)`/ `aos(columns)` conversion must either copy data
   or prove an ownership move compatible with target storage. Never claim
   contiguous columns from an ordinary AoS strided view.
3. Borrowed `soa_view(rows)` is a lifetime-scoped projection only;
   immutable views cannot write, and writable views require exclusive
   access. No borrow, unsafe raw pointer or hot-reload generation may escape.
4. Insert, delete, grow, shrink, throw/raise/panic/cancel paths must
   commit/rollback all columns atomically. No half-rows, hidden
   reallocation, or unequal lengths.
5. Option fields use a defined presence representation; nested schemas,
   zero-length columns, mixed scalar widths, alignment, endianness and
   heterogeneous values need target-specific layout/ABI proofs.
6. Deterministic serializations and schema digests must use canonical
   field names/IDs, not incidental map iteration order or physical offsets.
   Quoted names are data and do not become dynamic field expressions.
7. GPU/SIMD lowering, scatter/gather, AoSoA chunking and FFI access are
   separately feature-gated and benchmarked for each backend. No device
   capability, GC, OS, or platform allocator is required by freestanding core.
8. Actor transport may transfer only validated, data-only, owned or frozen
   snapshots and must honor memory domains, size limits and capabilities.
9. Conformance requires real compiled Oreslang positive/negative programs
   for Java/GraalVM, LLVM and other claimed targets; source-free model tests
   and parser admission alone are not executable parity.

## Required test matrix before SoA APIs are enabled

- AoS -> SoA -> AoS equality over 0/1/many rows, field order permutation,
  unusual quoted keys, nested records, and Option payloads.
- No-op, failed, and cancelled conversions leave source ownership valid
  under exactly the declared contract; no double frees or stale borrows.
- Missing, extra, private, duplicate and computed field names reject.
- Row insertion/deletion under allocation failure never corrupts column
  length invariants; frozen snapshots never permit mutation.
- Native layout, alignment, actor-domain capability/ownership and
  canonical wire-digest vectors agree with backend-specific oracle tests.
