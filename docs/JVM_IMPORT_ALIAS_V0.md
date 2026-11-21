# AttoLang JVM import alias v0 (Java/Graal)

The Java/Truffle frontend accepts an explicit backend-qualified shorthand:

```atto
import jvm "java.util.ArrayList" as JList;
```

This is semantically **identical** to the existing syntax:

```atto
import class ArrayList as JList from "java:java.util.ArrayList";
```

The parser produces the same `Ast.ImportDecl(CLASS, ...)`, so TypeChecker,
ImportRules, CapabilityChecker, IsolatePolicy and Graal host allowlists remain
authoritative. The shortcut does not allow arbitrary runtime reflection,
grant host permissions, bypass access restrictions, or make source portable
to LLVM/Wasm/.NET. Dotnet-qualified imports are rejected on Java.

## Security and Native Image limits

- Class names must meet the existing `java:` binary-name rule.
- A host class must be explicitly allowlisted and `JAVA_INTEROP` must be granted.
- Private/untrusted actors must retain the existing interop restrictions.
- Host imports are not required to initialize a class merely to be parsed.
  Lazy lookup/initialization remains tracked separately in Java PR #1764.
- Graal Native Image reachability/configuration and linked execution require
  exact-head stepful Graal AOT tests; parser alias success does not prove AOT.
- Host Java objects use managed JVM semantics. No blanket zero-GC or
  cross-actor Sendable/borrow guarantee is implied.

## Acceptance

`HostImportTest` checks legacy AST equivalence, successful allowlisted
execution, denied allowlists, invalid Java binary names, and backend mismatch.
Do not promote on empty GitHub Actions job steps.

## Required GraalVM integration gates

- Run `mvn test -Dtest=HostImportTest` on the exact PR head under the pinned
  JDK/GraalVM toolchain; a parser-only test is insufficient proof of execution.
- Run the restricted-context JUnit execution on both allowed and denied class
  lists. Preserve the default lack of `JAVA_INTEROP` in private actors.
- Build a true `-Pnative-aot` Native Image (not just a native-host JVM
  process). Execute an allowlisted host adapter under the same policy, with
  no dynamic reflection fallback; document the actual output and binary SHA.
- Integrate the inert-linking `LazyHostSymbol` changes in
  [Java PR #1764](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1764)
  **semantically**, after independent review. This branch intentionally does
  not duplicate or replace that pending host-symbol lifecycle change.
- Track the shared negative/positive source oracles in
  `oreslang-platform-conformance/contracts/host-interop-v0`; do not mark
  those cases VERIFIED from parser, source-free metadata or zero-step CI.
