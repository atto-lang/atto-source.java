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

`src/test/java/dev/oreslang/UsingTelemetrySpanScopeTest.java` currently defines
22 JUnit test methods (source-defined, **NOT EXECUTED on Java/Graal CI**):

- lexer/parser lowering shape and exact single synthetic cleanup site
- sync guest fallthrough, return and nested scope cleanup
- type checker rejection if `close_scope` does not exist
- preservation of `request.borrow()` transport-loan rejection
- malformed alias rejection and unchanged expression calls named `using`
- extra conditional taken/not-taken guest cleanup assertions, manual-close rejection
- exactly one nonempty literal operation (1..256 UTF-8 bytes) matching LLVM's current narrow subset
- fail-closed parser denial for `await`, `yield`, `select`, `task scope`, and `task {}` inside telemetry scopes
- type-checker rejection of async, suspending, generator, and actor callables until continuation frames are integrated
- explicit parser denial for typed `throw`, `raise`, and `panic` (these are **NOT** unwind execution receipts)

Test guest code defines a small native Atto `telemetry.span` provider in the
same source for **compiler-execution proof only**; it is not a real trace
collector/exporter and does not claim disabled fast-path allocation bounds.

## Explicit unresolved gates before promotion

1. **Run** `mvn -B -ntp -DfailIfNoTests=true -Dtest=UsingTelemetrySpanScopeTest,UsingRequestBorrowScopeParserTest clean test`
   with GraalVM/JDK25 against the exact branch HEAD. The guarded workflow now
   requires at least 34 executed cases (22 scoped tests, the borrow regression,
   and 11 native continuation-lifecycle tests),
   zero skipped/failing/error cases and both Surefire suite receipts.
   Source Actions still fail with **zero job steps** (most recently workflow
   `atto-telemetry-using-exact-head` run `43329579902` at Java head
   `10cda295778311e27025c55b5c106d0069d93449` before subsequent doc edits).
   **No real Java guest JUnit execution receipt exists.**
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


## Latest native LLVM evidence and Java authorization boundary

LLVM [PR #1716](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1716)
has an **executed restricted AOT** acceptance receipt at source commit
`3466e3702094eaeb413382c16cdadd9a7a56161f`:
[approved private run #43325381462](https://github.com/streempilot-test/browser-studio-e2e/actions/runs/43325381462).
That run attested 48 exact source blobs, five positive and five negative real
guest cases at Clang O0/O2, and a same-process trusted-host off/on/off/on
probe toggle producing two spans with unchanged guest result 51. This is
**dormant AOT probe activation**, not JIT insertion.

The Java source allowlist file
`.github/private-ci-approved-repositories.txt` specifies an external private
carrier candidate but requires an independent @ORESoftware approval and
protected-main/V3 attestation **before** any private source transfer.
No Java-source carrier has been authorized by the changes in this PR.
Do not substitute a synthetic local fixture, off-branch run, or zero-step
source-org workflow for exact-head execution.

## Standalone Java runtime-owned scope primitive (not compiler-integrated)

The candidate `dev.oreslang.runtime.NativeTelemetryScopes` implements
**host-created, non-guest-visible** opaque `SpanToken` values with private
construction. Tokens are tied to a logical continuation `Carrier` rather
than a Java thread. The trusted host API provides:

- disabled fast path reusing a carrier-owned preallocated token (no token
  allocation, no clock read on begin/end)
- enabled bounded 64-deep LIFO stack, cross-carrier and stale token denial
- end exactly once even if host recording is disabled between enter/exit
- continuation retirement draining enabled scopes once in LIFO order
- strict UTF-8 operation validation (1..256 bytes, no unpaired surrogates)
- host-only off/on/off/on recording toggle; no implicit network egress

The new `NativeTelemetryScopesTest` defines **11 host Java JUnit tests**
for lifecycle, limits, cancellation-style retirement, cross-carrier rejection,
worker-thread migration, and toggle accounting. **These tests are currently
UNEXECUTED in source CI**; they are not actual .atto guest/scheduler tests.
The current compiler `using` lowering **still uses a guest-declared nominal
`SpanScope` mock** and is NOT wired to this native lifecycle primitive.
A public Java API marked `FromTrustedHost` is not itself a permission
boundary for arbitrary host integrations; enforce real controller authority
before any live tracing deployment.

### Required integration follow-up (P0)

1. Bind the parsed compiler-owned `using` marker to an unforgeable native
   `SpanToken` and compiler-issued begin/end, not to a guest-defined
   `telemetry.SpanScope` constructor or guest-overridable `close_scope()`.
2. Treat `SpanToken` as opaque; no projection into generic guest objects,
   messages, closures, returns, or persistent fields. Observers need typed
   compiler/runtime dispatch rather than arbitrary method overrides.
3. Attach the `Carrier` to each source Task/actor continuation and retire it
   on cancellation/actor death; only then relax the current async/actor gates.
4. Install authorized host-only probe controller and Truffle tags; do not
   equate the standalone toggle unit test with same-process Atto JIT attach.
5. Obtain exact-head GraalVM execution on an independently approved carrier,
   with all **34 required non-skipped JUnit cases**. Keep draft until this
   gate and runtime provenance review pass.
