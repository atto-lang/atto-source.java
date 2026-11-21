# AttoLang project manifest

AttoLang projects define compiler/project configuration in the nearest
`.atto.pkg.toml`. The legacy `.oreslangc.cfg.toml` spelling remains supported
for existing Oreslang projects. The compiler searches upward from the entry
source file (or current working directory if no file is supplied) and uses
the first manifest found. If both names exist **in the same directory**, it
rejects ambiguity rather than silently selecting a project root. Symbolic-link
manifests and malformed closer manifests fail closed instead of falling back
to a more distant root.

This is intentionally a project/compiler manifest: lighter than Maven's build
lifecycle, but more project-aware than a source-only compiler switch file.

## Supported v1 contract

```toml
schema_version = "1"

[project]
name = "my-atto-app"
version = "0.1.0"
root = "."

[source]
roots = ["src"]
import_paths = ["lib", "vendor"]

[entrypoints]
main = "src/main.atto"
```

All manifest filesystem paths are resolved relative to `[project].root`,
which itself is relative to the manifest directory unless absolute.

The current implementation intentionally rejects unsupported schema versions
and only assigns behavior to fields that the compiler/runtime actually
consumes. Additional compiler, target, profile, dependency, workspace, and
actor-policy sections should be added version-by-version rather than accepted
and silently ignored.

### Namespace-wide package proof (staged Java API)

The Java compiler now has an **opt-in** `PackageNamespaceBuild` entrypoint
that consumes the existing `OresProjectConfig` and recursively discovers
bounded `.atto`/`.ores` files under `source.roots`. It validates the
complete manifest-owned source set before linking namespace contracts,
retains a package-qualified static actor-method index, and invalidates
incremental work when a source file disappears. It rejects manifest
changes without reloading the configuration and source symlinks/path
escapes. This is not yet wired to the CLI and does not enable executable
external receiver-method bodies; see
[PACKAGE_NAMESPACE_ACTOR_INDEX_V1.md](PACKAGE_NAMESPACE_ACTOR_INDEX_V1.md).
The existing `IncrementalCompiler.compile(Map)` remains a source-map
compiler, not a manifest completeness attestation.

## Import resolution

Oreslang source identity remains filesystem/path based. The manifest does not
create a Java-style package namespace and source files do not need a top-level
`module` declaration.

Resolution order is:

1. `./foo` and `../foo`: only relative to the importing file.
2. Absolute source path: exactly that path.
3. Bare path such as `pkg/http`: each `source.roots` entry in order.
4. Each `source.import_paths` entry in order.
5. Each `ORESLANG_PATH` entry in order.

For a path without an extension, the current canonical resolver tries the
exact file and then `.atto`, `.ores`, and `.java`. Canonical `.atto` entries
win suffix-shadowing conflicts even if they are nonregular or symlinked;
the bounded reader must reject invalid files rather than selecting a legacy
sibling. Explicit `.ores` paths remain supported.

Project-local roots intentionally precede ambient `ORESLANG_PATH` entries so
a developer machine cannot silently shadow a dependency pinned by the project.

## ORESLANG_PATH

`ORESLANG_PATH` is the ambient Oreslang import/search path. It is a list of
directories separated by the host OS path-list separator, exactly like
`PATH`:

```bash
# macOS / Linux
export ORESLANG_PATH="$HOME/.oreslang/lib:/opt/oreslang/lib"

# Windows PowerShell
$env:ORESLANG_PATH = 'C:\\oreslang\\lib;D:\\shared\\ores'
```

Relative entries are resolved from the compiler process working directory.
Empty entries are ignored and never mean the current directory; this avoids
accidental dependency injection from a leading/trailing separator or `::`.

The compiler records the resolved target for every loaded import and reuses
that exact mapping during static linking and runtime linking. Compilation and
execution therefore cannot disagree about which file a bare import names.

## Library profile

Oreslang has exactly two public library layers:

- `ores.core`: freestanding/no-OS source.
- `ores.std`: hosted/platform source.

The optional build selection is:

```toml
[build]
library_profile = "std"
```

The only accepted values are `core` and `std`. Omitting the setting defaults
to `std` so existing hosted projects keep their behavior. Unknown build keys
and old multi-tier values such as `hosted`, `freestanding-core`, `alloc`,
`actor`, and `otp` fail closed.

Canonical compiler-owned imports are `ores.core/*` and `ores.std/*`.
Existing `std/*` imports remain a compatibility spelling for `ores.std/*`
during migration; they do not create another library layer.

`ores.core` is downward closed: a compiler-owned core module may import only
another `ores.core` module. It cannot reach `ores.std`, Java host classes,
project files, or `ORESLANG_PATH`. `ores.std` may depend on `ores.core`
or `ores.std`, but not on application/ambient source from inside the trusted
compiler-owned registry.

The Java/Graal backend is hosted and currently executes only the `std`
profile. A requested `core` build is rejected before import traversal,
parsing, compiler emission, or guest execution. Java may still execute
`ores.core` source as part of a hosted `std` build for semantic
conformance; that is not a bare-metal/no-OS execution proof.

Allocation, actors, supervision, and clocks are not separate public library
tiers. Freestanding implementations belong beneath `ores.core`; adapters
that require OS/host services belong beneath `ores.std`.

