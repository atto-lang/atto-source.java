# Experimental NIO Future reactor — nonblocking substrate

`NativeNioFutureReactor` bridges nonblocking JDK NIO sockets to Oreslang's runtime-owned `OresFuture`, without executing guest callbacks on the reactor. A dedicated daemon **host reactor**, never an actor or ROOT carrier, waits for selector readiness; accept/read/write operations submit bounded commands and produce futures immediately.

This is now exposed through a **restricted native Oreslang `stdlib/net.ores` module** using `net_core.listen_loopback`, `net_core.accept`, `net_core.read`, `net_core.write`, and async close. Listener and connection handles are opaque, capability-gated, supervisor-only and non-sendable to actors until ownership-transfer implementation is validated. It is loopback-only and does **not** replace `NativeHttp` or the existing blocking-style `AsyncNativeIo` client implementation. There is no production claim or HTTP benchmark yet.

The first compiled Oreslang guest integration is verified: a `.ores` TCP server listens, accepts an external Java client, reads 4 bytes, writes them back, and closes through futures. Exact-tree CI [#38336641716](https://github.com/zed-pkg-test/java-app/actions/runs/38336641716) passed 16 selected tests. This proves an experimental runtime protocol boundary, **not** LLVM parity, HTTP/1.1, or nonblocking actor execution of guest socket methods. Imported Oreslang module call results currently require explicit `Future<T>` annotations before `await`.

## Current proof
- TCP accept/read/write use nonblocking channels; ready-with-no-data does not occupy guest carriers and stalled reads do not block other connections.
- Fixed limits: maximum live connections, maximum pending futures/commands, per-call 64KiB byte cap, at most one read and one write pending per connection. Context-wide limit of **16** reactor listeners (including in-progress opens) bounds platform thread growth; registry entries are removed only when close settles.
- Single owner reactor serializes socket readiness, registration, and close. Caller-owned mutable write arrays are snapshotted before enqueue. Every admitted operation is registered by Future identity and force-failed on abnormal selector termination, even when its queued command never reaches kernel registration.
- `OresFuture` completions only transition future state and its runtime subscription layer; no Oreslang guest code runs from socket callbacks.
- Context-level `close()` only wakes the selector, never waits on it. Outstanding reads, writes, accepts are failed on shutdown and pending counters reclaimed. Explicit `Connection.closeAsync()` enqueues closure on the owner reactor. Admission and command enqueue are atomic against shutdown; 40 concurrent close/admission iterations verify no stranded futures or counter leaks. An operation releases admission before publishing its future's settled state.
- Optional numeric measurements: peak future occupancy, max host command queue delay, outstanding futures. No blocking logging or synchronous output.
- Accept/read cancellation may win before a consuming kernel syscall is admitted. Cancellation is refused once consumption begins; a zero-progress readiness attempt reopens cancellation. Guest byte-array read futures forward cancellation to the same underlying operation. Writes remain non-cancellable; no rollback is promised for emitted bytes. Connection close is the asynchronous abort.
- No selector, ByteBuffer, SocketChannel or host object crosses a guest actor boundary; these types are internal only.

## Remaining before production std/net
- Upgrade the experimental supervisor-owned loopback source API to general capability-scoped binds and connects, with safe transferable opaque guest endpoint IDs and actor-owned state. The current `std/net.ores` is a small real-language entrypoint, not the full networking library.
- Single-winner cancel/deadline vs read/write/close, fd generation reuse fences, host reactor lifecycle tied to `OresContext`, portable resolver/connect and native LLVM parity.
- Guard against any implicit blocking `AsyncRuntime.await` fallback in guest network methods. Actor continuations must suspend; ROOT carries no socket syscall or selector wait.
- Non-loopback bind authorization, TLS, HTTP framing, high-load fairness, byte-budget rate control and kernel readiness event queue tracing.
- Exact-source CI and Native Image AOT/hybrid proof; end-to-end Oreslang HTTP throughput is still unmeasured.

This audited integration carries only the Future reactor; the unused separate `NativeNioTcpKernel` prototype was not imported. HTTP protocol policy remains in `stdlib/http.ores`. Command turns process at most 64 queued submissions before serving readiness; connection admission is capped at 65,536. These are hardening bounds, not a production throughput claim.
