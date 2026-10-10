# Async stdio and deferred cleanup (candidate)

## API surfaces

- `stdio.stdout.log_async(values...)`, `stdio.stdout.logList_async(sequence)`, `stdio.println_async(value)` and `stdio.stdout.println_async(value)` format immutable output records at the call site and return `Future<void>`. Awaiting that Future confirms the record was written/flushed by the host sink. Detached calls are allowed: the writer retains accepted records independently of Future lifetime.
- `await stdio.flush_async_logs()` is a per-context FIFO barrier for records **already admitted** when called. Concurrent later admissions are not covered by that barrier.
- `stdio.stdout.log_blocking(...)`, `stdio.stdout.println_blocking(value)`, `stdio.println_blocking(value)`, `stdio.print_blocking(value)`, and `stdio.stdout.write_blocking(value)` are explicit blocking source aliases. Existing unsuffixed stdio calls remain for compatibility. Synchronous writes still use the host writer lock and may briefly park an actor carrier; prefer async logging on actor request paths.
- Both paths require the existing STDOUT capability. They share the host-selected `PrintWriter` and cannot independently close `System.out`. The core flight recorder (`CorePerf`/`CoreDebug`) separately buffers numeric diagnostics and writes them on its JVM shutdown hook.

## Backpressure / shutdown

Each Oreslang context lazily creates a daemon writer after its first accepted asynchronous record. Its queue holds at most 1,024 accepted records of at most 65,536 UTF-8 bytes each. Admission is nonblocking; full queues, oversized records, closed writers, and detected sink failures **raise**, never silently discard logs. Async write and flush failure is also reflected in the relevant Future.

Normal `OresContext.close()` first closes admission and waits for the async drain, with a 5-second shutdown deadline, before closing the VM and hosted streams. Any writer failure or exceeded deadline surfaces as a shutdown failure. A deadline is a tradeoff: the process cannot guarantee both bounded exit and lossless output to a permanently blocked sink. `SIGKILL`, power loss, remote log ingestion, `fsync` durability, and non-cooperating host crashes are beyond an in-process stdio flush guarantee.

Ordering is FIFO per context for accepted `*_async` records. Mixed synchronous and async writes share a `PrintWriter` lock but **not** a single global order across producer threads. Synchronous multi-argument `log` now formats one atomic line before invoking the writer.

## Defer lowering

```ores
pub async routine main(): void {
    defer {
        await stdio.flush_async_logs();
    }
    stdio.stdout.log_async("hello");
}
```

The block is a zero-argument lexical closure registered for scope exit. Under the existing resumable source-task engine, its `await` continues through deferred-flow scheduling; return from the deferred closure does not return from the enclosing routine. Scopes execute deferred actions last-in/first-out.

```ores
pub routine main(): void {
    let x = "earlier";
    defer (|saved| -> {
        stdio.stdout.log(saved);
    })(x);
    x = "later";
}
```

A directly invoked inline block-bodied lambda in `defer` eagerly evaluates and binds its arguments at registration; the body runs at scope exit. Borrowed references or mutex guards must not escape into this deferred activation. Spreading or suspending during argument evaluation is rejected at runtime in this initial slice. Legacy `defer cleanup(expr)` remains lazy for compatibility.

These changes are undergoing compile, ownership, source task, native carrier and real sink validation. Do **not** infer production readiness from source review or zero-step CI runs.
