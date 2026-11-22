# Atto Java/Graal `stdlib/telemetry.atto` candidate

This is a native **Atto source** telemetry value model. It is not a Java OpenTelemetry SDK binding, an exporter, a privileged `SpanScope`, a verified guest import, or a certified running standard-library module.

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

## Test and security gates

1. Compiler-backed positive/negative tests for the pure Atto helpers and batch overflow/drop saturation; alias/capability forgery rejection; no source-defined `span` accepted as a trusted provider.
2. Exact-head JDK25/Graal guest `using` normal/return/failure/await/cancel/actor-restart execution, single logical span with no leak or double-end; disabled hot path must not read clock or allocate.
3. Live Truffle JIT attach/on/off/deopt with generated probe-site identity and current-generation preservation. Separately test dormant AOT activation and strict `none` denial.
4. W3C hex/header grammar negative vectors, context propagation through tasks/actors/network, and a real OTLP receiver capture under explicit egress permission.
5. Source tree SHA, nonzero CI runner steps, independent review and cross-backend receipts. **No such test or CI run is claimed by this source-only PR.**

### Compatibility / overlap disposition
This PR intentionally touches only a new .atto module and its documentation, and does not reimplement parser or runtime changes in Java #1661/#1662 or the instrumentation contracts in common-source #120/#121/#122. It must be reconciled with their trusted-lowering APIs before treating it as an importable stdlib.
