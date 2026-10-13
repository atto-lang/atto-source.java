# Experimental NIO Future reactor — nonblocking substrate

`NativeNioFutureReactor` bridges nonblocking JDK NIO sockets to Oreslang's runtime-owned `OresFuture`, without executing guest callbacks on the reactor. A dedicated daemon **host reactor**, never an actor or ROOT carrier, waits for selector readiness; accept/read/write operations submit bounded commands and produce futures immediately.

This is **not yet guest-visible std/net**, is loopback-only, and does not replace `NativeHttp` or the blocking-style `AsyncNativeIo` implementation. There is no production claim or HTTP benchmark yet.

## Current proof
- TCP accept/read/write use nonblocking channels; ready-with-no-data does not occupy guest carriers and stalled reads do not block other connections.
- Fixed limits: maximum live connections, maximum pending futures/commands, per-call 64KiB byte cap, at most one read and one write pending per connection.
- Single owner reactor serializes socket readiness, registration, and close. Caller-owned mutable write arrays are snapshotted before enqueue.
- `OresFuture` completions only transition future state and its runtime subscription layer; no Oreslang guest code runs from socket callbacks.
- Context-level `close()` only wakes the selector, never waits on it. Outstanding reads, writes, accepts are failed on shutdown and pending counters reclaimed. Explicit `Connection.closeAsync()` enqueues closure on the owner reactor.
- Optional numeric measurements: peak future occupancy, max host command queue delay, outstanding futures. No blocking logging or synchronous output.
- **Cancellation not yet admitted** on individual accept/read/write futures: `cancel` returns false rather than claiming rollback of already consumed/written bytes. Connection close is the explicit asynchronous abort. Do not advertise cancellation until a single-winner kernel admission/completion contract exists.
- No selector, ByteBuffer, SocketChannel or host object crosses a guest actor boundary; these types are internal only.

## Remaining before std/net
- Supervisor-only listener authority, capability checks and owned opaque guest endpoint IDs; all public orchestration/source API belongs in `std/net.ores`.
- Single-winner cancel/deadline vs read/write/close, fd generation reuse fences, host reactor lifecycle tied to `OresContext`, portable resolver/connect and native LLVM parity.
- Guard against any implicit blocking `AsyncRuntime.await` fallback in guest network methods. Actor continuations must suspend; ROOT carries no socket syscall or selector wait.
- Non-loopback bind authorization, TLS, HTTP framing, high-load fairness, byte-budget rate control and kernel readiness event queue tracing.
- Exact-source CI and Native Image AOT/hybrid proof; end-to-end Oreslang HTTP throughput is still unmeasured.

The separate `NativeNioTcpKernel` remains the minimal selector proof. Eventually consolidate it with this Future-based reactor rather than exposing two duplicated native APIs.
