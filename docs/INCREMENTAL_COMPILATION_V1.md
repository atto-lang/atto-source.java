# Incremental compilation design v1 — Java/GraalVM JVM and Native Image

**Status: DRAFT DESIGN ONLY.** This commit does not implement new incremental code paths and is not compiler or guest-execution evidence.

Shared contract: [atto-common-source #133](https://github.com/atto-lang/atto-common-source/pull/133); rollout tracker: [atto-compilers-parity-testing #53](https://github.com/atto-lang/atto-compilers-parity-testing/issues/53).

## Required cross-backend semantics

- Within a resolved, immutable package instance, source files with an explicit `namespace N;` may contribute to a **single package-qualified namespace** spanning multiple files/folders. Namespace identity is `(PackageInstanceId, N)`.
- A source file **without any namespace header** is an **independent compilation/cache unit** keyed by `(PackageInstanceId, SourceUnitId)`. It never joins an implicit global/default namespace; modifying it cannot cause unrelated unnamespaced sibling files to recompile.
- On a private/body-only edit, recheck/regenerate the owning unit or a finer proven function unit; unaffected files/namespaces retain verified typed outputs. On public ABI/effects/ownership/actor-contract change, invalidate bound importers and negative lookups; inline, macro, specialization, constants and whole-program optimizations create additional edges.
- Package manifests/locks define membership and version provenance, **not** the minimum rebuild scope. Deleted/moved files, renamed namespace headers and package-version switches must purge stale graph entries. Cyclic dependencies use deterministic interface-first SCC processing.
- Cached work is never proof by assertion: rederive complete source membership, dependency resolution and source/ABI hashes from a trusted snapshot before reuse. Fail closed for unresolved imports, path escapes, unsigned/foreign/stale artifacts, negative lookup changes, partial outputs and missing ownership/borrow proofs.
- Target-specific code generation and final artifact relinking are separate steps. The build explanation must distinguish `RECHECK`, `CODEGEN`, `RELINK`, `REUSED`, `UNSUPPORTED`, and `NOT_EXECUTED` rather than hide a full rebuild.

## Repository entry points

`src/main/java/dev/oreslang/compiler/IncrementalCompiler.java`, `ImportGraph.java`, exported-interface/linker utilities, and manifest-backed package build work.

## Baseline and overlapping work

`IncrementalCompiler` on main already caches file units by source and exported ABI digests but parses the full supplied source map before link and does not prove namespace-granular cache reuse. Reconcile existing Java PRs #697 (parsed AST reuse), #1536 (multi-file namespace contracts), #1545 (manifest-backed package source set) and #1589 (declared actor witness gate).

## Target implementation

Build a pinned package snapshot from the manifest; enumerate source membership, explicit namespaces and independent file units. Reuse parsed ASTs only after raw/expanded source hash and source-map ownership verification. Compute a versioned namespace exported surface including nominal types, effects, owned/borrowed signatures, actor contracts, generics, constants, negative lookups and resolved import targets. Recheck only changed units + ABI-dependent closures, regenerate only changed codegen units and embedded/inlined dependents. Preserve strict-AOT vs legacy cache separation and mandatory package/actor admission before reuse.

## Backend-specific cache boundaries

JDK 25, GraalVM version, JVM/Native Image modes, target OS/architecture, compiler/IR schema and permissions/feature profile must be part of target-specific artifact keys. Native Image may still relink a full binary even when internal source units are reused.

## First executable acceptance slice

Add Java JUnit tests in the incremental and namespace suites, then compile and execute positive and negative `.atto` guests on JVM and real GraalVM Native Image, with dirty/rechecked/generated counts and artifact hashes.

## Safety and overlap

Open upstream work #697, #1536, #1545, #1589 is not superseded. Implement by extending their canonical interfaces; do not introduce a second Java project scanner or weaken declared-actor gates.

## Required verification before merging or marking supported

1. Add real source-level implementation and tests; machine-readable contract status remains `DESIGN_ONLY` until merged, exact-current-head, stepful compiler/guest evidence exists.
2. Run canonical **10 files / 3 namespaces + 2 unnamespaced files**: no-op; body-only edit; ABI/effect/ownership change; inlining; namespace move; deletion; duplicate/negative import; package pin change; target-profile change; corrupted cache; and cold-clean differential.
3. Prove changing just one unnamespaced file leaves another unnamespaced file's parse/typing/codegen counters and output hash untouched (aside from justified link work). Prove package-bound explicit namespace membership and unchanged-ABI callers are reused.
4. Compare incremental and clean diagnostics, emitted public interfaces, artifact reproducibility when promised, and **real compiled guest behavior**. Record exact Git SHA/tree, target/toolchain, nonzero CI steps, compiler output and exit statuses.
5. Never export private source to an unapproved CI carrier. If source Actions admission fails with zero steps, report `NOT_EXECUTED` and use only separately approved, exact-tree, per-blob-verified private CI mirroring. Independent review and branch protections remain mandatory.

Design/checklist files here are intentionally **not** a substitute for the above backend changes.
