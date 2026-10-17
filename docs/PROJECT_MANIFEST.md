# Oreslang project manifest and root-anchored imports

The canonical package manifest is `.ores.pkg.toml`. Its parent directory is the
default **project root**. The older `.oreslangc.cfg.toml` remains supported for
existing projects. Discovery walks upward from the importing entry source file
(or the current working directory when no source file is provided), choosing
the nearest directory containing a manifest. When both names exist in the same
directory, `.ores.pkg.toml` takes precedence.

The filesystem search does **not** depend on the process working directory
when compiling an entry source file.

## Manifest

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

All paths in the manifest resolve relative to `[project].root`. In a modern
`.ores.pkg.toml`, that root must be `.` or a **canonical, existing, relative
subdirectory** of the manifest directory. Absolute paths, `..`, redundant
`.` segments, backslashes, control bytes and root symlinks that physically
escape the manifest directory are rejected. Nested modern or legacy package
markers along either the spelled or physical root path are also rejected.
Omitting `root` selects the manifest directory.

Existing legacy `.oreslangc.cfg.toml` root handling is retained separately
for compatibility. Known manifest properties (`project.root`,
`entrypoints.main`, `source.roots`, `source.import_paths`) must have the
correct TOML type, rather than being silently ignored.

Unrecognized schema versions are rejected. Only existing supported sections
are interpreted; package registries, workspaces, and dependency pinning are
separate features, not silently inferred from arbitrary TOML fields.

## Import syntax

```ores
import module telemetry from "+/lib/telemetry";
import module store from "+/lib/store";
import module relative from "./local";
import module sibling from "../sibling";
```

The prefix `+/` is reserved for **project-root-anchored** imports. The path
after the prefix is relative to the resolved project root, regardless of the
importing source file's depth, and has no dependence on `source.roots`,
`source.import_paths`, or `ORESLANG_PATH`.

The spelling `+lib/store` is intentionally unsupported. Use `+/lib/store`
only. A bare `+`, an empty suffix, empty segments, `.` and `..` segments
are invalid. On every platform, use forward slashes in source imports.

`+/` requires a project manifest. An unresolved project-root import is an
error: it must **not** silently fall through to an external dependency
resolver. Existing files are also checked after resolving symbolic links,
preventing `+/` from escaping the project root.

Root-anchored paths resolve exactly, then with `.ores`, then with `.java`
for suffixless paths, preserving the existing file-probing contract.

To use `+/lib/store` as shown above, place `lib/store.ores` directly under
the project root. If the file instead lives in `src/lib/store.ores`, use
`+/src/lib/store`.

## Other import resolution

Existing rules remain:

1. `java:` host imports use capability-gated host interop.
2. Compiler-owned `std/` imports use the sealed core/standard-library resolver.
3. `+/` imports resolve only under the selected project root.
4. `./` and `../` imports resolve relative to the importing source file.
5. Absolute filesystem imports resolve directly to the named path.
6. Bare paths search `source.roots`, then `source.import_paths`,
   then colon-separated `ORES_PATHS`, then host-delimited `ORESLANG_PATH`.
   Omitted `source.roots` defaults to `["."]`, but explicit `roots = []`
   never silently adds the project directory.
   Dot-prefixed bare paths such as `.hidden/helper` use the same search
   order, while only `./` and `../` are importer-relative.

The narrower `+/` implementation in this PR does **not** implement
verified zed-pkg alias contexts. Until the full package resolution and
source ownership rules from Java #1050 are reconciled, an explicit `pkg:`
import fails closed instead of silently resolving as a bare filesystem name.
The retired `@/` root marker also fails closed.

Existing import targets are recorded during source-closure collection and
used again during static and runtime linking; execution must not reselect
a different module for the same import.

## Explicit root selection and CLI flags

To compile from another directory or from an editor/build host, specify
the root explicitly:

```bash
oreslang-compiler --project-root=/path/to/my-app src/main.ores
ORES_PROJECT_ROOT=/path/to/my-app oreslang-compiler src/main.ores
```

When `--project-root` is provided, it overrides `ORES_PROJECT_ROOT`;
without a CLI override, `ORES_PROJECT_ROOT` overrides automatic discovery.
An explicitly selected root must exist and contain one of the recognized
manifests; missing manifests fail immediately. Relative overrides are
resolved against the invoking process working directory.

The canonical `.cli-flags.toml` maps the string flag `project-root` to
`ORES_PROJECT_ROOT`. This is configuration for the CLI, **not** a new module
import namespace or a second project-root marker.

Precedence: `--project-root` > `ORES_PROJECT_ROOT` > nearest manifest
found by source-relative upward discovery.

## ORES_PATHS and ORESLANG_PATH

`ORES_PATHS` is the canonical colon-delimited ambient bare-import search
list on all platforms, and is searched **before** the legacy
`ORESLANG_PATH`. Both are searched after project `source.roots` and
`source.import_paths`. Empty components in either list are ignored,
rather than granting implicit CWD. Duplicate directories keep the first
precedence position. Path strings with spaces are preserved verbatim.

```bash
export ORES_PATHS="/opt/oreslang/plugins:$HOME/.oreslang/extra"
```

## ORESLANG_PATH

`ORESLANG_PATH` is the ambient search list for *bare* imports, separated
by the host OS path-list separator. It never overrides `+/`.

```bash
# macOS / Linux
export ORESLANG_PATH="$HOME/.oreslang/lib:/opt/oreslang/lib"

# Windows PowerShell
$env:ORESLANG_PATH = 'C:\\oreslang\\lib;D:\\shared\\ores'
```

Relative entries are interpreted from the compiler process working directory.
Empty entries are ignored and do not implicitly name that directory.
