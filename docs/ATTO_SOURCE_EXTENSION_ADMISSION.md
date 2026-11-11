# Java/GraalVM AttoLang .atto source admission (first implementation slice)

This change supports `.atto` as a second native guest source extension, preserving `.ores` and mixed `.java` file compatibility. The source still goes through the same bounded UTF-8 reader, `MixedSourceUnit`, transitive import closure, parser, compiler/type+ownership gates, and runtime linking; it does **not** skip or wrap the frontend.

## Scope and safety

- CLI `--check`, `--build-analysis` and direct execution now recognize `.atto` and retain `.ores` compatibility.
- Both explicit `./file.atto` and extensionless imports work. Extensionless imports deterministically prefer `.atto`, then legacy `.ores`, then `.java`. Explicit suffixes remain authoritative: `./helper.atto` always selects the canonical AttoLang file and `./helper.ores` continues to select the legacy file.
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

### Known remaining boundary

Checking ancestor path components before opening a file is *not an atomic resolution guarantee*: an adversary with write permission to parent directories can race directory changes after the check. The leaf open uses `NOFOLLOW_LINKS`, but race-proof ancestor admission still requires an approved secure directory-relative source-root walker (for example a provider supporting `SecureDirectoryStream`) and a fallback policy. Legitimate system-level symlink aliases may also require callers to use their canonical physical path. Keep the security review gate open rather than claiming full race-free traversal.

At exact PR head `2897d19750b6bfffc865b7db9a6ca669a0512af6`, Java Actions `fast-ci` run 41490485954, synthetic CI runner smoke 41490486044, and project manifest run 41490486334 each have a failed job with zero recorded steps. These are **runner-admission failures**, not a JUnit/parser/compiler verdict. Do not bypass private source-transfer holds to use a different organization's Actions minutes.


## Canonical suffix selection hardening (2026-11-02)

`OresProjectConfig` now checks canonical `.atto` entries with `BasicFileAttributes` and `NOFOLLOW_LINKS`. A regular file, directory, symlink (including a dangling symlink), or other existing entry occupies the canonical name. Only `NoSuchFileException` means absence; other metadata errors fail closed. The selected path is handed to the bounded source reader, which rejects non-regular/symlink input. `.ores` and `.java` explicit-path behavior remains unchanged.

Regressions cover both a dangling `.atto` symlink and a directory named `.atto` beside a valid `.ores` sibling. The directory/symlink must be selected as canonical rather than triggering legacy fallback. An isolated JDK 21 resolver harness compiled and passed six cases: canonical precedence, explicit legacy, legacy fallback, directory shadow, dangling-link shadow, and foreign-extension rejection. This is isolated resolver evidence only; it does not substitute for the exact-head Maven/JUnit or compiler guest suite.


## Reconciled canonical AttoLang manifest admission

The nearest `.atto.pkg.toml` or legacy `.oreslangc.cfg.toml` controls the source project; two manifests in the same directory are ambiguous and are rejected. Unreadable manifest metadata cannot silently fall through to a different project. This merge preserves current main's canonical `.atto` suffix precedence, mixed-source interop gates, stronger imported-class ownership resolution, and null-safe pattern/condition evaluation.

The older pinned `SecureDirectoryStream` source-reader experiment is NOT silently substituted for current main's root-owned alias portability policy. Its stronger race-fencing claim needs distinct portability/security review and executed native tests before promotion. Java full-source tests are not executed while private Actions runner admission is blocked.
