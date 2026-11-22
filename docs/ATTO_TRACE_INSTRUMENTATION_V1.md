# Native AttoLang trace instrumentation: manual v1

**Status:** new guest API and guest execution regression test on a feature branch. Test execution and CI verification are **NOT EXECUTED**. This is a portable manual instrumentation boundary, **not** a completed distributed tracing SDK or automatic network tracer.

Canonical import: `import module Trace from "ores.std/trace";`. The legacy `std/trace` remains valid. The source of truth is `stdlib/trace.ores`, implemented entirely in AttoLang. No Java Truffle telemetry implementation is exposed as its public semantics.

```atto
import module Trace from "ores.std/trace";

pub routine compute_value(int input): int {
  return input + 2;
}

pub routine main(): void {
  val collector = Trace.buffer(128);
  val int started_ns = Trace.begin_span(true);
  val int result = compute_value(40);
  val bool recorded = Trace.finish_span(
    collector, 7, Trace.phase_function_call(), started_ns);
  stdio.println(result);
  stdio.println(recorded);
  stdio.println(collector.count());
  return;
}
```

`Trace.begin_span(false)` returns zero without reading the clock. `Trace.finish_span(..., 0)` returns false without recording; it also returns false on invalid identifiers or a full buffer. The collector is bounded and owned by one actor/supervisor, with explicit `dropped_count()` pressure reporting. For every instrumented operation, preserve the original return/error/cancellation semantics and call `finish_span` on all exits. The manual API does not enforce exactly-once completion.

When a capable native I/O implementation exists, wrap real I/O boundaries with `phase_http_client/server`, `phase_tcp_connect/read/write`, `phase_file_read/write`, `phase_actor_send/receive/await`. Do not falsely label a synthetic timer as evidence of actual TCP or file I/O. Reserved phase IDs are stable in the shared [instrumentation API v1 contract](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/feat/atto-instrumentation-contract-v1-20261112/contracts/v1/instrumentation-api-v1.json).

The design target is separate actor-local `Tracer`/`Span`/`Context` types, secure W3C IDs and header propagation, compiler-verified opt-in callable instrumentation, actual nonblocking I/O hooks, and bounded asynchronous OTLP export. None are delivered by this manual slice. Existing runtime `CorePerf`/`CoreLog`/`CoreDebug` and optional userland `oreslang-otel` remain independent. See `docs/NATIVE_NETWORK_IO_AND_TRACING.md` for existing networking caveats.
