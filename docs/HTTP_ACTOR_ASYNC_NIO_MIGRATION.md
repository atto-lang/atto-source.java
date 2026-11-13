# HTTP actor transport: asynchronous NIO migration gate

Status: DESIGN / NOT IMPLEMENTED. The Java implementation in
`src/main/java/dev/oreslang/runtime/NativeHttp.java` presently uses
`com.sun.net.httpserver.HttpServer` plus a virtual-thread I/O executor.
That implementation offloads blocking JDK socket APIs from actor turns,
but is **not** a selector-based, fully nonblocking socket transport.
No throughput or five-backend parity claim should treat it as NIO-complete.

## Non-negotiable runtime contract

- Keep the existing guest HTTP API and actor mailboxes as `Channel<T>`.
  Admission follows the configured request/message quotas; bounded
  channels expose both a nonblocking try-send and a suspending send.
- The reactor owns socket readiness, not guest actor execution.
  All `accept`, `read`, `write`, flush and close operations on the
  network path use `ServerSocketChannel`, `SocketChannel`, and
  `Selector` in nonblocking mode. No socket `InputStream` or
  `OutputStream` operations run on actors, scheduler lanes or reactors.
- Selector threads may run bounded framing/state transitions, never
  invoke guest code, execute a user callback or wait on an actor Future.
  Actor work runs only when its message is accepted by the runtime
  mailbox and wakes on async completion.
- Connection output is a queue of bounded immutable buffers. A pending
  write owns a lease until all bytes have drained or cancellation
  irrevocably revokes the lease. OP_WRITE is enabled only while unsent
  bytes remain, preventing a busy-spin loop.
- A request is not retired merely because its HTTP response or actor
  `done()` Future was published. Preserve the dispatch-handoff lease,
  request completion callback publication, actor mailbox seal and
  physical carrier exit receipts required by Java PR #2884.
- All reactor events are generation-bound. Stale readiness keys and
  callback completions cannot write to a recycled connection, awaken a
  replaced actor or spend a second HTTP admission credit.

## Implementation slices (separate reviewed PRs)

1. **Transport seam:** Extract a private `HttpTransport` contract around
   listener bind, request headers/body, response framing, abort and close.
   Keep the existing JDK transport as a reference implementation; avoid
   exposing `HttpExchange` or mutable buffers to Atto guest code.
   Preserve the existing public `http.listen`, `http.handler`,
   `http.claim` and exchange ownership semantics.
2. **Plaintext reactor:** Introduce a bounded selector-loop ownership
   model using nonblocking `ServerSocketChannel` and `SocketChannel`.
   Add length-delimited, incremental HTTP/1.1 parsing with no blocking
   reads. Enforce an explicit per-connection read buffer maximum, body
   size limit and output high/low watermarks before enabling read interest.
   Close or apply backpressure deterministically when admission is full.
3. **Response state machine:** Incrementally flush queued response buffers
   on writable readiness, correctly preserve partial writes, terminal
   cancellation and single-status-header emission. All completions are
   delivered to runtime Futures on safe host dispatchers, never under
   selector, listener, State or actor lifecycle locks.
4. **Protocol/security parity:** Reject conflicting `Content-Length`/
   `Transfer-Encoding`, ambiguous request framing, invalid URI escapes,
   unsupported upgrades, slowloris headers, oversized trailers, and
   excess pipelining. Explicitly define keep-alive, chunked framing,
   half-closed peers, backpressure and timeout behavior before enabling
   any protocol mode. Use a nonblocking `SSLEngine` state machine for
   HTTPS rather than a blocking TLS socket.
5. **Shutdown bridge:** Seal listener/worker admission, cancel selector
   keys, close outstanding channels, join selector owned tasks, retire
   responses/dispatch leases, and only then inspect actor
   `physicalCarrierExitPending`. No successful drain may precede the
   last accepted callback, actor carrier, or socket-ownership receipt.
6. **Conformance/performance gate:** Run equivalent fixtures through the
   old and new transports on the same JDK/hardware profile. Separate
   parser throughput, HTTP round-trip latency, host reactor wakeups,
   actor mailbox overhead and shutdown quiescence costs. Require actual
   request/response byte parity and consistent error status codes.

## Executable acceptance matrix

| Scenario | Required assertion |
| --- | --- |
| 1, 32, 128 concurrent keep-alive requests | No overlapping actor execution; no leaked permits |
| Partial frame on each read | Request parses once, with bounded retained buffer |
| Partial response write and client half-close | No duplicate headers or stale OP_WRITE wakeups |
| Channel capacity exhausted | `try_send` rejects immediately; awaited send suspends without parking a carrier |
| A shuts down while B uses the same Handler | A drains only A-owned actors; B retains/reuses its distinct worker |
| Unpooled actor terminal callback held | DONE does not release request admission before final carrier exit |
| Force-close during pending body write | One terminal owner, bounded completion, no use-after-recycle |
| Slow client / large body | Enforced memory and deadline budgets, no unbounded virtual threads |
| TLS delegated task slow/stalled | No selector blocking; terminal failure on deadline |
| Exact-head CI under JDK 25/GraalVM | Executed steps, Surefire XML and linked source commit SHA |

## Observability and measurement

Use core/std-lib logging and `CorePerf` / `CoreDebug` only when enabled,
with low-cardinality metrics. Instrument selector queue depth, read/write
interest transitions, retained request/response bytes, mailbox admission
waits, number of stalled response writers, accepted/rejected/completed
requests, actor-carrier exit latency, forced-close counts, and shutdown
timeout reasons. Disable per-request stack capture and allocation-heavy
logging on the benchmark path. Compare equivalent request bytes, connection
counts, compression/TLS settings and offered concurrency against a baseline
before claiming performance improvement.

## Current security and execution restrictions

The source organization has experienced GitHub Actions failures before
runner assignment. A zero-step workflow is NOT test execution. Keep the
existing draft changes unmerged until JDK 25/GraalVM compilation, guest
loopback and reactor stress tests execute. Private source must not be
transferred to an unrelated alternate organization without the approved
protected-main, exact-snapshot, destination-ID and independent-review gates
in `docs/security/CI_PRIVATE_SOURCE_MIRRORING.md`.
