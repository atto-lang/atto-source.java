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
