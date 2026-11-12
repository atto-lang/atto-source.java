# Native Atto `using` telemetry span scope — Java compiler milestone

**Status (2026-11-12): IMPLEMENTATION CANDIDATE, GUEST EXECUTION NOT YET VERIFIED.**
This change is the first limited executable guest-scope slice after the shared
automatic-instrumentation proposal in common-source
[#279](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/279)
and language-spec
[#44](https://github.com/ores-truffle-oreslang/attolang-language-spec.md/pull/44).

## Candidate implementation

`src/main/java/dev/oreslang/parser/Parser.java` recognizes the existing
contextual `using` statement in the exact expression form
`using telemetry.span(...) as alias { body }`. During this initial
compiler milestone, only a non-generic `*.span(...)` member factory is
admitted for **generic scope lowering**, subject to ordinary method/type
checking. This is **not** an arbitrary `ScopedResource` implementation
or a production OTel SDK. It lowers once into:

```atto
block {
    val alias = telemetry.span(...);
    try {
        body
    } finally {
        alias.close_scope();
    }
}
```

This is *conceptual* lowered Atto source: the real implementation builds
equivalent existing AST nodes, rather than rewrites user source text.
The resource expression is evaluated exactly once, cleanup is scoped to
the original binding, and user-visible return/control follows the existing
`try/finally` evaluator. Explicit `*.borrow()` retains its separate
`UsingBorrowStmt` and the existing typechecker/evaluator fail-closed gate.
Other unrecognized resource factories still parse-reject.

## Candidate executable tests

`src/test/java/dev/oreslang/UsingTelemetrySpanScopeTest.java` adds
7 JUnit tests:

- lexer/parser lowering shape and exact single synthetic cleanup site
- sync guest fallthrough, return and nested scope cleanup
- type checker rejection if `close_scope` does not exist
- preservation of `request.borrow()` transport-loan rejection
- malformed alias rejection and unchanged expression calls named `using`

Test guest code defines a small native Atto `telemetry.span` provider in the
same source for **compiler-execution proof only**; it is not a real trace
collector/exporter and does not claim disabled fast-path allocation bounds.

## Explicit unresolved gates before promotion

1. **Run** `mvn -q -Dtest=UsingTelemetrySpanScopeTest,UsingRequestBorrowScopeParserTest test`
   with the current Java/Graal/JDK25 toolchain and exact branch HEAD.
   Host-only parsing or successful source submission is not evidence.
2. Add a dedicated `UsingScopeStmt` or equivalent source-provenance
   metadata if the formatter/source mapper needs to preserve original
   `using` syntax. Current AST desugaring does **not** guarantee
   `using` formatter round-trips or accurate `using` source spans.
3. Add compiler-verified `ScopedResource` admission and a close contract
   that is nonthrowing/nonsuspending for `SpanScope` plus ownership/escape
   checks. The current method-call typecheck proves only callable
   `close_scope()`, not its complete exceptional/async effects.
4. Validate `using` resource scope across async `await`, task cancellation,
   actor restarts, `?` early return, `trap` and `raise/panic` once those
   guest effects are supported. Existing `try/finally` tests are
   encouraging but are **not** direct `using` test evidence.
5. Implement the actual native `.atto` trace context/span/collector API;
   this PR does not introduce a production stdlib module or default-off
   allocation-free, clock-free noop span yet.
6. Real JIT instrumentation: the current Java interpreter executes guest
   callables from `OresEvalRootNode`'s monolithic Evaluator and does not
   currently expose individually tagged Truffle `InstrumentableNode`
   function bodies. Truffle Instrumenter feasibility is **conditional**
   on exposing proper per-callable tagged AST/bytecode operations with
   source identifiers, host authorization, bounded deoptimization and
   independent runtime tests. Do **not** claim live attach already works.
7. Independently implement and prove LLVM full guest AOT scopes plus
   precompiled dormant probe enable; later prove Forge, .NET Native AOT
   and Wasm according to the common contract.

## CI and security

At this branch's initial head, source-org GitHub Actions workflows report
failure with `steps=[]`: treat as **NOT EXECUTED** rather than compilation
failure or passing tests. Do not weaken assertions, install unsafe
fallbacks, or copy private source into a new organization without
approved source-side authorization/security review and exact-blob proofs.
