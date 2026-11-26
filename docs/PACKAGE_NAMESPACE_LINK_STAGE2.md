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
construction. The verifier reconstructs manifest membership from the frozen
root/dependency manifests and compares the current file bytes of **every**
manifest-owned provider against the exact source text admitted to the compiler.
This includes root package providers, not merely pinned dependency source
digests. Changes to provider bytes, deleted or replaced providers, or
same-byte source symlink swaps fail closed at the checked boundaries.

The entry source path is also checked component-by-component **before**
canonicalizing or lexically eliminating `.` / `..`: symlinked ancestors,
including a component hidden by a subsequent `..`, must not redefine package
ownership. Pinned dependency manifest locations use that same raw-component
check **before** normalizing their permitted relative paths, so a
`symlink/../` segment cannot silently select a different physical package.
The direct-file symlink denial remains distinct from an aliased
parent-directory denial. The component scan and subsequent reads are not an
atomic filesystem snapshot under adversarial concurrent mutation.

When the filesystem supplies a stable `BasicFileAttributes.fileKey()`, the
workspace records **both provider-file and manifest-file** identities at
discovery, including the root manifest and every pinned dependency manifest.
It rechecks these identities when validating the source snapshot. This
rejects regular-file replacement with **identical-byte** copies, not just
content tampering and symlink swaps. The root manifest check is also invoked
on workspace admission; dependency manifest identities are verified again
before comparing their pinned bytes and source inventories.

On filesystems without a file key the byte/policy checks still apply, but
same-byte physical identity protection is unavailable. These checks remain
boundary observations, **not an atomic read/compile filesystem snapshot**.
Tests cover both root and dependency same-byte regular-file replacements,
and retain positive unchanged-file checks. A standalone OpenJDK 21 filesystem
reproduction passed 9/9 focused cases; those are not JDK 25/GraalVM, JUnit,
or guest-program execution evidence.

A transient replace-and-revert race or hostile concurrent filesystem mutation
is **not** proven impossible by these periodic hash checks. True race-free
file identity throughout the read/compile lifecycle requires a separately
reviewed descriptor-owned/immutable source transport.

Regression coverage includes immutable root/dependency owner mappings after
on-disk edits, a negative root-manifest symlink swap, root-only alias
authorization, malformed UTF-8, and manifest-byte budget enforcement.
These are **committed but not executed** until exact-head CI can allocate a
runner and report real test steps.

## Raw path identity before canonicalization

The standalone `PackageNamespaceIndex.load()` entry point and
`loadFromSnapshots()` now inspect the caller-supplied **unnormalized**
manifest path component by component before accepting any canonical path.
This rejects intermediate symlinks that would disappear from a lexical
`alias/../.ores.pkg.toml` spelling, even where the normalized path would
have pointed to a valid manifest.

The public `LinkedProgramRunner.validate()` and `runWithExitStatus()`
paths likewise discover package ownership using the original submitted
entry path *before* source closure collection. Both compilation and
execution use the same discovered immutable workspace instead of
reopening discovery after losing the caller's original path spelling.
Regression tests exercise both entry points with a readable target behind
a symlink followed by `..`, plus direct/snapshot index negative cases.

Direct and snapshot index calls also enforce **exact admitted source-map
keys**: each entry must be an absolute, unaliased file path with canonical
textual spelling. The index rejects leaf and intermediate symlinks, embedded
`.`/`..` segments, relative keys and repeated separators *before* assigning
package ownership. A direct caller cannot launder a mutable source alias by
calling `toRealPath()` in the index. This is an API admission rule, not a
claim of atomic filesystem snapshotting.

These admission checks detect symlink substitution at the points inspected,
not a hostile replace-and-restore race during compilation. A focused local
OpenJDK 21 reproduction of the source-key helper passed 9/9 cases (two
canonical allows; seven negative path forms). The Java/GraalVM compiler,
project JUnit suite and native guest execution remain **NOT_EXECUTED** until
the private GitHub Actions runner-admission failure is resolved.

## Required exact-head qualification

Run `NamespaceRuntimeIntegrationTest`,
`PackageNamespaceIndexTest`, `NamespacePackageIdentityTest`,
`NamespaceModuleBoundaryTest`, and full Java/Graal fast CI. Confirm
positive same-package and cross-package execution; negative source/hash
tamper, malformed/nested/duplicate manifests, unauthorized aliases,
unexported members, type mismatch, arity mismatch, and stale ABI updates.
The CI job must have actual executed steps and correspond to the exact
head tree SHA. Do not merge a zero-step runner failure.
