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

## Shared conformance fixtures and unimplemented package visibility

This PR now pins the four **exact Git blobs** from merged
`atto-platform-conformance#84` under `tests/parity/class-fields-v2/`.
Each blob is independently verified against its upstream SHA in source
readback, JUnit and CI. The positive fixture includes `pub[pkg]`; Java's
current `Ast.Visibility` represents only PUBLIC/PRIVATE, so accepting the
positive fixture as public would improperly widen access. The parser
therefore rejects any `pub[pkg]` member declaration with
`E_PKG_VISIBILITY_UNSUPPORTED` until authenticated package scope is
implemented, and the matching fixture is tested as an **intentional
fail-closed conformance gap**, not a completed positive execution.

The other three fixture tests require failure of implicit-`val`
constructor reassignment, outside-class private access and a runtime-only
initializer on `const`. Exact `E_IMMUTABLE`, `E_PRIVATE`, `E_CONST`
diagnostic agreement remains to be separately established on a real
runner; merely rejecting source is not full conformance. The current
Java AST also does not distinguish a dedicated `final` field binding
variant from `val`; deferred exactly-once `final` semantics remain
a separate backend parity gap, not covered by the default change.

## Required verification

```sh
mvn -B -ntp '-P!native-thread-unix' \
  -Dtest=AttoCanonicalImplicitValClassFieldTest,AttoClassFieldV2ConformanceSourceTest \
  -Dsurefire.failIfNoSpecifiedTests=true clean test
```

Also run complete relevant class-constructor/ownership regression suites,
and both canonical and legacy compiled guest scenarios using a real
Atto-selected compiler entrypoint before claiming general release parity.
CI must record exact checkout HEAD and tree; zero-step jobs count as
`NOT_EXECUTED`. Full Rust/formatter cross-backend parity is not inferred
from parser AST assertions.
