# Oreslang project manifest

Oreslang projects may define compiler/project configuration in the nearest
`.oreslangc.cfg.toml`. The compiler walks upward from the entry source file
(or from the current working directory when no source file is supplied) and
uses the first manifest it finds.

This is intentionally a project/compiler manifest: lighter than Maven's build
lifecycle, but more project-aware than a source-only compiler switch file.

## Supported v1 contract

```toml
schema_version = "1"

[project]
name = "my-ores-app"
version = "0.1.0"
root = "."

[source]
roots = ["src"]
import_paths = ["lib", "vendor"]

[entrypoints]
main = "src/main.ores"
```

All manifest filesystem paths are resolved relative to `[project].root`,
which itself is relative to the manifest directory unless absolute.

The current implementation intentionally rejects unsupported schema versions
and only assigns behavior to fields that the compiler/runtime actually
consumes. Additional compiler, target, profile, dependency, workspace, and
actor-policy sections should be added version-by-version rather than accepted
and silently ignored.

## Library profile (Java/Graal fail-closed admission)

Oreslang has exactly two library layers:

- `ores.core`: freestanding language/runtime facilities with no operating-system dependency.
- `ores.std`: hosted/platform facilities, including OS I/O and other services unavailable on bare metal.

The version 1 project manifest recognizes one optional `[build]` setting:

```toml
[build]
library_profile = "std"
```

The only accepted values are `std` and `core`. An omitted value defaults to
`std` for backward compatibility with existing hosted projects. Unknown,
misspelled, or non-string values fail admission.

The Java/Graal runner currently executes only the `std` profile. Requesting
`core` fails before import traversal, parsing, compilation, or guest execution.
That fail-closed check prevents a hosted build from being mislabeled as a
freestanding/no-OS build.

Canonical compiler-owned imports use `ores.core/*` and `ores.std/*`.
The initial `ores.core/numeric` module is packaged source with unit identity
`@ores.core/numeric.ores`. It cannot be shadowed by project files or
`ORESLANG_PATH`, and an `ores.core/*` module may import only other
`ores.core/*` modules. It may not import `ores.std/*`, Java host classes,
project modules, or ambient modules.

Existing `std/*` imports remain a compatibility spelling for `ores.std/*`
during migration. They do not define a third library layer. The old proposed
`alloc/*`, `actor/*`, and `otp/*` top-level library profiles/namespaces
are intentionally removed from this design; those facilities belong beneath
one of the two canonical layers according to whether they require hosted
platform services.

The packaged `ores.core/numeric` source is still only a source-provenance
proof on Java/Graal. It is not yet evidence of a no-libc/no-OS executable.

See [Java admission issue #1209](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1209).

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

For a path without an extension, the resolver tries the exact file and then
`.ores` and `.java`.

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
