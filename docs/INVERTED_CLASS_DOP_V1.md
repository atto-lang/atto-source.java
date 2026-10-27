# Inverted classes and columnar DOP — stage-one contract (2026-10-18)

Status: **proposed language syntax; native reference storage implemented separately**. Neither Java/Truffle nor LLVM source compilers currently accept executable `define inverted class` programs. This is a normative staging contract and must not be cited as a complete compiler feature.

Related: [Java DOP issue #211](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/211), existing native `DopLayout.hpp` (AoS/SoA/AoSoA) and `DopKernelIR.hpp` (typed CPU + generated GPU kernels).

## Principle

Logical row API does **not** imply a row-wise physical object. `inverted class` declares a **nominal, class-like row API whose owning collection defaults to SoA**. It does not silently change `Array<Foo>` layout, allocate one JVM/OS heap object per row, provide pointer-stable physical addresses, or give automatic vectorization/GPU execution.

## Surface syntax — not yet executable

```oreslang
define inverted class Particle as
    float x;
    float y;
    float vx;
    float vy;

    move(float dt): void {
        self.x += self.vx * dt;
        self.y += self.vy * dt;
    }
end

let particles = InvertedArray<Particle>.with_capacity(100000);
let h = particles.push(Particle { x: 1.0, y: 2.0, vx: 3.0, vy: 4.0 });
particles[h].move(0.016);
let xs = rt borrow mut particles.columns.x;
```

Canonical grammar insertion: `class_decl = visibility "define" [ "abstract" | "inverted" ] "class" ...`. The stage-one semantic gate rejects `abstract inverted`, inherited/inheriting classes, overriding virtual dispatch, dynamic field layouts, and nontrivial field resources until ownership/destructor lowering exists. Import/export and static methods follow ordinary class visibility rules. No unqualified field widening or implicit AoS-to-SoA conversion.

## Physical storage / ABI

`InvertedArray<Particle>` owns `x[]`, `y[]`, `vx[]`, `vy[]` independently (column contiguous); all columns have identical live row count. No individual mutable `Particle` allocation is required. A row reference is a checked store+slot+generation handle (not a raw pointer); temporary row views are borrowed, nonescaping projections. A materialized single-row snapshot is a value, never a live mutable alias. `to_aos()` / `from_aos()` conversions are explicit and may copy.

The initial native reference admits only pointer-free arithmetic and enum scalar fields; fixed-size POD records remain deferred until the compiler can prove transitive pointer-freedom. Canonical booleans have 0/1 byte representation (no `vector<bool>` bit-proxy ABI). Strings, heap-owning/recursive objects, `Option<T>` tagging, nested flattening, alignment-sensitive FFI, and resource-owning destructors require independent representation decisions and tests.

`push` is all-or-nothing for logical row lengths even on allocation failure; `erase_swap` can reorder physical rows, but surviving handles follow their rows. Deleted handles must fail; reused slots must advance generation; no generation wraparound can resurrect a stale handle. Foreign-store handles fail. `erase_stable` is a distinct optional O(n) operation and must never be silently substituted.

The existing native `DopLayout.hpp` covers homogeneous-width POD geometry and remains authoritative for its current CPU/GPU kernel ABI. The new `DopInvertedColumns.hpp` is a CPU reference collection for **heterogeneous** trivial field types; it is not wired to LLVM IR or device dispatch and does not claim an ABI shared with Java yet.

## Native reference additions / specific limits

The native `InvertedColumns<Fields...>` model currently exposes `reserve(n)`, `push(...)`, `erase_swap(handle)`, `get<I>(handle)`, `set<I>(handle,value)`, `get_row(handle)`, `set_row(handle,...)`, and typed `borrow_read<I>()` / `borrow_write<I>()`. `get_row` and `set_row` take the same store lock to avoid exposing partially updated whole-row values. Resizing/reallocation and row changes are rejected while any field is loaned. Huge allocations can fail without changing logical row count or handle validity.

Owner IDs saturate rather than wrap, and slot generations never revive a stale handle. After owner destruction, a loan cannot issue a *new* span, though an existing raw C++ `span` may outlive its scope if host code misuses it. The source compiler must prohibit such escape; this C++ reference alone is **not** an actor-isolation or teardown-revocation proof.

[Native exact-source carrier audit #39182285383](https://github.com/scintilla-run-test/gleam-runner-conformance/actions/runs/39182285383): 4/4 GCC/Clang normal/ASan+UBSan matrix passed with exact file SHA verification, negative type admission, deterministic row property stress and concurrency cases. This is host-model evidence only, not full LLVM CMake/CTest or Java guest compilation evidence.

## Bracket indexing: staged typed generated-lowering contract

The parser already represents `receiver[index]` as `Ast.IndexExpr`. **Do not
implement inverted indexing as an untyped textual macro** or reinterpret it as
an ordinary AoS array operation. Resolve `InvertedArray<Foo>` and the index
type before code generation. Until verified borrow/escape admission is wired,
guest inverted indexing remains **fail-closed**.

- `rows[i]` with `i32` or `i64` selects position `i` in the **current
  dense ordering**, zero-based. Reject negative, out-of-bounds and wide
  non-narrowable indices before narrowing. Never index spare capacity.
- `rows[h]` with a compiler-issued typed row handle selects stable logical
  identity. Every use checks owner authority, live slot and generation; stale,
  foreign, deleted, recycled and closed-store handles fail.
- Swap-delete changes physical positions. A previously selected surviving
  handle continues to name that row, not whichever row replaces its former
  position. A later numeric subscript uses the new positions.
- For `rows[i].field`, `rows[h].field`, method invocation, nested member
  access and assignment, evaluate receiver/index/RHS exactly once in language
  order. Bind the declared column accessor statically; do not materialize an
  implicit AoS row or perform reflection-based runtime member lookup.
  Compound updates need a verified atomic transform, not a racy get/set pair.
- A row projection is nonescaping: no storing it in a field, returning it,
  capturing it in Task/actor work, or suspending across await. A field write
  requires exclusive loan rights. Structural mutation requires all loans
  quiescent. Teardown revokes every pending view.
- Ordinary arrays, tuples, maps, strings, slices and custom indexers retain
  their own indexed-access rules. Invalid index types, wrong generic row
  handles or unverified native/GPU targets fail closed.

Java host reference now has `InvertedColumnStore.handleAt(long)` and
`InvertedArray.at(long)` / `at(Handle)` as **potential generated lowering
targets**. They are not proof of source `[]` code generation or Java/LLVM
guest parity. Promotion requires executable source positive and compile-negative
fixtures on pinned backend heads (including malicious/overflow indices,
swap-delete, reuse, loans, concurrent mutation and owner teardown).

## Hidden generated binding namespace and physical transpose

A source declaration introduces exactly one **nominal compiler symbol identity**
for its inverted layout. Its generated implementation details reside in a
separate, non-importable **compiler-internal binding namespace**, not in the
user's namespace and not as exported synthetic identifiers. Fields named
`row`, `at`, `columns`, `bindField`, or `__ores_internal_row` are ordinary
user fields, not reserved implementation slots. Methods, fields, type names,
and imports each follow their declared namespaces: never emit string-based
`__Foo_field` symbols that can collide with any of them.

The **transpose is logical, not a full-data transpose at every access**.
At allocation/schema construction, direct fields get stable physical column
ordinals. The frontend resolves a guest field symbol once and the lowered
access carries an opaque `(nominal layout identity, field ordinal, kind)`
binding. Field slots are compiled references, not a runtime string switch.
For cross-module imports or generic specializations, this nominal identity
must be the resolved unique type/symbol ID, **not only the short class name**.
Runtime-generated reference bindings are per immutable `Schema` instance;
different owners may intentionally share one schema, but independently
resolved schemas cannot exchange generated binding tokens merely because
their field names or physical layouts happen to match.

Proposed generated lowering for an indexed column access:

```text
source:        rows[i].x
resolve:       rows: InvertedArray<Particle>; x: FieldBinding<Particle, f64>
lower:         tmpStore = evaluate(rows) once
               tmpIndex = evaluate(i) once
               tmpStore.getAt(tmpIndex, resolved_x_column)
physical:      x_column[tmpIndex]   // checked against LIVE count, not capacity
```

A positional `rows[i].x = rhs` must evaluate receiver, index, RHS exactly
once according to Oreslang sequencing, then perform one checked positional
write transaction. Java's reference `getAt(index, column)` and
`setAt(index, column, value)` each perform row-index validation, loan
validation, and one physical column operation under **one store lock**.
This avoids a structural swap-delete between a separately selected numeric
row and a subsequent field load/store. At the host facade,
`Schema.FieldBinding` represents the private generated namespace, while
`ColumnAccess.getAt/setAt` avoids per-access `RowView` or row-handle
allocation. This is a lowering *target*, not actual enabled guest emission.

Stable `rows[h].x` still uses checked owner/slot/generation handles.
Compound operations (`+=`, `++`, etc.) must use a verified atomic
read-modify-write transform; **do not lower them to separate getAt/setAt**.
Method calls must lower `self` into a checked store/row context, not a
heap row. A nested ordinary fixed record remains packed as one opaque
column element. Generating an AoS mirror, implicit whole-table transpose,
reflective property access, runtime macros, or an untyped fallback is
not permitted.

### Atomic compound indexed operations

A generated `rows[i].field += expression` must never be implemented as
separate `getAt` and `setAt` calls. Resolve the receiver and index once,
then use a **single** verified, non-suspending read-modify-write transaction
that keeps the current row, field loan state, and column access synchronized
for the entire mutation. The value expression must be side-effect/effect
checked and evaluated with the language's specified order. If its effects
cannot be proven to fit this atomic transaction, reject rather than secretly
reorder evaluation or reselect a different row.

The Java host reference now stages package-private
`InvertedColumnStore.transformAt(long, column, verifiedTransform)`:
the callback sees only a detached scalar or copied opaque record payload,
nested store access taints the transaction (even if the exception is
swallowed), invalid field values/throws do not publish any write, and
`finally` releases the transform guard after every thrown `Throwable`.
This is **not** permission for arbitrary guest closures to run under the
monitor, block scheduler threads, perform I/O or cross await/Task boundaries.
Full guest code generation and ownership proofs remain disabled.

### Closed primitive compound-lowering targets

The Java host now provides `ColumnAccess.addF64At(denseIndex, delta)`
and `ColumnAccess.addF64(issuedHandle, delta)` as narrow checked lowering
targets for a compiler-proven **f64 addition**. Both route to a single
synchronized primitive read-modify-write: no callback, boxing into a
generated row object, runtime name lookup, or split `getAt/setAt` race.
The first selects the current dense position; the second validates and follows
an opaque generation-checked handle through swap-delete. Disjoint column loans
are preserved, while overlapping column loans reject the mutation.

These host methods **do not** enable source-language `+=`. The type/effect
checker must still prove that the source RHS evaluates exactly once in Oreslang
order, uses f64 arithmetic and no unsupported suspension/callback effects.
Do not silently extend this f64-only primitive to integer overflow modes,
f32 precision, records, or general user overloads before their semantics and
cross-backend differential tests are specified.

## Borrow and concurrency rules

A column view is zero-copy and tied to its owning store; compiler-enforced, nonescaping lifetime is mandatory. Read loans on a field may coexist; a write loan excludes all other same-field access. Disjoint columns may have independent write loans. Structural changes (push, erase, reorder, reserve/reallocate, close, ownership transfer) require quiescence across *all* columns; no suspension or `await` may extend an unsafe exclusive loan without an explicit proof. A raw host-language `span` must not escape its loan; the compiler is responsible for enforcing this when lowered to Oreslang.

A region/task privilege planner should eventually express `reads`, `writes`, `discards` and `reduces(op)`; unsound unknown aliases must serialize or be rejected. `critical` waiting must park the Oreslang task instead of blocking an actor/main/CONTROL scheduler carrier thread.

Each mutable collection has exactly one **owner domain**, which may be an ordinary stack/heap allocation, a structured Task scope, an actor-local arena, or an explicitly authorized shared domain. **Neither `InvertedArray<T>` nor columnar DOP requires an actor.** Disjoint borrowed columns or proven row partitions can be processed by ordinary `nb` Tasks while the owning world/collection remains live. Immutable `Arc<T>`-style shared buffers support zero-payload-copy clones; `rt share` never creates mutable cross-actor pointers. Hot reload and teardown must revoke row handles and loans before reclaiming owning arenas; generations are also scoped to the store identity.

## DOP extensions / ordered roll-out

**P0, CPU correctness:** language keyword and AST metadata; admission diagnostics; typed owning `InvertedArray<T>`; member access and method lowering through row view; field loans; atomic push/erase; handle invalidation; class/struct interoperability tests.

**P1, optimizer-facing:** explicit `Region<T>` layouts (`soa`, `aosoa<N>`, `aos`, `auto`; defaults may not silently violate an inverted-class contract); zero-copy projections, disjoint partition proof, `for ... simd`, field-aware reductions, map/filter fusion, and explicit AoS snapshots. Per-column alignment, heterogeneous widths, AoSoA padding and overflow require checked descriptors before native/Java ABI parity.

**P2, heterogeneous compute:** `compute fnc` effect restrictions, placement, GPU-resident columns, tiling, sparse/gather/scatter, ECS-like joins, AoSoA, device transfer fences, NUMA scheduling and benchmarks; no implicit CPU fallback for unsupported GPU execution.


Actor-independent shared ownership, ECS and structured-task composition are specified in [SHARED_STORAGE_ECS_TASKS_V1.md](SHARED_STORAGE_ECS_TASKS_V1.md). That contract distinguishes zero-copy immutable sharing from exclusive parallel writes and actor-boundary admission.

## Required acceptance gates

1. Parser/formatter and canonical grammar positive cases; reject contradictory modifiers, malformed fields, unsupported inheritance, and use of inverted values without an owning store.
2. Java + LLVM source execution equivalence with AoS for insert, read, update, delete/reinsert, method dispatch, and snapshot conversion; never claim parity from host-only tests.
3. Overflow, allocation injection, bounds, moved/deleted/foreign handles, generation rollover, partial row initialization, logical length equality.
4. Field-sensitive borrow matrix (read/read, read/write, write/write, different columns), row/column view escape, `await`, `nb` task capture, actor crossing, and teardown races.
5. CTest/JUnit under real backend builds with thread sanitizer and ASan/UBSan where supported, property and fuzz tests, SIMD correctness before performance claims.
6. CPU AoS/SoA/AoSoA benchmarks on at least column-only and full-row workloads; GPU claims require actual device execution and transfer-inclusive measurements.

Treat individual checkmarks by stage. Native host-model success never implies guest source support.
