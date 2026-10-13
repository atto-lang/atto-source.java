# Oreslang-owned nonblocking TCP/HTTP and tracing: migration contract

Status: design plus **implemented source-level Trace timing primitive**, NOT an implemented TCP listener, HTTP parser, asynchronous trace collector, LLVM backend, or production-performance result.

## Finding: current async facade is not a nonblocking networking implementation

- `NativeHttp` delegates listening/parsing/keep-alive to JDK `com.sun.net.httpserver.HttpServer`. Its `respondBuffer` delegates blocking-style `sendResponseHeaders` and response stream writes to a virtual-thread-per-task executor. Thus guest `await` is nonblocking **for an actor carrier** but the HTTP protocol and synchronous stream API still live in Java.
- `AsyncNativeIo` uses Java blocking TCP/read/write work offloaded to virtual threads (bounded 128 ordinary operations, 16 control slots). Again, not an evented nonblocking socket layer. A virtual thread does not prove a nonblocking syscall.
- `NativeHttp.Server.accept()` has one pending guest accept waiter; `Server.receive` serializes state publication and the supervisor performs routing/dispatch. Measure wait time and queue depth before claiming this is the principal throughput ceiling.
- Reported 700–900 completed req/s on two-vCPU hosted runners suggests a shared throughput limit but does not identify whether the hot path is interpreter execution, I/O, transport, scheduling, allocation, or locks.

## Required architecture (Java/GraalVM and LLVM)

The **public ownership and protocol implementation are Oreslang source**, shipped as `std/net` and `std/http`. No Java HTTP server, parser, request-handler dispatcher or logging library is authoritative. Java and LLVM implement only a minimal, capability-checked *nonblocking kernel network primitive* boundary. On JVM use nonblocking SocketChannel/ServerSocketChannel and Selector (or an equivalent genuinely nonblocking backend), not the JDK `HttpServer` or blocking socket methods. On native LLVM use nonblocking FDs with epoll/kqueue/poll or a verified completion facility. Cross-backend semantics and AOT compilation are mandatory before replacement.

Proposed Oreslang API (NOT yet implemented; deliberately not registered as a stdlib module):

```ores
import module net from "std/net";
import module http from "std/http";

pub async routine serve(): void {
  val listener = await net.listen("127.0.0.1", 8080, 4096);
  loop {
    val connection = await listener.accept(); // readiness based, cancellation aware
    // HTTP/1.1 parser, per-connection state machine, backpressure, and
    // actor dispatch belong to Oreslang source, not the host backend.
  }
}
```

The socket API must support owned byte buffers, partial reads/writes, EOF, half close, deadlines and cancellation, backpressure, a bounded accept queue, and one registered reader/writer direction per connection. No raw pointers or OS FDs cross an actor security boundary; only capability-checked ownership transfers are permitted. `await` must unregister readiness, suspend the Oreslang continuation, and resume at most once through the owning scheduler without blocking an actor carrier. Per-connection parse budgets bound header count, bytes, body length and HTTP pipelining; reject request smuggling, conflicting framing, malformed percent-encoding and invalid UTF-8. Any serialized transport token must retain the existing single-use claim and generation revocation rules. Preserve actor single-flight execution.

## Oreslang-owned trace API and emission contract

`stdlib/trace.ores` implements `Trace.start_ns(enabled)`, `Trace.duration_ns(started_ns)`, and `Trace.event(correlation_id, phase_id, elapsed_ns)` in Oreslang. Clock reads come from the existing Oreslang `process.monotonic_ns()` builtin. These source functions do no I/O; a disabled span allocates nothing and does not read the clock. An `Event` is an immutable numeric record, not a log write. No Java `CorePerf` event names or Java logger should become part of the **public** tracing interface.

Next, implement a bounded Oreslang trace-collector actor that accepts fixed-size events through a *nonblocking* `try_send`-style mailbox API, aggregates count/sum/max and histogram buckets, and exports off the request path via `await` on nonblocking socket/file sinks. This collector, its mailbox API and bounded sink are **not yet implemented**. Explicitly count and report dropped trace events; never block a request or wait for trace drain while holding actor state loans. Correlation IDs must be stable across `await`, actor migrations, cancellation and transport handoff. No URI/query, authorization token, body, raw headers or user data in trace records. Use monotonic durations (clock domain local to each process), with trace sampling after warmup and a zero-instrumentation control run.

Recommended stage IDs (version the schema before deployment):

| ID | Phase boundary or duration |
|---:|---|
| 1 | socket.accept.ready -> connection admission |
| 2 | connection admission -> parser enqueued |
| 3 | HTTP parse execution |
| 4 | parsed request -> supervisor dispatch dequeue |
| 5 | Oreslang route resolution |
| 6 | actor mailbox enqueue -> dequeued |
| 7 | actor ready/scheduled -> execution |
| 8 | actor continuation suspension -> resumption |
| 9 | response enqueued -> socket writable |
| 10 | socket write-ready -> bytes committed |
| 11 | request admitted -> response completed |
| 12 | trace collector enqueue delay (collector only) |

Also track per-phase queue depth/current-high-water, admitted/accepted/rejected/timeout/canceled/completed, scheduler runnable/blocked counts, I/O readiness registration counts, partial-write retries, bytes in/out, executor thread counts, CPU, RSS, allocation rate, GC pauses, and dropped trace events. Percentiles must use per-event distributions, not differences of aggregate percentiles.

For automatic all-function tracing, add a compiler-verified `@trace` or build-time instrumentation pass that injects source-level phase events at function entry/exit and suspension/resume; LLVM and GraalVM must lower to the same semantic event schema. Default disabled/production sampled: no string construction, heap allocation, synchronous output or call to host instrumentation. No indiscriminate printf-style logging for every request.

## Hard validation gates

1. Add true nonblocking listener/connect/accept/read/write primitives to both Java and LLVM with no host HTTP classes. Test readiness, EOF, half-close, partial writes, backpressure, deadline/cancellation races, fd cleanup, and security denial.
2. Implement `std/net` and `std/http` with actual Oreslang source and guest tests for parser fragmentation, connection reuse, streaming, limits and malformed requests.
3. Add the Oreslang collector and a fixed phase registry; prove that turning telemetry off changes neither output nor actor semantics and that telemetry saturation cannot block a request.
4. Benchmark JDK raw HTTP, old Spin, direct nonblocking Oreslang TCP, Oreslang HTTP without actors, and Oreslang HTTP with fresh/pooled actors **on the same pinned hardware**. Keep keep-alive, body, connection concurrency, warmup and runtime optimizations identical. Run open-loop as well as closed-loop tests to avoid coordinated omission.
5. Record per-stage p50/p95/p99, queue depth and host CPU with separate load-generator capacity, ABBA order and >=3 independent runs. Fix the largest measured dominant stage; no unverified 10k-RPS or 1ms claims.
6. Only then retire `NativeHttp` after compatibility/ownership/security checks; fail closed on unsupported native backend paths rather than secretly falling back to JDK `HttpServer`.

This document is a migration contract, not a claim that blocking host I/O or Java HTTP handling has already been removed.
