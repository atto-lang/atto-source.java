# AttoLang scoped telemetry spans and runtime activation (experimental)

**Status: Java/Graal feature-branch implementation in progress.** The Java
parser now admits `using telemetry.span(...) as span { ... }` and lowers it
into an ordinary Atto-local binding with `try { ... } finally { span.end(); }`.
Corresponding JUnit real-guest execution tests have been authored for normal
fallthrough, nested scopes, return, failure and suspension/await. **They have
not yet been executed on the exact branch head.** Do not claim production
effect-safe semantics, native OTLP export or full backend parity.

## Source syntax

```atto
import module telemetry from "ores.std/telemetry";

pub fnc checkout(Order order): Result<void, Error> {
  using telemetry.span("checkout") as span {
    span.attribute("order.type", order.kind);
    return process_order(order);
  }
}
```

This is the *target API*: `ores.std/telemetry`, `span.attribute`, automatic
logical active-context activation and the associated `Span` protocol are **not
implemented** by the parser patch. The expression is parsed as an ordinary
`telemetry.span(...)` call. An executable test uses a small user-defined
`Telemetry` guest class with `span(...)`/`Span.end()` to exercise the
**language scope semantics only**. It does not emit an OTel span.

The parser must not reinterpret `using resource.borrow() as view` as a span
scope: borrowed views retain their distinct AST node and fail-closed lifetime
checks. It also retains ordinary function calls named `using(...)` and
signature-level `using` effect clauses.

## Semantic requirements before production

- `using` evaluates the span factory once, binds its result for one lexical
  scope, then calls cleanup at most once even when exiting by return, throw,
  raise, panic, cancellation or suspended task teardown. Current lowering
  inherits the implemented `try/finally` behavior for the cases in the test.
- **Do not infer effects away:** calling a user-defined `end()` may throw or
  suspend. The future type checker must require a trusted
  `TelemetrySpanScope` cleanup protocol with non-suspending, no-throw,
  non-blocking completion so telemetry errors never mask business exceptions
  or panic CONTROL messages. Current AST lowering has not proved that.
- Creating a `Span` and activating it in logical context are distinct
  operations. The `using` construct should activate context on entry and
  restore the previous context on exit; the existing `Span.end()` desugaring
  does not yet implement that activation.
- A live mutable span remains actor-owned. Only immutable trace context
  crosses `await`, channel and actor envelopes, with incarnation and tenant
  checks. No host thread-local identity serves as semantic authority.
- If instrumentation is disabled, auto-generated call sites must avoid
  evaluating expensive attributes, allocation, clocks or output; manual
  explicit `telemetry.span()` still executes its user-written expression.

## JIT reconfiguration: allowed subset, not magical new call sites

A new internal `TraceProbeEpoch` uses a **per-domain Truffle Assumption**.
Trusted configuration replaces a versioned policy and invalidates the old
assumption; optimized call targets depending on it may deoptimize and then
recompile to observe the new mode. This mechanism does **not** rewrite
bytecode, monkey patch guest objects, attach Java agents or create new
compiler sites.

The policy tests cover OFF default, `BOUNDARIES`/`CALLS`/`FULL` coverage
ceilings, independent domain policies, sealed profiles and no-op updates.
These are **host policy unit tests**, not a full Truffle optimizing-JIT or real
compiler instrumentation proof. The gate has not yet been wired to the
call-entry/exit and stdlib HTTP/actor hooks.

A trace-capable JIT/AOT build can change the **recording mode** without
redeployment only for probes which were compiled in. If we want to
*introduce* new entry probes after startup, we must retain validated typed
source/MIR and compile new versions through a governed JIT with
versioned dispatch and quiescence. Java/Graal JVM may support this in
principle, but it is not implemented here. In sealed Graal Native Image,
LLVM AOT, .NET Native AOT and freestanding Wasm without such a JIT, new
uncompiled sites **require new code**; precompiled dormant probes can still
be enabled.

## Immediate actual guest gates

1. Run `UsingTelemetrySpanScopeTest` with the exact compiler source branch.
   Confirm its emitted `try/finally` cleanup and no borrow regression.
2. Run `TraceProbeEpochTest` with JDK 25, including deopt/reoptimization
   instrumentation (current unit tests only verify the policy results).
3. Preserve all previous `NativeTraceStdlibTest` execution checks and run
   real `.atto` tests under the optimizing Graal runtime, not interpreter
   fallback.
4. Implement typed `TelemetrySpanScope` and a native `ores.std/telemetry`
   class, plus runtime context capture/restore, then run real HTTP/actor/HTTP
   tracing with an external OTLP receiver. Until then the syntax alone
   must not be described as distributed tracing.
5. Repeat the same guest on LLVM; its currently narrower native compiler
   needs separate parser, ownership, unwind and actual nonblocking HTTP work
   before matching claims can be made.

Related contracts:
[shared zero-code tracing](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/feat/atto-instrumentation-contract-v1-20261112/docs/AUTO_INSTRUMENTATION_AND_DISTRIBUTED_TRACING_V1.md)
and [shared v1 instrumentation ABI](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/feat/atto-instrumentation-contract-v1-20261112/contracts/v1/instrumentation-api-v1.json).
