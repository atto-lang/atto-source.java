# AttoLang singleton: production parser to immutable runtime bridge (V1)

**Status: DRAFT, OPT-IN CHECKED-SNAPSHOT BRIDGE. Full guest singleton execution is still denied.**

This branch is stacked on Java #2772 and #2749, and does not merge Java #2747's actor-private sealed-state machinery. It preserves normal `Parser.parse`, `OresCompiler.parseAndTypeCheck`, the standard `TypeChecker` and the Truffle `OresEvalRootNode` fail-closed behavior for `define singleton`.

## New compiler bridge

`OresCompiler.compileSingletonSnapshotOnly(source, trustedCodeUnitId)` forwards to `SingletonSnapshotCompilerV1.compile`.

The bridge uses the **actual production lexer/parser** in the separately authorized `Parser.parseSingletonsCheckOnly` mode, followed by `SingletonCheckOnly.check`. It accepts only checked, closed, primitive `const` fields in singleton declarations. It forbids mixed executable declarations, imports, dynamic initializers, self-reference and mutable types. It also rejects Symbol literal tokens **before** the production parser can intern permanent process Symbols in a declaration that would later be rejected.

A compiler-resolved `SingletonSharedDomain.Key` combines the source-unit identity, optional namespace, module name and singleton declaration name. The immutable field map is accompanied by a SHA-256 digest of the canonical typed contents. The domain atomically rejects the same identity under a different compiled schema, including attempts to overwrite a compiler-published singleton through the old untyped host API. Failed schema admission does not overwrite the original snapshot. Publication returns **nonparking futures**: callers must not block actor carrier threads waiting for first initialization.

A `Compiled` plan contains no actor heap pointers or mutable guest capabilities; its `publishAsync(domain)` method registers immutable snapshots in the existing bounded domain. A different application domain receives a different physical root. The same domain/source identity receives the same physical snapshot, even for concurrent independently parsed callers.

## What this does NOT provide

- Normal `define singleton` guest compilation through the general `TypeChecker`, `OwnershipChecker`, and Truffle interpreter: **NOT ENABLED**.
- A mutable `state {}` singleton or actor-visible `critical-read/critical-write`: **NOT ENABLED**.
- A source-level `Actor` guest executing operations on the singleton, or compiled actor message semantics: **NOT EXECUTED**.
- Import alias resolution, hot reload, across-process/Graal native-isolate coordination, untrusted actor authority, or a complete linked-program ABI: **NOT IMPLEMENTED/PROVEN**.
- A full Java/Maven build or Graal Native Image test on this branch: **NOT EXECUTED**.

The `checkedCodeUnitId` is supplied by the **trusted compiler/linker**. Guest input must not choose module identity. The whole compilation is intentionally conservative.

## Verification plan and evidence

- Dedicated JDK 25 private carrier: `voxletra/voxletra-e2e`, with a temporary branch, no cross-organization source checkout and `persist-credentials: false`.
- Verify **all exact Git blob SHA identities** for the production parser, AST, Token, Lexer, TemplateLiterals, TaskCaptureNames, Symbol, singleton checker, compiler bridge and host runtime.
- Compile those production files with `javac -Xlint:all,-dangling-doc-comments -Werror`; the one suppressed Javadoc lint is already present in the original `Ast.java` and unrelated to the change.
- Execute `SingletonSnapshotCompilerV1Smoke` and `SingletonSharedDomainHostSmoke` as two separate Java main classes; record assertions, exit codes, exact carrier commit, source commit/tree and test logs.
- Then execute **full** `mvn -B -ntp -DfailIfNoTests=true -Dtest=SingletonCheckOnlyTest,SingletonSnapshotCompilerV1Test,SingletonSharedDomainHostTest test` on a proven full-tree mirror. Do not claim Maven or guest evidence until those jobs actually execute.

## Required next gate for real guest

Make `define singleton` a formally typed, ownership-checked normal declaration with explicit compilation-unit import identity and effect-free initialization. Keep mutable `state{}` sealed until a new shared-region checker proves no raw aliases, no unsafe `await/select` under a loan, authenticated actor execution capabilities and application-domain / native-isolate boundaries. Integrate `OresEvalRootNode` only after the compiler has emitted and verified these proofs; then execute a compiled two-actor guest with shared reads, writer exclusivity and STOP/restart tests.

**Do not merge as a complete singleton language feature or claim five-backend parity.**
