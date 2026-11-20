# Canonical AttoLang class fields: implicit val v2 (Java admission)

Source authority: `atto-lang/atto-spec/language/class-field-binding-defaults-v2.md`.
Historical origin: `ores-truffle-oreslang/oreslang-source.java#2273` (broader
legacy proposal; the `initialize` syntax change is deliberately **not** ported).

## Scope

`Parser.parseStrictAtto` and the explicitly versioned Atto callable parser
use the canonical class-field binder: a type-first class field without a
binding keyword is `val`, not `let`. This is independent of its type and
the `mut` referent qualifier. The explicit `let T field = value` spelling
remains rebindable subject to existing owner/loan checks; `final` and
`const` remain separate contracts. Named `field: Type` retains its
historical fixed-slot behavior.

The default `Parser.parse` entry is **not** silently migrated. Existing
unversioned `.ores` source and guests must not change layout or source
semantics based on a filename extension. A future language-version admission
integration must select canonical Atto explicitly.

## Security / compatibility

- No new cross-actor, cross-TenantVM, or cross-package capabilities.
- Bare slots cannot be mutated or rebound through a method or mutable loan.
  Referent writes still require separate `mut` and proper ownership.
- Older programs that rely on implicit rebindability must opt in explicitly
  to `let` when migrating to the canonical Atto profile.
- No whole-project rewriting of legacy mutable fields is performed.
- Legacy `constructor`/new `initialize` grammar is out of scope.
- Parser/checker unit evidence is not Java/GraalVM guest execution evidence.

## Required verification

```sh
mvn -B -ntp '-P!native-thread-unix' \
  -Dtest=AttoCanonicalImplicitValClassFieldTest \
  -Dsurefire.failIfNoSpecifiedTests=true clean test
```

Also run complete relevant class-constructor/ownership regression suites,
and both canonical and legacy compiled guest scenarios using a real
Atto-selected compiler entrypoint before claiming general release parity.
CI must record exact checkout HEAD and tree; zero-step jobs count as
`NOT_EXECUTED`. Full Rust/formatter cross-backend parity is not inferred
from parser AST assertions.
