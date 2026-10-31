# Package namespace linker — Java/Graal runtime slice (draft)

**Status: source changes and regression tests committed; no executed CI proof yet.**

This draft builds on the package identity and static indexing PRs (#2254 and
#2258). It does not change the canonical shared contract in
`oreslang-common-source#188`.

## New schema-v2 package imports

`import namespace billing from "+/";` binds the `billing` namespace
from the importer's own explicitly manifest-owned package.

`import namespace billing as payments from "checkout";` binds
the namespace from one exact pinned dependency. The consumer's
`.ores.pkg.toml` declares that dependency:

```toml
schema_version = "2"
[project]
root = "."
[packages.client]
files = ["src/main.ores"]

[namespace_dependencies.checkout]
manifest = "../checkout/.ores.pkg.toml"
package = "checkout"
manifest_sha256 = "<lowercase SHA-256 of exact dependency manifest bytes>"
sources_sha256 = "<lowercase SHA-256 of sorted manifest-relative file path, newline, file SHA-256, newline entries>"
```

For this initial runtime slice, each pinned **dependency manifest must
declare exactly one package**. A manifest with multiple packages is rejected,
rather than silently admitting unrelated sibling packages and running their
initialization. Package-filtered multi-package dependency snapshots are a
separate follow-on feature.

Only root-manifest-owned sources can use the root dependency aliases;
namespace names do not authorize package access. Unlisted files cannot join
a namespace merely because their headers match. No ambient package lookup,
runtime directory scanning, dynamic module loading, or cross-org checkout is
performed.

## Execution flow

1. The host resolves the nearest explicit `.ores.pkg.toml`, verifies
   dependency manifest and source content hashes, and admits bounded sources.
2. The compiler builds `PackageNamespaceIndex` from exact manifest members,
   retaining independent `PackageInstanceId` and source namespace identity.
3. Import validation and incremental compilation record dependency edges for
   **every** contributing namespace provider.
4. For the supported direct-call subset, the type checker checks the actual
   public provider `fnc` parameter/return signature, with statically
   disallowed unknown members, overload/arity and type mismatch.
5. The host registers one immutable route per namespace symbol under the
   importer and local alias *before* source units link and initialize.
6. The Graal evaluator resolves each direct member call to its exact
   provider's linked evaluator, rather than treating a namespace as a
   one-file wildcard import.

The first execution slice supports direct calls to **public, concrete,
synchronous, non-actor, non-generic `fnc` declarations** with explicit
scalar-only parameter and return types (`int`, `uint`, `float`, `bool`,
`String`/`string`, or `void`). Calls must resolve to file-root exports;
a matching declaration in a named module is not an alternative provider.
Unsupported callable exports (e.g. routines, async functions, actors) do not
invalidate otherwise valid namespace imports; their direct invocation from
this namespace form is statically rejected. Runtime dispatch independently
checks the restricted callable signature and forbids first-class member
extraction. Classes, actors, type-only metadata, `pub[pkg]`, first-class
member extraction, complex cross-file nominal provenance and arbitrary
module/field access are **not yet qualified**. Do not misrepresent this
subset as complete namespace support or LLVM/.NET/Wasm parity. The runtime
and type-checker fail closed for unsupported calls.

## Immutable manifest-to-linker authority

`PackageNamespaceWorkspace.discover()` now captures strict-UTF-8 root and
pinned-dependency manifest text from bounded, no-follow file reads. The
workspace seals those strings in an immutable map, preserving the original
root-manifest SHA-256 and exact dependency manifest/source pins.

`LinkedProgramRunner` passes the captured map to
`PackageNamespaceIndex.loadFromSnapshots()`. The index derives package
instance ownership, namespace members and symbol routes exclusively from
those captured manifest texts rather than reopening a manifest to decide
ownership. The old `load(List<Path>, ...)` convenience API remains for
standalone compiler tests but does not carry the runtime's source-admission
provenance.

Filesystem and source-content revalidation runs before and after index
construction. An edit, package reassignment or same-byte symlink replacement
must fail closed at those guards; a transient replace-and-revert race or
hostile concurrent filesystem mutation is **not** proven impossible by hash
checks alone. True race-free file identity across the entire read/compile
lifecycle remains a separate descriptor-ownership hardening goal.

Regression coverage includes immutable root/dependency owner mappings after
on-disk edits, a negative root-manifest symlink swap, root-only alias
authorization, malformed UTF-8, and manifest-byte budget enforcement.
These are **committed but not executed** until exact-head CI can allocate a
runner and report real test steps.

## Required exact-head qualification

Run `NamespaceRuntimeIntegrationTest`,
`PackageNamespaceIndexTest`, `NamespacePackageIdentityTest`,
`NamespaceModuleBoundaryTest`, and full Java/Graal fast CI. Confirm
positive same-package and cross-package execution; negative source/hash
tamper, malformed/nested/duplicate manifests, unauthorized aliases,
unexported members, type mismatch, arity mismatch, and stale ABI updates.
The CI job must have actual executed steps and correspond to the exact
head tree SHA. Do not merge a zero-step runner failure.
