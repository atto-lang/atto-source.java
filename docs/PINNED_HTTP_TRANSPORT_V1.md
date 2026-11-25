# Pinned HTTP/HTTPS transport v1

Status: **host transport substrate only; guest hostname egress remains disabled**.

This layer consumes the per-connection authorization from
`OutboundEgressPolicy` without handing an authorized hostname back to a lower
TCP stack for another DNS lookup. The current revision also removes the blocking
DNS/write/backpressure gaps that previously prevented safe hostname enablement.

## Connection invariant

For every new physical connection:

1. canonicalize the target through `OutboundEgressPolicy`;
2. obtain one bounded/cancellable DNS authorization for that connection;
3. reject the entire DNS result if any returned address class is denied;
4. connect a nonblocking `SocketChannel` directly to one of the authorized
   `InetAddress` values;
5. for HTTPS, run `SSLEngine` over that already-connected channel and use the
   canonical hostname only for SNI and HTTPS endpoint identification;
6. close the connection when the response is retired.

The physical TCP connect path never receives the hostname.

The transport uses `Connection: close` in v1. There is intentionally no idle
pool, so a DNS authorization cannot outlive one physical connection. Every
later request obtains a new resolution generation. Pooling is a separate
follow-up and must preserve that rule whenever it creates a new socket.

Connection admission is held in a context-shareable `ConnectionBudget`.
Individual request transports remain independently revocable, while
per-origin/global limits cannot be bypassed by creating another request
transport inside the same context.

## Bounded DNS and physical retirement

Production guest requests in one Oreslang context share one
`BoundedDnsResolver` pool. The system pool has 8 resolver workers and a
32-entry queue; the outer native-I/O and HTTP connection budgets remain
additional admission bounds.

Each admitted hostname lookup:

- runs on a bounded resolver executor;
- has bounded queue/concurrency admission;
- is limited by both its DNS timeout and the request-wide overall deadline;
- can be revoked by request/transport/context cancellation;
- rejects any result that completes after revocation;
- borrows an `AsyncNativeIo.IoLoan` before resolver execution;
- releases that loan only when the resolver worker physically exits.

Java's system resolver is not specified to be interruptible. Cancellation
therefore revokes authorization immediately but does not forge a retirement
receipt. If a resolver ignores interruption and returns late, the stale answer
cannot authorize a connection and the actor/context remains pinned by the
native-I/O loan until that resolver worker actually exits.

## Selector state machine

One `SelectorTransportConnection` owns one nonblocking `SocketChannel`, one
`Selector`, and optional `SSLEngine` state.

The request transport advances through:

`NEW -> RESOLVING -> CONNECTING -> TLS_HANDSHAKE -> WRITING_HEADERS -> READING_HEADERS -> STREAMING_BODY -> RETIRING -> RETIRED`

Connect, TLS handshake, request write, response read, and body reads use
selector-driven waits. Each wait is bounded by its phase timeout intersected
with the request-wide overall deadline.

Cancellation closes/wakes the selector/channel. Native-I/O worker interruption
is also terminal, so context shutdown cannot leave a selector wait spinning
until the HTTP deadline.

## TLS

HTTPS uses the process/default `SSLContext` in production construction.

The TLS path:

- starts from an already-connected pinned `SocketChannel`;
- uses `SSLEngine`, not a blocking `SSLSocket`;
- sends SNI for DNS hosts;
- enables the JDK HTTPS endpoint-identification algorithm;
- validates the certificate against the original canonical hostname;
- never substitutes the pinned IP address as the certificate identity;
- preserves unread application plaintext across initial and TLS 1.3
  post-handshake transitions.

Tests use a local test CA/certificate for `origin.example` and prove that:

- `origin.example` can be resolved by policy to `127.0.0.1`, connected by
  exact IP, and still passes hostname verification;
- the server observes SNI `origin.example`;
- the same pinned IP fails closed when the requested hostname is
  `wrong.example`.

## HTTP/1.1 framing

V1 remains bodyless-request GET/HEAD only.

Request construction owns `Host` and `Connection`; callers cannot supply
transport/framing headers. Existing request header count/value/byte validation
is reused.

Response parsing remains strict and bounded:

- CRLF-only status/header lines;
- HTTP/1.0 or HTTP/1.1 final status;
- at most 8 informational responses;
- at most 100 response fields and 32768 header bytes;
- no obs-fold;
- no simultaneous `Transfer-Encoding` and `Content-Length`;
- one canonical numeric `Content-Length` only;
- `Transfer-Encoding` accepted only as exactly `chunked`;
- strict chunk sizes with no extensions in v1;
- bounded trailers with framing/control trailer rejection;
- response-body byte budget for fixed, chunked, and close-delimited bodies.

Repeated ordinary response headers remain separate entries.

## Deadlines and guest backpressure

The transport enforces:

- DNS timeout;
- connect timeout;
- TLS-handshake timeout;
- request-write timeout;
- response-header timeout;
- body-idle timeout;
- one request-wide overall deadline;
- per-origin concurrent-connection limits;
- global concurrent-connection limits.

The guest response body uses the existing 32 KiB bounded pipe. Producer
backpressure now carries the same request-wide overall deadline. If the guest
stops consuming without explicitly cancelling, the native producer cannot stay
blocked beyond that deadline.

A response owns its connection permit until the physical connection is closed.
Closing or fully streaming the body releases the connection and origin/global
capacity exactly once.

## Shutdown

The request owns a revocable transport instance while the bounded resolver is
context-owned. Closing one request transport:

- flips that request's cancellation token so any in-flight shared-pool DNS
  lookup is revoked without closing the pool for sibling/later requests;
- wakes/closes any selector/channel in connect/TLS/write/read;
- closes published responses and releases their connection leases;
- prevents new admission on that request transport.

The enclosing `AsyncNativeIo` operation remains the authoritative physical
worker receipt. DNS adds a nested native-I/O loan so a late system-resolver
worker cannot be mistaken for retired state.

Guest admission still runs the existing loopback-only target validator before
native work is admitted, so this revision does **not** enable hostname or
arbitrary egress.

## Focused tests

`PinnedHttpTransportTest` and `BoundedDnsResolverTest` cover:

- an otherwise unresolvable hostname connected through the exact
  resolver-provided IP with raw request-target preservation;
- fresh DNS authorization for every physical connection;
- DNS timeout/cancellation with a deliberately late resolver result;
- bounded DNS queue rejection and physical-loan retirement;
- cancel-vs-DNS without stale authorization;
- request cancellation leaving the context-shared DNS pool reusable;
- selector retirement on native worker interruption;
- pinned-IP HTTPS with original-host SNI and hostname verification;
- hostname mismatch rejection after pinned TCP connect;
- per-origin capacity held until response close;
- header and body-idle deadline cleanup;
- TE + Content-Length ambiguity rejection;
- strict chunked framing/body budget;
- transport shutdown interrupting a blocked body read and retiring the lease.

## Remaining gates before hostname egress

Do not remove the guest loopback-only restriction until the exact candidate has
nonzero-step JDK25 execution for the focused tests plus the repository's
GraalVM Native Image/AOT gates.

After that proof, hostname egress can be widened under
`OutboundEgressPolicy.generalHttpDefault()`. Request-body streaming, broader
HTTP methods, redirect integration, pooling, reverse-proxy wiring, forward
proxy/CONNECT, and benchmarks remain separate follow-up slices.
