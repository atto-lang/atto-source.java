# Process-owned standard I/O channels (draft)

## Canonical source API

```ores
process.stdout === stdio.out;    // same context-owned channel object
process.stderr === stdio.err;    // same object
process.stdin  === stdio.in;     // same object

// Channel<String> text-chunk output with existing cooperative backpressure:
await nb writech stdio.out, "hello";
await nb writech process.stderr, "diagnostic";
await stdio.flush_async_logs();

// Channel<String> line-framed input:
val String line = readch process.stdin;
val ReadableStream<String> stream = stdio.in.stream();
val reader = stream.get_reader();
val String next = await reader.read_async();
reader.release_lock();
```

The aliases are properties, not channel factories. They all refer to one process-context-owned handle per direction. `std.process.stdout` resolves to the same process facade as `process.stdout`.

This draft implements **actual runtime subclasses** of `ChannelRuntime.Channel<String>`, not merely objects shaped like channels. Read/write and `SelectCase` registration reuse the existing single-arbitration channel runtime. Runtime operations enforce read-only stdin, write-only stdout/stderr, and current capability admission. Guest code cannot close process-owned channels. Parent-context ownership is retained; a guest actor cannot transfer the handle into an untrusted actor without the ordinary actor capability rules.

### Output and flush

A dedicated worker drains each output channel into the embedding context's `Env.out()` or `Env.err()`. Worker threads may block on slow sinks; Oreslang actor carriers do not. Each channel has an 8192-chunk bounded buffer; a `try writech` probe rejects instead of blocking, while `nb writech` can register bounded-channel backpressure. Like regular channels, many unawaited pending writes can still accumulate registrations; **callers must bound outstanding Futures**.

`stdio.stdout.log_async(...)` formats one line and tries to enqueue it to **the same stdout channel** as `writech stdio.out`. Admission is immediate and rejects a full buffer. `stdio.flush_async_logs()` inserts a private FIFO marker into stdout and completes only after preceding accepted stdout chunks are written and the writer flushed. It does not wait for stderr, external pipe readers, network collectors, or durable storage. Mixed legacy direct PrintWriter operations do not have a strict interleaving order with channel writes.

Context teardown closes the process-owned channels, drains accepted output before `OresVM` shutdown, and surfaces drain failures/timeouts to the embedding host. `std.process.exit` and linked CLI exits need dedicated end-to-end validation.

### Input and ReadableStream

The stdin adapter lazily starts a daemon input reader when the input channel is first resolved; blocking `readLine` runs on that dedicated worker, not on an Oreslang carrier. This is a **line-framed String channel**, not a faithful raw-byte/chunked replacement for POSIX stdin. EOF closes stdin, so existing channel closure semantics apply. VM shutdown interrupts the input worker without closing the embedding host's stdin. A host input source with an uninterruptible blocking read may retain a daemon until host input closes.

`stdio.in.stream()` exposes a pull-based `ReadableStream<String>` adapter. `get_reader()` claims an exclusive **per-view** reader lease; `read_async()` returns `Future<String>`, and `release_lock()` releases it without closing process stdin. At EOF, pending channel reads fail with channel-closed rather than yielding a `{done:true}` Web Streams result; that richer contract remains unimplemented. Multiple separately created views can still compete for the same underlying channel, not multicast.

### Observable integration (external userland)

The existing `ores-truffle-oreslang/rx-oreslang-channels` package defines `Observable<T>` and `Subscription<T>`. The present `from_channel(Channel<Option<T>>)` expects explicit `Some(...)`/`None` framing and **does not accept** the raw `Channel<String>` process stdin without an adapter. The preferred new API is `Observable.from_ch(stdio.in)`, with a raw-channel adapter that treats process channel closure as completion and never closes shared stdin when a subscription is cancelled.

Until that userland factory and static-class method syntax have executable proof, do not claim the requested `Observable.from_ch(...)` is supported. A module-qualified `rx.from_ch(...)` may serve as an intermediate implementation.

## CI admission

Do not merge this draft until exact-head Graal JVM/AOT/hybrid/isolate builds and execution tests pass, especially:

- alias object identity under both namespace spellings and named/typed channel variables
- stdout and stderr captured separately, including pipe backpressure and bounded queues
- readch/writech/SelectCase wrong-direction denial and capability enforcement
- stdin EOF, cancellation, slow/blocking embedded stdin and teardown
- flush ordering under simultaneous log_async/writech and explicit process exit
- overflow, timeouts, races, worker error propagation and no carrier blockage
- Observable adapter single-subscriber behavior, terminal framing and cancellation

The source contract is intentionally *provisional* until those checks run.
