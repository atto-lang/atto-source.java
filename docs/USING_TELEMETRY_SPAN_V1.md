# AttoLang `using telemetry.span(...) as span { ... }` — Java v1

Status: **implementation in draft PR**, not a certified shipping feature.
This change is a stacked dependency on trace-context propagation PR #2990.
Do not infer Java/Graal guest execution from parser admission or zero-step CI.

## Canonical source surface

```atto
pub async fnc checkout(): int {
  using telemetry.span("checkout") as span {
    span.attribute("operation", "checkout");
    val int payment = await charge();
    return payment;
  }
}
```

`using telemetry.span() as span { ... }` is also admitted. The current
no-argument default label is `"span"`; automatic source-symbol naming is
future compiler work. `telemetry.span` is a **contextual compiler intrinsic**
in this initial Java slice, not a guest function/module that can be replaced
or monkey patched. Proper `std.telemetry` module wiring, `use` imports and
public API spelling across all backends require a follow-up PR.

The bound `span` permits `attribute(key, scalar)`, `trace_id()`,
`span_id()`, and `recording()`. Direct return/rebinding of the scope
handle is rejected by the checker. The runtime also rejects calls on closed
span handles and foreign-task scope access. Complete transitive no-escape
analysis for arbitrary closure captures/aggregates remains a conformance gate.

Existing `using request.borrow() as view { ... }` stays separate and
fail-closed. Its lifetime rules still forbid unsafe suspension. A span has
immutable trace identity and an owned lexical scope, never a request buffer
loan.

## Async and lexical lifecycle

- The compiler stores the using scope as a dedicated
  `Ast.UsingTelemetrySpanStmt`, not an expression statement or implicit
  copyable scope guard.
- The name expression is typechecked `String` and evaluated once.
- Synchronous bodies use a Java lexical finally/try-resource boundary.
- Suspendable source tasks use the heap-owned `SourceCleanupFrame` path.
  The span is started once, remains active through await/cooperate and
  carrier migration, and is closed only after all nested body cleanup.
- For cancellation, the nested body cleanup runs first and the outer span's
  cleanup frame is then unwound by the authenticated scheduler cancellation
  turn. The span close is idempotent; a terminal Future on another thread
  must never close it.
- A failed physical task cleanup may be quarantined. No claim of a clean
  closed span is made until its actual close path executes.
- The scheduler from PR #2990 captures and reinstalls `TraceContext`
  per **logical task**. A Future producer and the next task on a reused
  physical carrier do not receive the suspended span's context.
- Nested spans use fresh IDs under the same trace ID and restore the parent
  on lexical completion. The current slice does not implement span links,
  tracestate, W3C remote injection/extraction, or OTLP export.

## Runtime control (existing sites only)

Recording is OFF by default. The trusted host may call:

```java
TelemetrySpanScope.enableExistingSites(10, 300_000, "service.");
// ... selected pre-existing lexical sites are active for up to five minutes
TelemetrySpanScope.disableExistingSites();
```

The API validates a sample percentage, a bounded monotonic expiry, and a
literal name prefix. No timer thread or exporter is started by this toggle.
Unsampled or disabled spans perform no reporting I/O, preserve inherited
trace context, and do not change guest control flow. A bounded attribute
budget and sensitive-key rejection prevent accidental secret-bearing
diagnostics. `startedCount/endedCount` expose only aggregate host counters.

The toggle does **not** create new probes in functions that were not
compiled with `using` or future compiler-inserted hooks. Even enabled
recording currently retains no exporter-ready span events: a bounded
per-actor collector and asynchronous OTLP adapter remain follow-up work.

## Why live JIT reinstrumentation is NOT in this patch

The present Java/Graal frontend has one `OresEvalRootNode` and an evaluator
that directly interprets many guest functions in Java, including behind a
`@TruffleBoundary`. There is not yet a reliable per-callable Truffle
instrumentable-node/tag tree with safe code-version swaps.

An operator request for function-level JIT instrumentation should therefore
fail explicitly as unsupported today, not imply that
`enableExistingSites` performed recompilation. Real implementation path:

1. Lower each compiler-known callable and async continuation boundary into
   tagged, instrumentable Truffle AST/IR with stable IDs and source spans.
2. Maintain per-callable instrumentation policy generations and side-effect-
   free disabled fast paths.
3. On a trusted operator request, authorize and validate target IDs, generate
   a new instrumented code version, invalidate inlined callers, deoptimize
   safely at Truffle safepoints, and direct new invocations to that version.
4. Existing suspended continuations stay with their original code generation
   until a proven resume/deopt transition. Never rewrite borrowed frames,
   actor-owned stacks, or cancellation state in place.
5. Fence actor generations, JIT policy generations and stale continuation
   wakeups; enforce per-tenant code-cache, recompile CPU and telemetry quotas.
6. Emit bounded span events from runtime/compiler probes into a nonblocking
   collector. No export or instrumentation callbacks execute inside
   `critical`, carrier admission locks or actor scheduler locks.
7. Compare guest results and trace causality with/without runtime activation;
   include Native Image/AOT builds where runtime JIT may be unavailable.
8. For pure AOT (LLVM/.NET NativeAOT/Wasm), support pre-emitted probe sites
   and host-only runtime toggles; new sites require verified code-generation
   loading or a redeploy, not magic runtime patching.

No reflective method wrapping, Java agent, bytecode replacement,
monkey patching, runtime declaration synthesis, or unsolicited host code
is allowed.

## Acceptance tests and release gates

New Java tests:
- `TelemetryUsingSpanLanguageTest`: compiler AST, checked guest execution,
  nested spans, await/cooperate, default-off behavior and negative admission.
- `TelemetrySpanScopeTest`: host toggle, context restoration, bounded
  attributes, wrong-task close rejection, and idempotent close.
- Existing `TraceContextSuspensionTest` in PR #2990 is the scheduler
  substrate. These tests MUST be run together on the exact reviewed tree.

Still required: execute real JDK21/25 Maven JUnit, Graal guest tests,
Native Image AOT, source cancellation-vs-completion races, compiled actor
guest shutdown/hot reload, LLVM/.NET/Wasm/Forge/JS parity, and live JIT
deopt/recompile tests once the relevant backend is implemented.

When GitHub Actions reports `steps=[]`, that is runner admission failure,
not test execution evidence. Keep PR draft until the above gates are met.

Relevant repo docs:
- `docs/USING_BORROW_SCOPES.md`
- `docs/TASKS_BEYOND_GOROUTINES_V1.md`
- `docs/aot-jit-language-contract.md`
- `docs/TRACE_CONTEXT_SUSPENSION_V1.md` (PR #2990)
