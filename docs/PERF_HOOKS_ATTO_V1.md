# AttoLang Java/Graal backend: .atto performance hooks (candidate)

This backend owns `stdlib/perf.atto` and `stdlib/perf_ids.atto` independently
of LLVM/.NET/Wasm; the shared build-time numeric registry lives in
`atto-common-source/contracts/perf-probes-aot-v1.json`.

`Perf.begin_ns(enabled)` returns zero without reading the clock when off.
`Perf.Buffer` is owner-local and bounded; recording to a full buffer drops
events with a saturating count. It does not log, await, do I/O or start a thread.
`record_span` is a scope-exit hook and accepts a stable probe id, explicit
trace/span/parent identifiers, a start stamp and numeric outcome.

```atto
// Conceptual usage inside an actor-owned serial context:
val perf_enabled = true;
val started = Perf.begin_ns(perf_enabled);
// ... operation, with compiler-generated exit hook on all terminal paths ...
val accepted = perf_buffer.record_span(11, 12, 0,
                                      PerfIds.actor_user_mail_queue(), started, 0);
```

The compiler's future typed instrumentation pass must insert paired entry/
exit calls safely on returns, exceptions, cancellation and continuation
boundaries, or drop the probes completely for static OFF. JIT may specialize
those sites without changing IDs or adding runtime code generation. Existing
`Trace` and `CorePerf` remain intact; no silent replacement of phase timing
in the open Java actor/scheduler PR.

**Evidence:** the accompanying Node test is source-contract validation only,
not executed Atto guest tests or JIT/AOT parity. Guest import/linking, proper
generated cleanup paths and strict Native Image AOT remain outstanding.
