# Java .atto linked source identities — current-main integration slice

**Status: DRAFT / AUTHORED / NOT_EXECUTED** (2026-11-04).

Parent is [Java #2513](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2513), stacked on [#2504](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2504). This does not modify those parent branches. It selectively ports known same-repository host identity and opened-handle source-reading code from the earlier macro M1 [#2485](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2485) branch.

## Implemented (source only)

- A per-build `HostSourceUnitIds.Admission` maps authorized physical source files to bounded `project/<relative>` or `external/<root ordinal>/<relative>` IDs. Reserved embedded `ores.std` / `ores.core` identities remain compiler-owned.
- Both `LinkedProgramRunner.validate` and `runWithExitStatus` create private admission registries and use the same canonical IDs for source maps, `MixedSourceUnit`, import edges, guest parsing and entry selection. Remove the previous absolute-host-path `unitId(Path)` helper. Current-main `MixedSourceUnit` accepts `.atto`. The import resolver requires `.atto` when a canonical `.atto` importer uses an extensionless path (no implicit `.ores`/`.java` fallback), while legacy `.ores` importers reject ambiguous `.atto`/`.ores` twins and permit explicit legacy imports.
- Before linking, deny out-of-root paths; reject known symlink/hardlink aliases and duplicate file-key owners; verify source identity and file snapshot after the bounded read. Root authorization and symlinked descendant denial now precede every source-extension existence probe; physical extensionless files cannot bypass admitted suffixes.
- Source reads go through `SecureSourceReader` with `SecureDirectoryStream` pinning where the provider supports it, and strict UTF-8 / 16 MiB limits via `BoundedSourceReader`. Adversarial isolate validation fails closed on providers without secure directory handles.
- 13 linked-loader JUnit cases, seven source-read hardening cases, and ten `.atto` file-resolution cases (**30 authored tests**) cover ordinary two-file `.atto` guest return 42, strict canonical imports, legacy twin ambiguity, extensionless physical files, out-of-root existing/missing paths, pre-probe symlink denial, normalized imports, invalid physical aliases, traversal, size/UTF-8/zero-byte boundaries and source replacement.

## Explicit limits and unresolved acceptance

1. **NOT_EXECUTED:** No exact-head JDK 25 Maven/JUnit, linked Polyglot/Truffle result, GraalVM Native Image, sanitizer or hosted GitHub Actions job was executed by this work. One isolated JDK 21 Java resolver exercise passed ten assertions, **not** the repo's test suite. The source-owned fast-CI rerun was accepted but failed with zero executed steps. Git blob read-back and isolated logic checks are not passing compiler tests.
2. The imported **public macro** two-file `lib.literal!(const 40 + 2)` case requires a separately reviewed current-main macro artifact/linker integration. The authored ordinary two-file guest-42 case is NOT proof of imported macro parity. Do not use it to close Java issue #2492.
3. The second opened file handle and snapshot checks catch ordinary mutations; they are not an adversarial concurrent-writer proof against coordinated ABA / same-byte edits. True adversarial builds need protected immutable source snapshot or OS-specific file-descriptor provenance.
4. `OresProjectConfig` manifest/import policy and `MacroSourceUnitIdentity` normalization must be independently reviewed for Windows, casefolding, Unicode, external root aliasing and legacy `.ores` resolution.
5. Must run exact-head source-owned or explicitly approved source-bearing CI with nonempty executed job steps, JDK 25, compiler-owned negative diagnostics, actual guest exit 42 and independent security/compiler review. Source-free alternate-org canaries cannot authorize private source transfers.

## Suggested commands after source runner admission

```bash
mvn -q -Dtest=HostSourceUnitIdsTest,SecureSourceReaderTest,AttoFileImportResolutionTest test
mvn -q test
```

Pin source/head/tree and test logs in the PR before promotion. Source code remains in `ores-truffle-oreslang/oreslang-source.java`; no private blobs were moved between organizations.

References: [Java issue #2492](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/2492), [runner issue #315](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/315).
