# Incremental compilation design v1 — Java/GraalVM JVM and Native Image

**Status: PARTIAL JAVA FRONTEND IMPLEMENTATION / TESTS AUTHORED, NOT EXECUTED.** This PR now modifies the real Java compiler and adds focused JUnit tests, but no exact-head admitted runner, compiled-guest output, namespace package lowering or Native Image proof has been verified.

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

## Implemented Java file-fallback slice (draft PR #1744)

Java main already represents every source file as an individual compilation unit; source-set calls have **singleton file package IDs** and do not currently prove manifest-owned multi-file namespace aggregation. This PR now caches expanded parsed ASTs on a per-source SHA-256 basis with separate strict-Atto/legacy profiles. A no-op build does not reparse unchanged files; body-only changes reparse and re-typecheck the owner but leave unrelated no-header sibling files' typed units intact. `BuildResult.reparsedUnits()` reports parse work independently of `rebuiltUnits()`. Import target swaps with identical target sets invalidate importer checking and transitive dependents, while unresolved external imports conservatively recheck. Caches are published only after successful graph, type and ownership checking. A complete empty source snapshot evicts the selected profile cache.

The implementation **does not** yet aggregate several namespace files into one trusted package instance, produce machine-code/object artifacts, or enforce cross-process toolchain/IR profile keys. Its `packageId` remains `file:<normalized-source-id>` in the map-based API to avoid silently fabricating package/namespace authority.

### Exact-head evidence gate

Focused Java JUnit: `mvn -B -ntp -Dtest=IncrementalFileFallbackV1Test clean test`. The pinned `.github/workflows/incremental-file-fallback-v1.yml` requires a nonempty Surefire XML report with all ten named cases, no failures/skips, and records exact source tree. Source Actions have so far been runner-admission failures with **zero steps**. Thus actual Java compilation and JUnit/guest execution are **NOT_EXECUTED**, not passing.

### Reconcile overlapping PRs before merge

[Java #697](https://github.com/atto-lang/atto-source.java/pull/697) contains an earlier parsed-AST cache and import-binding design. PR #1744 ported the core optimization onto current main and extends it to strict profile separation; the two branches touch `IncrementalCompiler.java` and **must be compared and consolidated**, not blindly merged together. Preserve #697's extra ABI fingerprint audit/tests when reconciled. [#1536](https://github.com/atto-lang/atto-source.java/pull/1536) and [#1545](https://github.com/atto-lang/atto-source.java/pull/1545) own namespace proof and manifest package source-set authority respectively. Integrate those designs in follow-on compile package work without creating an ambient namespace authority.

### Public ABI hardening and binding identity (follow-up)

The type-neutral exported interface SHA is now `ores-abi-v4` in the process-local compiler. It includes public callable `suspends` / `nonLexical` / `trapped` state and declared checked effect types, parameter names (for named-argument dispatch), method receiver/critical/effects, public class actor transport/concurrency and abstract metadata, mutable referent flags and annotations, and module interface authority. Complete exported declaration entries are sorted so simple declaration reordering no longer dirties importers.

Resolved import bindings include the **full source-derived imported declaration identity** plus pinned source target. Changing `import fnc {left}` to `import fnc {right}` from the **same provider** must invalidate the importer and conservative downstream closure, even though both target set and provider ABI are unchanged. Import target swaps between two providers are also detected. `BuildResult.reparsedUnits()` and `rebuiltUnits()` separately expose parse work and semantic rechecking.

**Evidence:** Ten JUnit cases are written and required by `.github/workflows/incremental-file-fallback-v1.yml`, but **no executed JDK25 compiler/JUnit run has been obtained**. The shared canonical Python model has been locally re-executed with 20/20 tests passing; that does **not** prove Java compilation.

### Export authorization blocker — source remains private

The live allowlist `.github/private-ci-approved-repositories.txt` has **no approved carrier**, source `main` reports `protected=false`, and security [issue #65](https://github.com/atto-lang/atto-source.java/issues/65) explicitly holds external private-source mirroring pending independent `@ORESoftware` security resolution. The V3 policy requires a pinned carrier contract, exact source head/tree, approved repository ID, reviewed plan and effective protected-source authority before any source-bearing write.

An `opto-sync-test/go-java-validation` source-free runner canary on November 13 executed six steps, but its newer November 21 source-free capacity probe had zero executed steps. Neither is current proof of an approved/available private source carrier. **No private-source mirror was made in this work.**

### Remaining admission checks

1. Obtain independent V3 security review, live source protection and exact carrier allowlist/plan approval; then run a fresh **source-free stepful** capacity probe before any transfer.
2. On a permitted private carrier, transport only an exact independently approved PR tree; verify all root paths, modes, blob SHA and root tree SHA, scrub credentials and execute the approved JDK25 + offline Maven focused test plan.
3. Reconcile public ABI coverage against Java [#697](https://github.com/atto-lang/atto-source.java/pull/697) and maintain full manifest/namespace admission from [#1536](https://github.com/atto-lang/atto-source.java/pull/1536) and [#1545](https://github.com/atto-lang/atto-source.java/pull/1545), then compare actual compiled guests against clean builds.
