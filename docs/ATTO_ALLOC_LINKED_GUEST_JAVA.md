# Atto alloc compiler-owned linkage (Java backend milestone 2)

Atto source remains independently owned by the backend at
`stdlib/alloc/{allocator,set,drop,pool,arc,weak}.atto`. Maven copies those
files to `dev/oreslang/std/alloc/` using its existing source-resource rule.

**Imports:** `import fnc self_test from "atto.alloc/set";` (and other module
names), or explicit `.atto` suffix. This new `atto.alloc/` root has
a closed six-file registry, stable `@atto.alloc/<file>.atto` unit identity,
bounded source decoding, classpath-duplicate rejection, and no project
filesystem, package-path or symlink fallback. An alloc module may import
another registered alloc module or `ores.core` protocols only; project,
host Java, and std imports are rejected.

**Source versus runtime authority:** `allocator` computes allocation
admission/layout but does not mint physical allocation receipts.
`drop` tracks advisory LIFO destructor obligations; only compiler-owned
drop glue can release actual payload memory. `arc` remains a SERIAL
reference-counting ledger, not an atomic `Arc<T>`.
`set` implements two Atto-backed collections: `BoundedIntSet`
(dense domain [0..N)) and `IntHashSet` (open-addressed signed int keys,
collision-safe search, tombstone reuse); neither is a generic `HashSet<T>`; accepting user-defined `Hash` is insufficient without
compiler-checked `Eq` coherence and key immutability.

**Tests:** `AttoAllocGuestTest` contains real Java compiler/linker guest
entrypoints for all six `.atto` modules, registry/path shadow negatives
and undeclared-export rejection. The exact-head GitHub Actions workflow
requires an executed Surefire report and method names; it must not
interpret an unstarted CI run as success.

**Current evidence:** authored code and tests; compiled guest tests
and linked runtime outcomes **NOT_EXECUTED** until the workflow reports
actual material job-step completion and the exact PR-head SHA.
No AOT, multi-threaded Arc atomicity, native allocator or other-backend
parity is claimed. Keep the PR Draft pending these gates.

## Atomic host prototype and fail-closed type boundary

A same-branch host prototype `AtomicSharedCellV1` now models an opaque,
package-private strong/weak control block with CAS increments, domain and
generation checks, and last-strong destructor invocation. It is **not**
guest-accessible `Arc<T>`: freezing, compiler-inserted drop glue, isolate
identity, actor delivery, and authentically typed allocation receipts are
still missing. Its host acquisitions preallocate unpublished handles before
reference increments so OOME cannot strand a count, and its stress probe
surfaces worker failures and requires a successful pre-release upgrade.

Negative guest fixtures explicitly reject fabricated `Arc<T>`,
`HashSet<T>` and physical `Allocator` imports until those APIs have
compiler-verified semantics. Keep these negatives in the linked guest gate;
do not turn them into placeholder constructors.

## Dependency layering (Java compiler linker)

- `ores.core/*`: `ores.core/*` dependencies only.
- `atto.alloc/*`: `atto.alloc/*` and `ores.core/*` only.
- `ores.std/*` and legacy `std/*`: `ores.std/*`,
  `atto.alloc/*`, and `ores.core/*`; no project, Java, or ambient imports.

Resolution uses the same closed import registry and budget for every edge,
including transitive `std -> alloc -> core` dependencies.

## 2026-11-22: Atto file namespaces and allocation transactions

The six source files now use the canonical **file-scope Atto header**
`use namespace atto_alloc_<name>;` instead of wrapping all declarations in
`define module <name> as ... end`. Atto's parser only admits flat, static
namespaces. Different namespace names avoid same-named `self_test()`
collisions across compiler-owned source files. The public import remains
`atto.alloc/allocator` (and other closed registry paths); namespace spelling
does not create or authorize an ambient source lookup.

`allocator.ReservationLedger` is implemented in Atto source and provides:

- Strictly bounded outstanding allocation reservations, safe subtraction-based
  quota checks and no fake success for zero-size or full capacity
- Two-phase `reserve -> record_commit` after trusted physical allocation;
  `record_cancel` after provider failure, with generation invalidation
- Two-phase `begin_retirement -> finish_retirement`, blocking duplicate,
  stale, out-of-order and unfinished retirement transitions
- Slot retirement on generation exhaustion and `seal/is_drained` accounting
- An Atto `reservation_self_test()`, invoked by `allocator.self_test()`

**Security boundary:** `Reservation` and its `slot/generation/bytes`
fields are policy metadata, not secure capabilities. Callers can forge
numeric values. Only an actual allocator provider with opaque compiler-issued
receipts may authorize physical allocation or release; the trusted adapter
must enforce real borrow/continuation quiescence, exactly-once destructor
execution, and failure-atomic reclamation. Neither a successful ledger
transition nor source namespace syntax establishes those facts.

**Verification:** exact Git source readback, namespace assertions and guest
fixture tests are authored; JVM guest execution and AOT have not yet been
confirmed on a running exact-head CI job. Keep Draft.
