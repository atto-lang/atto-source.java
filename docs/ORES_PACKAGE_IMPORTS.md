# Oreslang package-root and zed-pkg imports (draft v1)

This document describes the Java/Graal import resolver feature introduced in
this branch. The LLVM compiler and standalone bundlers need parity work before
claiming cross-backend support.

## Source import forms

```ores
import fnc local from "./neighbor";
import fnc common from "common/util";            // search roots
import fnc shared from "@/src/shared/util";      // project root
import fnc answer from "pkg:math/src/answer";     // manifest dependency
import class ArrayList from "java:java.util.ArrayList";
```

- `@/` is the one-character **project-root marker**. It refers to the
  directory containing the nearest `.ores.pkg.toml`, not the process cwd.
  `@/` never falls through to ambient search roots. Missing `.ores.pkg.toml`,
  missing files, and attempts to escape via `..` are errors.
- `pkg:<alias>/<path>` is a manifest-declared dependency alias, resolved
  under the corresponding zed-pkg installation. The compiler does **not**
  download packages, resolve semver, or run installers.
- Bare imports check project `source.roots`, `source.import_paths`,
  `ORES_PATHS`, then legacy `ORESLANG_PATH`, in that order.
- `./` and `../` keep their existing importer-relative meaning.
- `java:` remains capability-gated Java host interop, not a filesystem import.

Source-file resolution supports the exact path and extension inference (`.ores`,
then `.java`) as before. The first existing match wins.

## Package manifest: .ores.pkg.toml

```toml
schema_version = "1"

[package]
name = "demo"
version = "0.1.0"

[source]
roots = ["src"]
import_paths = ["vendor"]

[entrypoints]
main = "src/main.ores"

[dependencies]
math = { zed = "acme/ores-math" }
http = "acme/ores-http"
```

The package manifest's location defines the project root; it cannot redirect
the root using `project.root`. `[dependencies]` declares aliases; each value
must be a zed-pkg `org/name` or `{ zed = "org/name" }`. Package aliases are
not automatically injected into bare-path imports, preventing package
shadowing of local sources.

When a `.ores.pkg.toml` exists on the ancestor path, the *nearest package
manifest* wins, including over a nearer legacy `.oreslangc.cfg.toml`. If no
package manifest exists, the nearest legacy compiler manifest is used.
Manifest-less and legacy projects retain their original transitive bare-import
search context, for backwards compatibility.

## zed-pkg integration

zed-pkg maintains `.zpkg.toml` and `.zpkg.lock` and materializes packages
under a project-local install directory; the default is `zed_modules/`, but
`.zpkg.toml` can override `[install].dir`. The Oreslang resolver reads that
directory setting, and resolves `pkg:math/src/answer` as:

```text
<project>/<zed install dir>/acme/ores-math/src/answer.ores
```

zed's per-project links into the content-addressed global store work normally.
The compiler follows symlinks and canonicalizes existing source files with
`toRealPath()`; this prevents duplicate compilation or self-imports from
different paths to the same file. At a `pkg:` boundary, manifest discovery stops
at the installed package's canonical physical root; it cannot accidentally
pick up the consumer's project manifest. A dependency with no `.ores.pkg.toml`
cannot import `@/` from its consumer. Nested package manifests reached through
relative or bare imports are discovered as distinct source roots. Symlink traversal can resolve files outside
the project root. These rules are **import discovery**, not filesystem
confinement or a defense against a hostile concurrent filesystem mutator.

A typical development flow is:

```sh
zed add acme/ores-math@^1
zed install --frozen
export ORES_PATHS="$HOME/ores-shared:/opt/ores-libs"
oreslang --check src/main.ores
```

Keep any zed dependencies in `.zpkg.toml` aligned with `.ores.pkg.toml`
aliases, and commit the zed lockfile. If an installed dependency contains
`.zpkg.toml`, its `package.org` and `package.name` must match the declared
`org/name`; malformed or mismatched metadata is rejected. This metadata
comparison is **not** a substitute for verified lockfile or artifact provenance. For CI, `zed install --frozen` or
equivalent verified materialization must run *before* compilation. Compiling
alone does not validate semver locks or artifact provenance.

## ORES_PATHS

`ORES_PATHS` uses `:` as its delimiter. Each segment is an import root,
not a filename. Empty segments are ignored; duplicates are removed in first
appearance order. Relative segments are resolved from the compiler's cwd.
No `~` or environment-variable interpolation is performed inside a segment.

```sh
export ORES_PATHS="/work/ores:/usr/local/share/ores:/some/link-to-ores"
```

This delimiter is intentionally Unix-oriented, unlike the legacy
`ORESLANG_PATH`, whose separator is host-native. Bare Windows drive paths
containing a colon cannot be represented directly in `ORES_PATHS` v1; use
`source.import_paths` or legacy `ORESLANG_PATH` on Windows.

## Tests and outstanding parity

Tests added alongside this proposal cover project-root precedence, legacy
manifest compatibility, manifest-less transitive resolution, ordered path lists,
symlinked roots, installed package identity, customized zed install directories,
malformed manifests, traversal rejection, nested project boundaries,
whole-program linking, and symlinked self-imports.

Outstanding: exact-head JDK25/JUnit execution, Graal/native validation,
LLVM frontend/linker parity, lockfile/version consistency checks, and
full transitive dependency-layout parity. Cross-package project-root imports
have a Java regression test, but no JDK25 execution evidence yet.
