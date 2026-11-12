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

The parser recognizes the scoped expression as an ordinary `telemetry.span(...)`
call. A minimal native `stdlib/telemetry.atto` module **now exists** and is
registered at `ores.std/telemetry` (with legacy `std/telemetry` alias).
It supplies non-recording `span()` and `span(name)` factories, a
`Span.is_recording()` query and idempotent `Span.end()` method.
The source fixture and `NativeTelemetryScopeGuestTest` that compile and run
this module have been authored but **NOT EXECUTED**.

The true recording `span.attribute` API, trusted `TelemetrySpanScope` type,
automatic logical active-context activation and OTLP exporter are still
**NOT IMPLEMENTED**. Separate `UsingTelemetrySpanScopeTest` cases use a small
user-defined guest `Telemetry` class to exercise lexical cleanup semantics,
not OTel export.

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
compiler instrumentation proof. The gate is now **wired to the checked callable dispatcher**, which records
numeric call admission durations in a fixed-size, per-isolate
`AutoTraceRecorder`. It does **not** yet trace async task completion,
emit OTel spans, or provide a trusted, externally accessible runtime
reconfiguration service. The numeric recording gate is controlled by trusted
`--ores-telemetry=calls` at startup. An explicitly requested
`--ores-telemetry-receipt=stderr` emits a **cold shutdown-only diagnostic
receipt**, never synchronous guest hot-path output. Its site IDs are an
internal provisional hash and **not** cross-backend stable public IDs.

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
4. Extend the native non-recording `ores.std/telemetry` module into a
   typechecked `TelemetrySpanScope` recording implementation, plus runtime
   context capture/restore; then run real HTTP/actor/HTTP tracing with an
   external OTLP receiver. Until then the syntax alone is not distributed tracing.
5. Repeat the same guest on LLVM; its currently narrower native compiler
   needs separate parser, ownership, unwind and actual nonblocking HTTP work
   before matching claims can be made.

Related contracts:
[shared zero-code tracing](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/feat/atto-instrumentation-contract-v1-20261112/docs/AUTO_INSTRUMENTATION_AND_DISTRIBUTED_TRACING_V1.md)
and [shared v1 instrumentation ABI](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/feat/atto-instrumentation-contract-v1-20261112/contracts/v1/instrumentation-api-v1.json).


## Additional Java/Graal implementation slice: immutable context and HTTP

- `TraceParent` is a strict **W3C v00-only**, lowercase-hex,
  nonzero 16-byte trace ID / 8-byte span ID value. Roots/children use
  `SecureRandom`, preserve the sampling flag and reject malformed headers.
  Parsing does not presently support future W3C versions or `tracestate`.
- `TaskContext.pushTraceParent()` stores the immutable parent inside the
  task's already heap-retained logical context chain. `SourceTask` saves and
  restores this same chain across suspend/resume; OS/JVM thread-locals are
  caches for the current turn, not the authority.
- `ActorRuntime.MessageEnvelope` now includes a private immutable
  `TraceParent` field *outside* the user message graph. Both synchronous
  send and asynchronous mailbox admission capture the sender's value **before
  any asynchronous waiter callback**. USER dispatch installs the captured
  context, then restores its carrier's prior state on normal/error exit.
  Propagation is disabled by default and excluded for untrusted recipients.
  This has not been proven on the exact Java compiler branch.
- `OutboundHttpRequest.request()` captures the parent at guest admission,
  after the existing network permission check, and adds a new valid child
  `traceparent` HTTP header to an immutable list handed to the physical I/O
  worker. It rejects duplicate user-supplied tracing headers and respects the
  established 100-header/32768-byte request limits.
- **Still missing:** HTTP SERVER automatic span creation and extraction from
  inbound headers, semantic CLIENT/ACTOR spans, OTLP/HTTP export from a bounded
  native Atto telemetry actor, safe `tracestate` handling, actor-tenant trust
  policy, compiler-generated full function body probes, and actual Java/LLVM
  executed guest trace receipts. The original Java HTTP frontend still relies
  on a host transport rather than the proposed native `std/http` implementation.

Tests authored on this branch:
`AutomaticCallProbeGuestTest`, `AutoTraceRecorderTest`,
`TraceParentTest`, `TraceTaskContextTest`,
`ActorTraceContextPropagationTest`, and
`OutboundHttpTraceInjectionTest`. None has an exact-head nonzero-step
passing CI receipt. **Do not present a source-level match as runtime pass.**
