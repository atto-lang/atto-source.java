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
