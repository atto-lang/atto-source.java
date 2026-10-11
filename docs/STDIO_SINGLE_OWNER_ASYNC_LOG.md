# One-owner asynchronous stdout integration (stacked on PR #538)

This change implements `stdio.stdout.log_async(...args)` and `stdio.flush_async_logs()` on the **existing** `StdChannels.output()` rendezvous channel and its sole context-owned output pump. It must not be merged as an alternative to or alongside `AsyncStdoutLog` or `ProcessChannels` workers from draft PRs #539/#540.

- `log_async` concatenates arguments and one LF, performs capability checking and the bounded frame check, then registers a scheduler-friendly channel write. It returns `Future<void>` that settles at channel admission; it does **not** claim remote/durable delivery.
- `await stdio.flush_async_logs()` writes a zero-byte marker to the same FIFO channel. The pump consumes that marker only after printing and flushing every preceding admitted channel frame. Therefore the barrier covers earlier channel writes and async logs, not fsync or external readers.
- The channel has bounded pending registrations and at most one output frame in flight. A slow or broken sink cannot silently accumulate an unbounded log queue. Admission, closure and sink errors propagate through native channel futures.
- Both APIs check the **current** actor's STDOUT capability, including when the descriptor is cached or sent between actors.
- The legacy synchronous `stdio.stdout.log`, `stdio.println`, and `stdio.stdout.write` facades still write directly to `context.output()`; global FIFO ordering **across legacy direct calls and channel calls is not yet proven**. The remaining single-sequencer migration belongs in [#609](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/609). Do not claim full mixed-path parity yet.
- This is an API/typing and pump-integration increment, not full Rx/WHATWG Streams conformance or asynchronous defer capture support.

An accepted async call that is not awaited may be cancelled or interrupted during context teardown. For explicit drain before return use:

```oreslang
pub async routine main(): void {
  await stdio.stdout.log_async("worker ready");
  await stdio.flush_async_logs();
  return;
}
```

Review admission and flush ordering, broken-pipe propagation, actor capability laundering, cancellation and linked-program close separately, then verify exact source Git tree on JVM, GraalVM AOT/hybrid, and real Unix pipes.
