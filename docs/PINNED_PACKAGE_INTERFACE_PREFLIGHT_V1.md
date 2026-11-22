# Pinned package .oresi v1 interface admission — Java/Graal

**Status: implemented metadata admission API; requires executed JUnit and independent review. No Atto guest linking or binary-package import implemented.**

Compile-time Java API: `dev.oreslang.compiler.PinnedPackageInterface.admit(Lock, Map<String,byte[]>)`.

## What it actually does

- Requires explicit locked alias, immutable package instance ID, compiler-version, target ABI and feature-policy identity.
- Pins a bounded, closed namespace inventory to each exact SHA-256 of `.oresi` bytes.
- Calls the existing real Java `ExportInterfaceCodec.decode` (canonical sorted v1 binary format).
- Rejects extra/missing interface files, stale digest, wrong compiler/backend policy, wrong namespace, duplicate namespace pins, orphan public functions with no exported module marker, and malformed wire bytes.
- Returns immutable, qualified public package/namespace/module metadata; does not perform unqualified wildcard import or issue actor/singleton authority.
- Preserves unsigned UTF-8 order emitted by `.oresi`. Golden cross-toolchain digest for the `math/algebra` v1 fixture: `8db5681e1e41c389e2358f9e0cd5c46e78b5b33519ed4769056268a240847f3d`.

## Important limits

This is genuine **Java compiler-host logic**, not guest behavior that belongs in an `.atto` stdlib. No new Java-based guest algorithm is added. Existing `.oresi` v1 is experimental; it does not encode verified imported nominal types, full native storage layouts, runtime capabilities, package-wide init, or cross-namespace source composition. The caller must independently verify who produced the interface and the exact source/compiler/binary release. SHA-256 only binds bytes; self-asserted hashes do not authenticate a producer. The API does not open archive paths or execute binaries.

Interface SHA-256 includes the target ABI/compiler strings, hence this digest is currently **target specific**, not the final target-neutral semantic signature digest. Java and LLVM can decode the same wire version but must not reuse each other's linked code payloads without target verification.

Potential grammar `import pkg bar from "pkg:bar";`, `import module algebra from "pkg:bar";`, and `import namespace math from "pkg:bar";` are deliberately **not** wired to the parser, checked HIR or guest runtime by this branch. See [shared draft conformance #268](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/268), the executable parity tracker [#269](https://github.com/ores-truffle-oreslang/oreslang-common-source/issues/269), and Java [namespace build #2813](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2813).

## Promotion gates

1. Run `mvn -B -ntp -Dtest=PinnedPackageInterfaceTest,ExportInterfaceCodecTest -DfailIfNoTests=true test` on exact PR head under JDK 25. Assert nonzero executed JUnit methods and independent review. No cross-org private-source CI mirrors without source/destination authorization.
2. Specify signed/verified dependency-lock and target binary-provenance format; evolve `.oresi` v2 only by an explicit backwards-compatible/versioned migration.
3. Integrate verified interfaces into real parser/type and module linking with exact package/type identities, visibility, ownership, actor-state and effect checking.
4. Run one `.atto` consumer against source, verified source archive, and compatible target-specific compiled package, including JVM and GraalVM Native Image guest exit 42; negative fixtures must never publish partial artifacts.

Any source-free test, JUnit-only decoder test or zero-step CI run is **NOT_EXECUTED** for AttoLang guest parity.

## 2026-11-11 audit and hardening addendum

The following defects/risks were addressed on this draft branch:

- Added a check that the declared `.oresi` export count is possible within the actual remaining envelope bytes before allocating its decoded symbol table (`ExportInterfaceCodec.decode`).
- Denied dangerous public symbol spellings containing slash/backslash, C0/C1 controls, bidi overrides, zero-width formatting, or Unicode tag controls. Ordinary Unicode identifiers remain permitted.
- Enforced 16 MiB per-interface and 64 MiB aggregate package admission limits before cloning or decoding bytes; all consumed inputs are copied into an owned Java snapshot.
- Expanded `PinnedPackageInterfaceTest` to 15 authored methods, including exact Java/native digest vectors, Unicode acceptance, hostile export names, mixed-case alias rejection, forged count, oversized interface and nominal package isolation.

**Evidence status:** tests are committed but current source-org workflow has no executed steps. The test suite has not been run against a JDK25 compiler in an authorized CI environment. External `.oresi` provenance, actual signed package locks, type/layout ABI and guest linking remain unimplemented. Do not promote to executable package parity.
- Matched the shared reference model's forbidden dependency-alias and namespace identifiers: `constructor`, `prototype`, `__proto__`, and `__root__`.
