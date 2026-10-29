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
return types. Classes, actors, type-only metadata, `pub[pkg]`, first-class
member extraction, complex cross-file nominal provenance and arbitrary
module/field access are **not yet qualified**. Do not misrepresent this
subset as complete namespace support or LLVM/.NET/Wasm parity. The runtime
and type-checker fail closed for unsupported calls.

## Required exact-head qualification

Run `NamespaceRuntimeIntegrationTest`,
`PackageNamespaceIndexTest`, `NamespacePackageIdentityTest`,
`NamespaceModuleBoundaryTest`, and full Java/Graal fast CI. Confirm
positive same-package and cross-package execution; negative source/hash
tamper, malformed/nested/duplicate manifests, unauthorized aliases,
unexported members, type mismatch, arity mismatch, and stale ABI updates.
The CI job must have actual executed steps and correspond to the exact
head tree SHA. Do not merge a zero-step runner failure.
