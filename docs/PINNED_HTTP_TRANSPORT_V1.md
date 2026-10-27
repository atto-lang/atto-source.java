# Pinned HTTP transport v1

Status: **implemented as the physical HTTP/1.1 transport under the existing
loopback-only guest request path; arbitrary hostname egress remains disabled.**

## Security boundary

`PinnedHttpTransport` consumes an
`OutboundEgressPolicy.ResolvedTarget` and opens the physical TCP socket with
`Socket.connect(ResolvedTarget.connectAddress(...))`.

The socket therefore connects to the exact `InetAddress` that policy
authorized. The canonical hostname is retained separately for HTTP Host and,
for HTTPS, TLS peer identity.

The transport never passes an unresolved hostname to TCP connect.

## HTTPS identity

For HTTPS the JDK TLS layer wraps an **already-connected** policy-pinned socket:

- the canonical hostname is supplied as the TLS peer host;
- endpoint identification is explicitly set to `HTTPS`;
- DNS names receive explicit SNI;
- IP literals explicitly clear SNI so stale `SSLParameters` state cannot leak
  a previous hostname;
- the existing TCP destination is not replaced or resolved again.

The default trust manager remains the platform/JDK trust configuration. This
PR does not add insecure trust-all behavior.

## Physical connection generation

V1 intentionally sends `Connection: close` and does not pool sockets.

That means every request is a new physical connection and every hostname
request must obtain a new egress-policy resolution generation. There is no
idle pooled connection whose DNS authorization can silently outlive its
generation.

Pooling can be added later only if:

1. each new physical connection is resolved/authorized independently;
2. pool keys preserve canonical scheme + authority + policy identity;
3. idle eviction is bounded;
4. a connection is never retargeted after authorization.

## Limits and deadlines

The transport enforces the policy's:

- global concurrent physical-connection limit;
- per-origin concurrent physical-connection limit;
- TCP connect deadline;
- response-header deadline;
- response-body idle deadline;
- overall request deadline;
- response byte budget.

Connection-limit admission is fail-fast; requests do not build an unbounded
queue behind a stalled origin.

Request headers remain bounded to 100 fields / 32768 aggregate bytes and use
the existing transport-owned framing-header rejection.

The current v1 request has no request body, so request-header writes are small
and bounded. A future streaming request body requires a transport-level write
deadline/backpressure implementation before POST/PUT/PATCH are enabled.

## HTTP/1.1 parsing

The pinned transport is deliberately strict:

- only final 200..599 HTTP/1.0 or HTTP/1.1 responses are accepted;
- CRLF is mandatory for status, headers, chunks, and trailers;
- obsolete header folding is rejected;
- status-code delimiter ambiguity is rejected;
- response headers are capped at 100 fields / 32768 bytes;
- `Transfer-Encoding` + `Content-Length` together are rejected;
- v1 accepts only a single `chunked` transfer coding;
- conflicting Content-Length values are rejected;
- Content-Length overflow and response byte-budget overflow fail closed;
- hop-by-hop and framing headers are not exposed to the guest response;
- chunk trailers cannot redefine framing/control fields;
- chunk extensions are bounded and reject control/non-ASCII bytes.

Bodies support fixed-length, chunked, and connection-close framing. HEAD,
204, and 304 are bodyless.

## Cancellation and retirement

Each logical call owns an atomic reference to its currently active physical
socket.

Cancellation may therefore close the socket while the worker is blocked in:

- TCP connect;
- TLS handshake;
- response headers;
- response body.

The transport owns no worker threads. It runs inside `AsyncNativeIo`, so a
logical Future settlement is still **not** the physical retirement receipt.
The enclosing AsyncNativeIo operation retires only after the worker exits.

Connection permits are released after the physical socket has been closed, not
when response headers are merely published.

Transport shutdown revokes all live calls and closes their active sockets.

## Current guest integration

The existing `OutboundHttpRequest` loopback path now uses
`PinnedHttpTransport` inside its existing `AsyncNativeIo` operation.

The public guest contract has **not** widened:

- still only `http://127.0.0.1:<port>/...`;
- still GET/HEAD;
- no production DNS names;
- no public arbitrary egress;
- no HTTPS guest target yet;
- no redirects;
- no request-body stream;
- no CONNECT.

This deliberately makes the existing actor cancellation, body backpressure,
context shutdown, and `get_done()` tests exercise the pinned transport before
hostname egress is exposed.

## DNS limitation before public hostname egress

`OutboundEgressPolicy.Resolver` is currently a synchronous resolver contract.
The pinned transport calls it on the native-I/O worker and accounts its elapsed
time against the overall request deadline, but Java's system DNS lookup is not
guaranteed to be physically interruptible.

Therefore this PR does **not** wire
`OutboundEgressPolicy.Resolver.system()` into guest hostname requests.

Before arbitrary hostname egress is enabled, production DNS needs one of:

- an independently bounded/interruptible resolver whose physical completion is
  tracked by AsyncNativeIo; or
- a dedicated DNS transport with explicit UDP/TCP deadlines and cancellation.

A timed-out logical Future without physical resolver retirement is not
sufficient.

## Exact tests

`PinnedHttpTransportTest` proves:

- hostname HTTP uses one injected DNS resolution and connects to the pinned IP;
- Host/request-target identity remains the canonical hostname/raw target;
- repeated response headers survive while framing headers stay transport-owned;
- each new physical connection gets a new resolution generation;
- global/per-origin limits reject rather than queue;
- cancellation closes a stalled header read;
- transport shutdown closes a stalled live call;
- response-header and body-idle deadlines retire connections;
- chunked decoding and trailers;
- Transfer-Encoding + Content-Length smuggling rejection;
- malformed status delimiter rejection;
- framing fields in trailers are rejected;
- the HTTPS layer receives an already-connected pinned socket plus the original
  hostname;
- TLS parameters require HTTPS endpoint identification and DNS SNI, while IP
  literals clear stale SNI.

The focused JDK25 gate also runs the existing
`OutboundHttpRequestGuestTest`, so actor/future lifetime regressions cover this
transport on the same exact tree.
