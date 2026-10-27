# Inverted classes and columnar DOP — stage-one contract (2026-10-18)

Status: **narrow Java/Graal guest evaluator admission has been staged, with exact-site typed ownership proofs**. Flat validated inverted classes and admitted direct `rows[i].field` read/write are implemented in the Java reference path. General row methods, typed handle subscripts, compound guest updates and full LLVM/.NET/Wasm/Forge parity remain unverified or gated. Exact-head Java tests have not executed in the current source-org CI; do not report a completed compiler feature.

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
type before code generation. Only the narrowly verified direct-field
Java evaluator path is admitted; unsupported forms remain **fail-closed**.

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

Java host reference includes `InvertedColumnStore.handleAt(long)`,
`InvertedArray.index(long)`, and fused `ColumnAccess.getAt/setAt` targets.
The Java type/ownership/evaluator path has a staged exact-site admission, but
this is **not** evidence of executed CI or Java/LLVM guest parity.
Promotion requires executable source positive and compile-negative
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
allocation. The admitted Java direct-field evaluator now uses these fused
positions; general methods, stable handle syntax and compound guest lowering
require further proof.

Stable `rows[h].x` still uses checked owner/slot/generation handles.
Compound operations (`+=`, `++`, etc.) must use a verified atomic
read-modify-write transform; **do not lower them to separate getAt/setAt**.
Method calls must lower `self` into a checked store/row context, not a
heap row. A nested ordinary fixed record remains packed as one opaque
column element. Generating an AoS mirror, implicit whole-table transpose,
reflective property access, runtime macros, or an untyped fallback is
not permitted.

### AST identity and proof lifecycle (fail closed)

The first-stage `InvertedIndexedFieldPlan` is **syntactic candidate binding
only**. It records the exact read or assignment source node and its exact
receiver/index/RHS AST subnodes, plus the immutable nominal-schema identity
and field binding. The `requireExactSource(currentNode, currentSchema)`
guard fails if the node or schema changes, even when an AST reconstruction is
equal by value. It must be called after each AST transformation if that
candidate is reused. A completely new candidate must be constructed for the
new node after every rewrite.

**Important:** matching source identity is necessary, but is **not** a type,
borrow, escape, effect, or backend-emission proof. It does not admit guest
source indexing or permit arbitrary callbacks. The future typed
`InvertedRowRef<T>` / `IndexPlan` proof must be admitted by the compiler
type/ownership pass, revoked at the **start** of every recheck, and only
republished once receiver type, index kind, nominal field, liveness and
ownership checks all succeed. An exception, stale linked metadata, changed
generic specialization, rejected annotation expansion, or unrelated AST
rewrite must never leave a previously admitted plan executable.

The required *negative* matrix includes: structurally equal rebuilt read
and write sites; altered receiver, index, RHS and field; same field spelling
but unrelated nominal schema; failed recheck; borrowed row-view escape;
ordinary `Array<T>`, dynamic `Any`, `Symbol.index` and user-macro fallback;
unsupported nested, multi-index and slice syntax. All must reject rather
than silently use text-based generated accessors.

### Conservative index-expression staging limits

The independent `InvertedIndexPlan` syntax candidate accepts direct named
receivers and candidate integer-index subexpressions only. At this stage,
integer `i32/i64` literals and names are allowed, together with a restricted
`+/-` unary and integer-arithmetic binary operator subset. Boolean, string,
floating-point, comparison/logical, call, await, dynamic, or member-derived
index expressions are rejected rather than assuming they are integral or
non-suspending. Names and arithmetic may still be overloaded or ill-typed:
**this filter is not a type/effect/ownership admission proof**.

Traversal must be iterative, bounded at **1,024 nested levels** and
**4,096 logical visited AST nodes**, including repeated visits of a shared
subgraph. Oversized, malformed or synthesised index sites fail with controlled
diagnostics rather than risking compiler stack exhaustion or unbounded staging
work. JUnit fixtures cover both deep chains and repeatedly shared binary
subtrees; they remain unverified until a real Java test runner executes.

### Exact nominal identity for compiled indexed-field authority

A column-layout fingerprint (class spelling, ordered field names, physical
scalar kinds, fixed record widths) is a representation compatibility check,
**not** nominal type identity. It cannot grant an already-checked write to a
second declaration that happens to have the same name/layout but different
mutability, module origin, or visibility. The Java runtime `Schema` retains
the exact immutable checked `Ast.ClassDecl` as a private nominal token.
`InvertedIndexPlan.bind` and the guest indexed-field evaluator must require
this exact declaration identity **as well as** physical shape compatibility
before touching columns. Equivalent source text from a separately parsed AST
is deliberately not an authority token. Compiler rewrites must re-type-check
and issue new plans scoped to the replacement AST.

