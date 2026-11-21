# Manifest-backed namespace builds and actor method index — Java stage 1

> Draft, source committed but **NOT EXECUTED**. This describes the Java
> compiler entrypoint only; it is not guest execution or AOT-backend parity.

## Existing project manifest, not a new package dialect

This stage reuses the Java compiler's existing
`dev.oreslang.config.OresProjectConfig` and its `.atto.pkg.toml` project
manifest. Existing `.oreslangc.cfg.toml` projects remain readable by the
configuration loader; the manifest-backed package-proof entrypoint
requires an **explicit** (not ambient/discovered fallback) manifest, a
stable `project.name` and `project.version`, and a project-local source
root. It does not silently infer a package from the current working
directory, a namespace spelling, a sibling package, or `ORESLANG_PATH`.

```toml
schema_version = "1"

[project]
name = "shop-app"
version = "0.1.0"
root = "."

[source]
roots = ["src"]
```

Example source layout:

```text
shop-app/
  .atto.pkg.toml
  src/shop/contracts.atto
  src/shop/cart.atto
  src/shop/tick.atto
```

`src/shop/contracts.atto` contains
`namespace shop conforms ShopApi;`, and the other two files use
`namespace shop;` to contribute declarations. The trusted file
membership is the complete set of bounded `.atto` and `.ores` files
discovered recursively under `[source].roots`; imports or matching
directory names **outside these roots cannot satisfy the contract**.
The canonical `.atto` extension and supported legacy `.ores` remain
distinct files, not silently merged alternative bytecode identities.

## Java library API (opt-in, not wired into the launcher)

```java
var config = OresProjectConfig.load(
    Path.of(".atto.pkg.toml"), Map.of());
var packageBuild = new PackageNamespaceBuild(config);
var receipt = packageBuild.build();
var index = receipt.actorMethods();
```

The same `PackageNamespaceBuild` instance safely reuses the existing
incremental compiler for the **same pinned manifest and source roots**.
Each `build()` rescans the closed file set; deletions cannot silently
reuse cached namespace/actor witnesses. The manifest SHA-256 digest is
checked again before each incremental build; a changed manifest requires
reloading its configuration and beginning a fresh package builder.
Another package needs another builder (no global shared symbol registry).

The filesystem traversal rejects source-root escapes, symlinks (including
ancestor path components), irregular source entries, non-UTF-8 source, case
collisions and overlapping source roots. It limits individual source files
to 16 MiB, the source set to 4096 files, and aggregate source bytes to
256 MiB. Units are sorted by normalized project-relative IDs for
deterministic builds. External `ORESLANG_PATH` and manifest
`source.import_paths` do **not** become namespace ownership roots.
Until a separately versioned, trusted external-dependency manifest is
available, this build API **rejects unresolved non-platform source imports**
rather than letting the ambient resolver inject untracked witnesses.
Compiler-owned `ores.core/`, `ores.std/`, legacy `std/`, and capability-
gated `java:` imports retain their existing compiler-specific policies.
Ordinary relative and bare imports must resolve to a supplied source unit
within this package's discovered source set.

Important operational limit: while this code checks symlink components
and opens source files without following final-component links, an
adversarial concurrent filesystem writer may still race path-component
replacement in a mutable checkout. Public production exposure requires
a stable immutable source snapshot or directory-handle-anchored
(`SecureDirectoryStream` or equivalent) no-follow reads. A successful
builder call does not claim race-free adversarial filesystem isolation.

The historical `IncrementalCompiler.compile(Map)` stays usable for
in-memory/fixture source sets, including the earlier #2791 tests.
It is **not** evidence of manifest-derived package completeness.
No CLI switch or launcher entrypoint was changed in this stage; this
new Java API must be explicitly selected.

## Static actor method index

`ActorMethodIndex` binds nominal actor/method slots by
`(project name@version, explicit source namespace, actor type name,
method name, arity)`. Each entry is assigned to one declaring source ID
and carries parameter types/mutability, return type, visibility, async,
suspension, generic, structural, checked-effect and abstract metadata.
The actor declaration itself also retains declaring source unit, visibility,
generic status and actor kind. A `publicNominalWitness()` check explicitly
rejects private and generic actors; a method's separate
`declarationEligible()` check cannot substitute for actor eligibility.

A lookup is deliberately explicit and has **no fallback** across
namespaces or package identities. Methods inside named modules or source
units without an explicit namespace are not silently exposed as
namespace-root actor methods. Duplicate nominal actors and duplicate
method slots fail at collection. Runtime/control names (`receive`,
`tear_down`, `on_*`, `handle_state`, etc.) and static functions are
excluded. Method signatures marked `declarationEligible` are eligible
only for future **static** contract matching; this flag is **not** an
authority to access actor state or issue a mailbox call.

```java
var identity = new ActorMethodIndex.ActorKey(
    receipt.packageIdentity(), "shop", "Cart");
var key = new ActorMethodIndex.MethodKey(identity, "count", 0);
var method = receipt.actorMethods().method(key);
```

This stage indexes methods **declared inside actors** that may appear
in a different file from a namespace contract. It does not parse,
accept, validate or execute an external
`define pub mtd (Foo foo) abc(): void { ... }` body. The next compiler
stage must require exact imported actor provenance, reject duplicate
external and embedded slots, tie method bodies to a single actor
mailbox, and prove ownership/nonreentrancy/cancellation/generation
fencing in native guest execution before enabling such code.

## Validation / evidence

JUnit `PackageNamespaceBuildTest` includes real temporary manifests,
closed source-set discovery, incremental deletion, manifest replacement,
roots escaping the project, symlinked entries, duplicate actors,
namespace/package identity collisions and method slot metadata. It
does **not** constitute executed evidence until a runner actually
executes the Java tests.

Run the new suite plus `NamespaceContractMultiFileTest`,
`ActorContractTargetTest`, prior module/actor suites and positive/
negative canonical `.atto` fixtures at the exact PR SHA/tree.
Require nonzero executed steps and actual guest return/diagnostic
receipts, then independent APPROVED review. LLVM, .NET NativeAOT,
Wasm and Forge/Cranelift remain **NOT IMPLEMENTED / NOT VERIFIED**
for this new package-index feature. Do not transfer private Git trees
to another organization without the source repo's independently
approved exact-head carrier contract and publisher security controls.
