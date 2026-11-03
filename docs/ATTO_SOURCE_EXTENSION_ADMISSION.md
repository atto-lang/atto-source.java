# Java/GraalVM AttoLang .atto source admission (first implementation slice)

This change originally admitted `.atto` alongside legacy `.ores`. The stacked AttoLang canonical-source milestone makes `.atto` the default for new source and imports while leaving explicit legacy paths and internal Java/Truffle ABI identifiers compatible. The source still goes through the same bounded UTF-8 reader, `MixedSourceUnit`, transitive import closure, parser, compiler/type+ownership gates, and runtime linking; it does **not** skip or wrap the frontend.

## Scope and safety

- CLI `--check`, `--build-analysis` and direct execution now recognize `.atto` and retain `.ores` compatibility.
- Both explicit `./file.atto` and extensionless imports work. In the stacked canonical milestone an unqualified source import selects `.atto` before a same-directory `.ores` or `.java` twin. Existing `.ores` / `.java` references must be explicit when a canonical twin exists.
- `.atto` is not Java source and does not grant mixed-Java interop capabilities. Native Truffle MIME/language identity remains unchanged for compatibility.
- Invalid UTF-8, oversized inputs, transitive import budgets, dependency capabilities, and owner paths must obey the same checks as legacy guest source.
- JUnit `AttoSourceAdmissionTest` constructs actual `.atto` entry/helper files and runs an `answer() == 42` import through the linked Java/Graal guest pipeline, plus a colliding legacy-name control.
- The full private `lru-cache-atto/lru-cache.atto` source tree is **not** mirrored into this repo. Those seven native fixtures and their compiler-dependent language syntax still require separate source-authorized exact-head guest verification.

This is source admission only: it is not proof of the LRU cache's `critical fnc`, `InvertedArray`, zero-GC, native-image, or multi-backend parity. Keep the PR draft until exact-head executed JUnit and guest receipts.

## Threat-model regressions

- A `.atto` file containing a mixed `java { ... }` declaration is subjected to the same `JAVA_SOURCE_INTEROP` capability gate as `.ores`; extension renaming must never grant ambient host compilation.
- A malformed UTF-8 **transitive** `.atto` import fails through the bounded reader, even when the entrypoint itself is valid. This disallows source-admission bypass by dependencies.

These tests are committed but **not executed** while GitHub jobs report `steps=null`.
## Cache-semantic guest admission probes

Two additional **real linked-guest JUnit candidates** exercise source constructs used by the LRU library, while keeping the private library tree in its own repository:

1. `attoPublicClassConstructorAndMutableReceiverLinkAcrossFiles` imports a public `.atto` class, initializes a constructor-owned field, mutates it via `fnc advance(mut self)(...)` and must emit `42`.
2. `attoOwnedIntegerArrayAndOptionCompileThroughGuestPipeline` uses an owned `Array<int>` column, `add/get/size`, `Option<int>`, `Some/None`, and `unwrap` in a second `.atto` class import. It must emit `42`.

The test names are proposals until actual stepful Maven/Graal execution; they do not establish current compatibility or eliminate the broader seven-fixture LRU gate.

## Symlinked source aliases

The Java linked-unit loader now rejects a symbolic-link **leaf file** for a `.atto` entrypoint or transitive filesystem import before opening its content. The admission tests cover both direct and imported aliases. This narrow policy does not establish symlink-free ancestor directories; those need separate package-root canonicalization. Legacy `.ores` and `.java` behavior is unchanged. The gate remains a code candidate until actual stepful Java Maven tests are executed.

## CI transfer hold

The Java source repository `main` branch reports `protected=false` and status-check enforcement `off`. The source repository remains private. A source-free GraalVM JDK25 carrier test passed in private `usa-acc/usa-acc-e2e`, but security issue #962 has no independently approved destination policy and branch-protection evidence. **Do not mirror private compiler source or treat the carrier canary as authorization.**

## Common-reader Atto source-path hardening (2026-11-02)

The common `BoundedSourceReader` now performs Atto-only, component-by-component symlink rejection before opening an entry or dependency, including intermediate directory aliases. An `NOFOLLOW_LINKS` open also closes the leaf-link window. This applies equally to direct CLI `--check` / `--build-analysis` reads and to linked imports; legacy `.ores` and `.java` source semantics are unchanged.