The negative matrix includes two independent declarations with identical
spelling and physical layouts, and a lookalike whose fields are `val` rather
than mutable `let`. Host `ColumnAccess` remains a lower-level storage
primitive; only a checked source plan can authorize guest operations. These
cases are committed as JUnit regressions but are **not tested** until
exact-source JVM jobs execute real steps.

### Recheck revocation across type and ownership entry points

An exact-Program inverted-index execution epoch is revoked **at the start**
of `TypeChecker.checkTypesWithDiagnostics` and the linked-metadata type
entry point, including before argument validation, annotation expansion,
import resolution or any operation that can throw. A failure leaves the
original immutable Program unable to execute its old bracket-field evidence.
The revocation is scoped to Program object identity: independently admitted
ASTs continue to function even when their source text is identical.

A type failure also fences direct `OwnershipChecker.check(oldProgram)`
from laundering its previous type side table into a new executable epoch.
The recheck barrier is **not publicly clearable**; the supported repair path
is a successful complete TypeChecker/OwnershipChecker analysis of a freshly
expanded Program with newly admitted exact-site evidence. The annotation
expander returns a distinct Program identity rather than reusing the old one.
Fail-closed negative fixtures must cover failing linked metadata, stale
execution epochs, independent valid ASTs and the fresh reanalysis path.
These are committed regression fixtures, **not a JUnit pass** until an
actual source runner has executed them.

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
Only the limited exact-site Java guest read/write path is admitted.
This generic callback hook is not guest codegen authority, and broader guest
lowering remains disabled until effects and ownership are proven.

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

### Exception-safe push and issued-handle publication

Insertion must **issue its opaque store/slot/generation capability before**
consuming a free slot, initializing a newly claimed generation, storing row
field data, updating dense row-to-slot maps, marking the row live, or
incrementing logical length. A failed handle allocation (including OOME)
must not leave a live but unreachable row. Reserve may have enlarged idle
capacity, but is not allowed to publish an unreported live row. Admission
must validate all column types and fixed-record widths before this commit
sequence. Reused slot generations must not be reset after a failed
admission, and a stale handle must never regain authority.

The Java host reference enforces this *publication ordering* on
`InvertedColumnStore.push` and has regression fixtures for negative
width/type/reuse and **one-shot injected OOME** at handle issuance and
reserve-buffer publication. The test seam is package-private and one-shot,
with no arbitrary guest callbacks; it clears the failpoint before throwing.
**The checked-in JUnit injection tests have not executed on this head.**
Source inspection and source-order assertions are weaker evidence than an
executed Java failure-injection suite.

### Allocation-free, all-column teardown

`InvertedColumnStore.close()` must revoke access and scrub **all** original
physical column bytes even when the process is under allocation pressure.
The previous per-column ordering (`fill(old)`, allocate `new byte[0]`,
allocate `ByteBuffer.wrap`, advance) could throw `OutOfMemoryError`
halfway through and leave later columns unsanitized. The hardened path
publishes `closed = true`, fills **every** existing column with zeros,
then drops column/buffer references using one preallocated zero-length
sentinel and `null` buffer entries. It performs **no heap allocations
during teardown** and remains idempotent. Previously issued row handles,
row views, and active column loans remain revoked.

A reflection-based regression retains references to all former backing
arrays and ByteBuffers, verifies their bytes are scrubbed after close,
checks that every released column references an empty sentinel with no
replacement buffers, and exercises stale handle/loan rejection plus
second-close idempotence. This is checked-in Java/JUnit regression
coverage, **not a passing JUnit receipt** until the source CI runner
actually executes it.

### Exception-safe column loan issuance across store and facade

Borrow acquisition also has an allocation-publication boundary. A low-level
`InvertedColumnStore.borrowRead/borrowWrite` must **allocate its
`ColumnLoan` first**, and only then increment the reader counter or publish
the exclusive-writer flag. If loan allocation throws, no unreleaseable borrow
may remain. Package-private, one-shot `BEFORE_COLUMN_LOAN_ISSUE` injection
supports regression of both read and write allocation failures.

At the facade boundary, `InvertedArray.borrowRead/borrowWrite` first acquires
a valid store loan and then allocates a `FieldLoan` wrapper. That *second*
allocation can also fail. A `finally` guard must close the acquired store
loan unless the wrapper was successfully published. A separate package-private
one-shot `FieldLoan` injection demonstrates that the failed wrapper does not
leave a reader/writer hold or disturb unrelated column loans. This applies
even to `OutOfMemoryError`, not merely ordinary runtime exceptions.

These tests are committed against the source and the publication ordering
can be statically inspected, but **no actual JDK 25/JUnit/GraalVM execution
receipt exists** while source-hosted Actions jobs fail with zero steps.

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
