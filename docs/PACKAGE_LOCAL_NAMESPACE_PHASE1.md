# Java/Graal package-owned namespace imports — executable pinned-host slice

**Status:** The Java feature branch now contains a real Truffle/Graal guest
execution path for a **closed host-supplied package inventory**, plus parser,
type checker, runtime namespace facade, member provenance and source/ABI
dependency invalidation. The focused 10-source / 3-namespace test **asserts
main() exits 43**. It has **not yet executed on a verified runner**.

## Syntax

    import ns a from 'pkg:foo';

Each source in foo may contribute to namespace a by declaring namespace a;
in its file header, regardless of directory. Identity is always
(PackageInstanceId, NamespaceName), not a global string. No namespace merges
across different package instances; define module remains a different scope.

## Host-supplied entry point

    PinnedNamespaceProgramRunner.run(
        sources, sourceOwners, pinnedAliases, entrySource,
        IsolatePolicy.developer(), ExecutionProfile.serverJit());

The runner passes an exact closed source map through
IncrementalCompiler.compilePinned, PackageNamespaceLinker, TypeChecker and
OwnershipChecker, then compiles and links the full verified inventory in a Graal Context,
but initializes **only the entry and its transitive verified dependencies**.
The dependency-first SCC plan is validated for complete inventory coverage,
duplicate IDs, dangling edges and wrong ordering before any guest initializer
runs. Other packages and unimported namespaces remain validated but must not
execute startup hooks. The package graph adds imported namespace member files
to the entry dependency closure for initialization and ABI invalidations. The host registers each public function's EXACT source ID,
name and arity. Runtime dispatch validates that the selected function is a
public nonactor fnc in the original file-root; it does not use named module
lookup, copy or move function bodies, or resolve a same-spelling global name.

**IMPORTANT:** the host is REQUIRED to authenticate sourceOwners and
pinnedAliases against a pinned manifest+lockfile graph before invoking the API.
These maps are NOT itself a verified manifest; the new runner does not
discover, read or authenticate .ores.pkg.toml, sign the import inventory, nor
perform canonical-path/symlink/security checks. The source-free API is intended
for trusted compiler hosts or an isolated conformance gate, not as a public
filesystem package resolver.

Initial accepted exports: file-root public, synchronous, non-generic,
non-actor, nontrapped functions with explicit **int/bool scalar params and
int/bool scalar returns**, checked without implicit conversions. Exact verified
provider-to-provider calls across physical files in the same
(PackageInstanceId, NamespaceName) use closed name/arity slots in the type,
ownership and runtime passes. A missing, private, wrong-arity or cross-package
sibling is not resolved by filesystem or global-name fallback.
Private exports, named modules, aliases, classes, actors, type aliases,
pub[file]/pub[pkg], generic overload identity, effects, suspensions, classes,
deep ownership and re-exports require later checked interface lowering.
Ordinary file/Java host/bare imports in compilePinned are intentionally
rejected: no silent legacy filesystem fallback across package boundaries.
Existing filesystem LinkedProgramRunner and standalone evaluation remain
fail-closed for import ns.

## Explicit local package manifest admission

The package verifier now also reads actual schema-v2 .ores.pkg.toml files:

    PinnedNamespacePackageInventory.load(
        Map<String, PackagePin> pinnedPackages,
        Map<String, Map<String,String>> pinnedAliases);

Each externally pinned PackagePin contains the manifest path, the EXACT
SHA-256 digest of manifest bytes, and the EXACT digest for each enumerated
.ores file. The manifest must declare schema_version="2", project.name,
project.root=".", and [packages.NAME].files. The enumerated file paths and
pinned hash entries must be equal sets. This verifier rejects malformed
manifest UTF-8, mismatched hashes, unnormalized/escaping file paths, symlinked
source paths, aliasing/hardlinks between admitted files, and unpinned alias
targets. Source admission is bounded (64 packages, 1024 source units,
64 MiB aggregate, 1 MiB/manifest). Runtime consumes the verified source bytes,
not a subsequent filesystem reread.

Inventory.run(entryUnit, policy, profile) executes its loaded graph in Graal.
Tests provide two REAL temporary package manifests and sources and assert 43.

This API is deliberately **not** a lockfile/registry discovery mechanism:
a trusted host must supply the approved manifest/source checksums and exact
package-instance names. It does not fetch or sign packages, authenticate the
host's lockfile, or integrate zed-pkg's signed builds; a local attacker who
controls BOTH package bytes and the host-provided expected hashes is outside
this verifier's trust model. Ordinary CLI LinkedProgramRunner still rejects
pkg: namespace imports without a validated package resolver.

## Reachable-only startup verification (source-only, not a Graal guest run)

The standalone JDK-only fixture
`src/test/java/dev/oreslang/runtime/PinnedNamespaceInitializationPlanSelfTest.java`
runs with the exact production `PinnedNamespaceInitializationPlan.java`
source, requiring no Maven, Graal, filesystem package discovery, or tokens:

```sh
mkdir -p /tmp/ores-namespace-init-classes
javac -Xlint:all -Werror -d /tmp/ores-namespace-init-classes \
  src/main/java/dev/oreslang/runtime/PinnedNamespaceInitializationPlan.java \
  src/test/java/dev/oreslang/runtime/PinnedNamespaceInitializationPlanSelfTest.java
java -cp /tmp/ores-namespace-init-classes \
  dev.oreslang.runtime.PinnedNamespaceInitializationPlanSelfTest
```

Acceptance: `PINNED_NAMESPACE_INIT_SOURCE_ONLY_PASS checks=14` and a zero
exit status. These are real Java tests of the planner, **not GraalVM
compilation or guest execution**. The focused namespace GitHub workflow
executes this before Maven and separately requires the compiler-backed
`PinnedNamespaceSharedCorpusTest` exit-43/53 guest results.
The shared-corpus JUnit tests also assert that the 43 path initializes
only the six imported providers and entry (seven units), whereas the 53
path initializes all ten foo providers and entry (eleven units); the
unrelated `other@1` collision package must remain dormant.

## Required follow-up and acceptance

1. Connect the manifest verifier to an authenticated **zed-pkg lockfile**,
   and to the normal Java CLI import resolver. Schema-v2 source inventories,
   SHA-256 pins, path checks and in-memory compilation are now implemented as
   explicit host-injected APIs; automatic dependency discovery, cryptographic
   signature verification, lockfile trust, and CLI integration are NOT done.
2. Obtain **actual** JDK25 GraalVM execution for PinnedNamespaceRuntimeTest,
   including the target code returning 43. CI jobs with steps:null are
   NOT_EXECUTED and are not test passes.
3. Expand the shared fixture into the full visibility matrix
   (pub[pkg]/pub[file], cross-file private declarations, nominal classes),
   ABI stability, overloads, transitive package imports, import cycles.
4. Verify Java/Graal JIT and Native Image AOT and LLVM native execution;
   then .NET, Forge, Wasm and JS ESM parity.

Shared contract: oreslang-common-source PR #189.
Java PR: #2255. LLVM source-only graph PR: #1162.