Three JUnit regressions were added: symlinked parent entrypoint, symlinked imported directory, and direct CLI `--check` denial. A JDK 21 isolated compilation of the reader logic passed 7/7 checks (normal input; symlinked file and parent; invalid UTF-8; NUL; >16 MiB; legacy `.ores` behavior). **This is a source-reader isolation check, not the Maven project suite, a native compiler receipt, or guest execution.**

### Pinned directory-handle admission (source candidate)

`BoundedSourceReader` now opens `.atto` files by traversing from the absolute filesystem root with `SecureDirectoryStream` and `NOFOLLOW_LINKS` at **every directory component and the leaf**. All nested directory streams remain open during the final bounded read. This prevents an ancestor symlink from being substituted between a path check and file open. If the filesystem provider cannot offer secure pinned directory handles (for example ZipFS), `.atto` reading **fails closed** with `SecurityException`; legacy `.ores` and `.java` admission is unchanged. On unsupported platforms/filesystems, this intentionally means `.atto` cannot yet compile until a security-equivalent provider implementation is available.

Two additional JUnit source-reader checks cover ordinary multibyte UTF-8 plus a synthetic ZipFS provider rejection; both remain **NOT_EXECUTED by Maven** until source-org runner admission or an independently authorized compiler-source carrier is available. A standalone source-free JDK 21 secure-handle probe completed **6/6 synthetic path tests** (ordinary, leaf-link, parent-link, empty, oversize, missing) and a separate ZipFS unsupported-provider probe passed locally. Neither is Java compiler or private-source test evidence.

The existing symlink preflight is a diagnostic guard, not relied upon for race-proof authorization; pinned handle traversal is the enforcement path. Filesystems providing weaker semantics, reparse points, and multi-platform AOT behavior remain explicit backend conformance obligations.

Keep the source-export security review gate open; this source fix does not authorize private compiler mirroring.

At exact PR head `2897d19750b6bfffc865b7db9a6ca669a0512af6`, Java Actions `fast-ci` run 41490485954, synthetic CI runner smoke 41490486044, and project manifest run 41490486334 each have a failed job with zero recorded steps. These are **runner-admission failures**, not a JUnit/parser/compiler verdict. Do not bypass private source-transfer holds to use a different organization's Actions minutes.

## Canonical AttoLang source contract — stacked PR (2026-11-03)

The public language name is **AttoLang**, and new guest source files end in
`.atto`. New projects use `.atto.pkg.toml`, `ATTOLANG_PATH`, and
user-facing `attolang` CLI spelling. The internal `dev.oreslang` Java
packages and Truffle identifiers are temporary ABI compatibility details,
not recommendations for new projects.

**Resolution priority per search directory:** an explicitly suffixed
`cache.atto`, `cache.ores`, or `cache.java` is exact; extensionless
`cache` selects `cache.atto`, then legacy `cache.ores`, then
`cache.java`. The compiler searches configured project roots before
ambient paths; it does **not** skip a selected project-local legacy module
to find an ambient Atto module with the same import name.

**Project discovery:** climb from importer directory toward the filesystem
root; at each directory prefer `.atto.pkg.toml` to
`.oreslangc.cfg.toml`. If no canonical manifest exists *at that directory*,
the legacy file is accepted as a migration fallback. A malformed canonical
manifest fails explicitly; it must not silently load a legacy sibling.

**Environment:** `ATTOLANG_PATH` wins over `ORESLANG_PATH`. If the new
name is set, including an intentionally empty string, the old name is not
consulted. Do not concatenate both variables or implicitly append the
current directory.

The included `examples/atto-canonical` package demonstrates an
extensionless `.atto` import and a canonical manifest. Targeted Java
tests include same-basename collision behavior, explicit legacy imports,
manifest precedence, search-root escapes, and an actual two-unit linked
Atto guest that must output `42`.

**Evidence boundary:** source commits and tests are not compiler execution
evidence. The Java/Graal compiler PR must produce a stepful exact-head
Maven test and linked guest receipt before claiming working import
resolution. LRU cache PR #6 must independently execute all 14 positive
fixtures and four ownership/effect negatives. No cross-organization private
compiler-source mirror is authorized by this change.
