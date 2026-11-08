# atto-source.java

AttoLang compiler and actor runtime targeting the JVM, GraalVM, and GraalVM Native Image AOT.

## Scope

This repository is intended to host the Java/GraalVM compiler backend, actor runtime integration, and supporting build and conformance tooling.

## Status

Repository initialization and implementation status should be checked against the source tree and CI. This README describes the intended scope and does not assert that every feature is implemented.

## Ecosystem

- [AttoLang language organization](https://github.com/atto-lang)
- [Atto commercial infrastructure](https://github.com/atto-co)

## Development

See the repository's source tree, workflows, and contribution documentation for verified build and test instructions. Do not assume cross-backend parity without executed conformance results.

## Repository ownership and contribution routing

**Belongs here:** Java/GraalVM compiler, JVM execution, Graal Native Image AOT path, Java host-library import admission, actor-owned heap/stack runtime and guest execution tests.

**Belongs elsewhere:** Normative semantics live in atto-spec; portable .atto library source belongs in atto-common-source; LLVM/.NET/Rust/Wasm implementation must remain in their own backend repos.

**Security and portability boundary:** Host Java/Graal library calls require explicit capabilities, ownership and lifetime fencing. Native Image compilation or Epsilon host smoke alone is not proof of zero GC, isolation or guest-level parity.

AttoLang is the public name; preserve existing Oreslang repository, CLI and `.ores` compatibility while specifying new portable source in `.atto`. Do not duplicate normative semantics from [atto-spec](https://github.com/atto-lang/atto-spec).

**Evidence gate:** record exact compiler/runtime head and tree, target/toolchain, nonzero executed steps, positive and negative guest results, and explicit passed/failed/unsupported/not-executed states. Host-only CI and `steps=[]` do not establish guest support. README scope is not a feature-completeness claim.
