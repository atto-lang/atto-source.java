# Closed inferred records and data-oriented layout inversion

Status (2026-10-19): canonical `record {...}` plus compatibility `infer record`
and `infer<T> record` parse as closed readonly structural aggregates on this branch.
Plain `record {...}` carries no type-capture intent; only explicit `infer` forms may introduce
an inferred structural alias. **AoS/SoA is a design and
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
effects retain source order. The AST retains distinct `INFERRED_RECORD` and
`INFERRED_STRUCT` origins and the semantic checker now preserves that distinction:
records are readonly structural rows, while inferred/explicit structs are exact
closed concrete shapes. They are not implicitly assignment-compatible. This
avoids dynamic maps or reflective keys while preventing a data-layout struct
from silently erasing into a record shape.
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
The parser/AST now enforce the same maximum 1,024 UTF-16-unit field-name
length and reject NUL and malformed surrogates for inferred/explicit struct
fields and typed structural record fields; unmodified, well-formed Unicode,
spaces and punctuation remain valid. This bounds compiler schema names as
well as host-side column keys without creating locale-sensitive normalization.

The implementation validates exact row/column keys and every element type,
checks equal column lengths, and caps fields at 64 and cells at 1,000,000.
It copies inputs and exposes immutable column/row projections, so callers
cannot mutate or observe mutations to backing input collections. Failures
occur before returning a snapshot. Input maps/lists **must not be mutated
concurrently while a snapshot is being built**: this is not an atomic read
of external mutable collections. Invalid or partially converted data never
becomes visible through a returned snapshot. Zero-row typed schemas work;
empty-schema rows preserve their count when created from rows. Creating from
an empty set of columns is rejected because a row count is not recoverable.

Host field names are canonicalized into Java `String` natural (UTF-16
code-unit) order for deterministic column iteration independent of the input
map's iteration order; this does **not** prescribe a cross-backend wire/ABI
field order or change structural type identity. Valid names preserve exact
Unicode spelling without normalization. Reject NUL, malformed surrogate
sequences, and names longer than 1,024 UTF-16 code units. Empty, whitespace,
punctuation and valid supplementary-plane Unicode keys are allowed.

`scripts/check-soa-snapshot.sh` runs the standalone JDK smoke, while
`SoaSnapshotTest` invokes the same checks through JUnit. The smoke includes
300 seeded field-order/roundtrip cases, plus Unicode rejection and
canonical-column checks. The local audited build used OpenJDK 21
`javac --release 21 -encoding UTF-8 -Xlint:all -Werror` and
`java -Xmx96m -ea`. This is intentionally independent
of actor memory domains, LLVM, guest compiler intrinsics, and FFI.

## Offline JDK-only compiler-front-end gate

`bash scripts/check-record-frontend.sh` compiles the real AST, lexer,
parser, import rules, type representations, TypeChecker, ReturnCleanupChecker,
and OwnershipChecker with OpenJDK 21 and no Maven, Truffle, secrets,
network access or alternate CI organization. Its executable smoke runs
parser -> type checker -> ownership checks over positive and negative named
record/struct aliases, literal/quoted keys, readonly capability protection,
forward explicit-alias conflicts, structural field mismatches and
identity-keyed alias provenance. This is a stronger assertion than the
parser-only `check-record-parser.sh` script.

The exact-head CI gate now runs all **three** JDK-only checks (parser,
front-end compiler and host SoA) before Maven/GraalVM. The frontend smoke
does not execute the guest interpreter or prove GraalVM Native Image, LLVM,
Wasm, formatter, multi-file linked runtime, or runtime data layout.
A successful isolated frontend smoke does not satisfy the remaining
Maven/JUnit/native-image merge requirements.

## Reproducible private CI verification

The standalone oracle is gated by a full checkout test runner in
`scripts/check-record-inference.sh`. Run in the source repository against
the **exact approved source commit**:

```sh
bash scripts/check-record-inference.sh --commit <40-hex-source-commit>
```

For a security-approved private mirror, Git commit identity may differ
because the destination has different parents. Instead verify that its
**complete Git tree SHA** equals the trusted source head's tree SHA:

```sh
bash scripts/check-record-inference.sh --tree <40-hex-source-tree>
```

The test runner rejects a wrong identity, nested checkout and dirty tracked,
untracked **or ignored** files before Maven. Ignored generated classes or
injected Java sources must not contaminate test results. The `--tree` mode
does not itself grant permission to copy private code: require reviewed
destination allowlisting on the protected source branch and verify private
destination identity and full blob/mode/tree equality first. The commands
report the actual checked-out commit and tree. Never substitute successful
host smoke or formatter-only tests for compiler/GraalVM/LLVM test evidence.

`bash scripts/test-record-inference-gate.sh` uses synthetic Git repositories
and stub Maven commands to exercise both identity modes, wrong IDs, dirty
worktrees, ignored source injection, nested checkouts, and missing tools.
That test validates only the **gate script**, not Oreslang compiler semantics.

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
