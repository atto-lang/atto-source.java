> **AI agent instructions (organization-wide):** Before working on code, issues, or pull requests, read and follow the [organization `AGENTS.md`](https://github.com/atto-lang/.github/blob/main/AGENTS.md), including its **merge-first stealth-development policy**. Honor any repository-local `AGENTS.md`, explicit holds, and GitHub-enforced rules.

# atto-source.java

AttoLang compiler and actor runtime targeting the JVM, GraalVM, and GraalVM Native Image AOT.

## Naming and specification migration

This is the canonical [AttoLang backend repository](https://github.com/atto-lang/atto-source.java). Use `.atto` for portable AttoLang source and update local remotes to `https://github.com/atto-lang/atto-source.java.git`; do not use historical repository links or naming in new examples.

- **Normative grammar and semantics:** [atto-spec](https://github.com/atto-lang/atto-spec). Check the current versioned contracts for ownership operations (`borrow!`, `take!`, `share!`, `copy!`, `copy!!`), implicit argument borrowing, `lt!` binders, `eq`/`neq` value equality, `susp` effects and file/module `define const`. These are *specification targets*, not claims of executable support in this backend.
- **Positive and negative guest execution evidence:** [atto-platform-conformance](https://github.com/atto-lang/atto-platform-conformance) and [atto-compilers-parity-testing](https://github.com/atto-lang/atto-compilers-parity-testing). Report `passed`, `failed`, `unsupported` or `not executed`, and pin exact compiler, source tree, target and toolchain.
- **Portable shared sources and target constraints:** [atto-common-source](https://github.com/atto-lang/atto-common-source) and [atto-target-profiles](https://github.com/atto-lang/atto-target-profiles). Keep backend-specific gaps explicit, and do not infer guest parity from host tests or zero-step CI runs.

## Scope

This repository is intended to host the Java/GraalVM compiler backend, actor runtime integration, and supporting build and conformance tooling.

## Status

Repository initialization and implementation status should be checked against the source tree and CI. This README describes the intended scope and does not assert that every feature is implemented.

## Ecosystem

- [AttoLang language organization](https://github.com/atto-lang)
- [Atto commercial infrastructure](https://github.com/atto-co)

## Development

### Build and run the Java CLI

Use Maven with `JAVA_HOME` pointing to GraalVM 25.3.4.1 (JDK 25.0.4.1), matching the Truffle components in `pom.xml`. For a distributable JVM launch, keep `target/lib/` beside the JAR.

```sh
mvn -DskipTests package
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED \
  -jar target/oreslang-source-0.1.0-hotload-SNAPSHOT.jar \
  --check tests/fixtures/suspends-await-v1/inline-nested-defer.atto
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED \
  -jar target/oreslang-source-0.1.0-hotload-SNAPSHOT.jar \
  tests/fixtures/suspends-await-v1/inline-nested-defer.atto
```

Both commands check the linked source graph and ownership rules. The second command resumes the nested guest continuation and prints `defer:42|root`. In callable declarations, `susp` and `suspends` mark an inline suspension effect. An `async` function call starts a task and returns a `Future`. An explicit `await` consumes its completion in the caller.

To run with Maven directly:

```sh
mvn compile exec:java '-Dexec.args=tests/fixtures/suspends-await-v1/inline-nested-defer.atto'
```

To build a standalone GraalVM Native Image executable:

```sh
mvn -DskipTests -Pnative-aot package
target/ores-aot tests/fixtures/suspends-await-v1/inline-nested-defer.atto
```

### TCP reactor and scheduler

`network.listen_async` uses VM-owned NIO2 completion workers by default. Set `-Dores.runtime.nio.shared-selector=true` before `-jar` to run TCP readiness polling between turns on the root scheduler carriers. For the native executable, put the same property before the guest arguments. A selector poll can settle a Future, and the owning scheduler resumes the guest continuation on a later turn. `--control-workers=N` configures the root and CONTROL carriers. Shared, isolated and untrusted actor dispatch each have their own pool. Spin's `http.listen` uses the JDK HTTP server.

The packaged CLI integration tests exercise both TCP modes and check that borrow and numeric-profile diagnostics agree between `--check` and execution.

```sh
mvn -Dtest=BorrowedOperandSuspensionAuditTest,OresVMNioReadinessLifecycleTest,AttoLibrariesV1Test \
  -Dit.test=PackagedAttoCliIT verify
```

## Repository ownership and contribution routing

**Belongs here:** Java/GraalVM compiler, JVM execution, Graal Native Image AOT path, Java host-library import admission, actor-owned heap/stack runtime and guest execution tests.

**Belongs elsewhere:** Normative semantics live in atto-spec; portable .atto library source belongs in atto-common-source; LLVM/.NET/Rust/Wasm implementation must remain in their own backend repos.

**Security and portability boundary:** Host Java/Graal library calls require explicit capabilities, ownership and lifetime fencing. Native Image compilation or Epsilon host smoke alone is not proof of zero GC, isolation or guest-level parity.

AttoLang is the canonical project identity. Use `.atto` source, `atto-*` package and repository names, and the active AttoLang CLI contract for current documentation. Do not duplicate normative semantics from [atto-spec](https://github.com/atto-lang/atto-spec).

**Evidence gate:** record exact compiler/runtime head and tree, target/toolchain, nonzero executed steps, positive and negative guest results, and explicit passed/failed/unsupported/not-executed states. Host-only CI and `steps=[]` do not establish guest support. README scope is not a feature-completeness claim.
