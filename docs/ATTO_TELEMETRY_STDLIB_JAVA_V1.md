# Atto Java/Graal `stdlib/telemetry.atto` candidate

This draft combines a native **Atto source** telemetry value model with a minimal Java compiler-owned scope observer integration. It is not a Java OpenTelemetry SDK binding, a live exporter, a proven linked guest module or a certified fully working stdlib.

- `core.trace` retains cheap timestamps, phase events and the bounded `Trace.Buffer` in `stdlib/trace.atto`.
- `Telemetry` adds W3C-v00 **field-level** admission, sampled/recording policy separation, immutable span records containing stable `operation_id` and `code_generation`, an actor-owned bounded batch, and dormant-probe admission.
- `trace_id_high/low` are signed 64-bit bit patterns for an unsigned 128-bit trace ID. Conversion to/from wire bytes and canonical W3C header validation are trusted adapter work, **not provided** by the field-level function.
- `SpanBatch` is only a value/queue candidate. Records constructed by guest code MUST NOT be treated as authenticated runtime span contexts. The real collector needs tenant/generation provenance, a nonblocking ring or mailbox, quotas, drop counts, safe shutdown and an independently authorized OTLP/HTTP or gRPC export capability.
- `sampling_decision` returns the three OTel decision codes (DROP/RECORD_ONLY/RECORD_AND_SAMPLE); propagation of a nonrecording W3C context remains independently controllable. Caller policy determines `is_recording`; sampled is not equivalent.
- `dormant_probe_admissible` only gates existing sites. Real **live JIT** instrumentation requires compiler-signed descriptors, IR provenance, tenant authority, safe deopt/recompile and in-flight generation fencing. Sealed AOT code with no probes cannot be remotely instrumented by a source helper.

## Target first-class scope spelling

```atto
use std.telemetry as telemetry;

pub fnc handle_request(): void {
  using telemetry.span("checkout") as span {
    span.set_attribute("cart.items", 3);
    span.add_event("payment_started");
    // await and cancellation need continuation-owned, exactly-once cleanup.
  }
  return;
}
```

This `using` form is a **compiler-owned intrinsic** as specified in [atto-spec#167](https://github.com/atto-lang/atto-spec/pull/167). No guest-defined `Telemetry.span` or `SpanScope.close_scope` is supplied here: doing so would permit provider impersonation. Current Java work [#1661](https://github.com/atto-lang/atto-source.java/pull/1661) and continuation work [#1662](https://github.com/atto-lang/atto-source.java/pull/1662) must be reconciled before enabling the import. The syntax above is a **target example**, not verified working code.

## Mainline compiler-owned observer integration

This draft also extends the **existing** Java/Graal trusted `UsingTelemetrySpanStmt`
lowering. The guest scope now admits both the original `span.attribute(key, value)`
and canonical `span.set_attribute(key, value)`, plus
`span.add_event(event_name)`. These are direct, ownership-checked calls,
not a user-defined `SpanScope`. Runtime event names must be 1..80 safe ASCII
characters; each recording span keeps at most 16 name-only events. Overflow
drops names without growing the collection and increments a saturating
`droppedEvents()` host-only counter. Event names containing sensitive-key
patterns are rejected. Disabled span scopes ignore the
observer payload, retain incoming trace context, and do not export.

The host-only `TelemetrySpanScope.eventsSnapshot()` returns an immutable
copy; no guest API exposes event storage. There is still no actual OTLP export,
full suspension/cancellation guest proof, or authorized live JIT patching.

The correct mainline compiled-guest syntax currently works **without** a
`use std.telemetry` import because `telemetry.span` is a contextual compiler
intrinsic. The canonical `use std.telemetry as telemetry` spelling shown above
still requires a separately verified trusted import resolver. The .atto
data-policy module is imported separately using
`import module Telemetry from "std/telemetry";` in existing source import
mechanisms. The two facilities do not manufacture authority for each other.

## Test and security gates

1. Compiler-backed positive/negative tests for the pure Atto helpers and batch overflow/drop saturation; alias/capability forgery rejection; no source-defined `span` accepted as a trusted provider.
2. Exact-head JDK25/Graal guest `using` normal/return/failure/await/cancel/actor-restart execution, single logical span with no leak or double-end; disabled hot path must not read clock and should eventually avoid all scope allocations (currently the disabled path still allocates a scope handle).
3. Live Truffle JIT attach/on/off/deopt with generated probe-site identity and current-generation preservation. Separately test dormant AOT activation and strict `none` denial.
4. W3C hex/header grammar negative vectors, context propagation through tasks/actors/network, and a real OTLP receiver capture under explicit egress permission.
5. Source tree SHA, nonzero CI runner steps, independent review and cross-backend receipts. **No exact-head compiler-backed pass is claimed for this draft.**

### Compatibility / overlap disposition
This branch now adds runtime/API integration on **current mainline**: it modifies `TelemetrySpanScope`, the existing type and ownership checkers, and the evaluator's already-present native-span dispatch. It **does not** replace the older overlapping Java #1661/#1662 proposals or their distinct compiler/continuation changes. The 5 newly authored JUnit methods (1 runtime-host, 3 compiled-guest scopes, 1 linked Atto stdlib source) are intentionally material gates but are NOT YET EXECUTED. The staged 18-assertion .atto fixture is also unexecuted.

Nearby work: [Java #1660](https://github.com/atto-lang/atto-source.java/pull/1660) supplies broader native instrumentation/OTLP candidate code; [Java #1682](https://github.com/atto-lang/atto-source.java/pull/1682) explores auto-call spans; [Java #1687](https://github.com/atto-lang/atto-source.java/pull/1687) explores JIT generation retirement; [Java #1760](https://github.com/atto-lang/atto-source.java/pull/1760) independently adds `perf.atto`. This PR does not claim merge readiness or replace those implementation lanes. Resolve overlapping runtime changes before any merge.
