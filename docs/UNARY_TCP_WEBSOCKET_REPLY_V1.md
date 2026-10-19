# Unary TCP and WebSocket reply envelope — host foundation v1

**Implementation status (2026-10-19):** Java 21 standalone framing and
correlation tests execute on the feature branch. This is **not** the Oreslang
`define unary tcp actor` or `define unary websocket actor` implementation.
Those declarations remain fail-closed until compiler, listener/session
integration, backpressure and authorization tests pass.

## Semantics: a unary *request*, not a unary connection

HTTP already has one response per request. TCP is a byte stream: an arbitrary
`read()` is not a message boundary. WebSocket supplies message boundaries
after a conformant RFC 6455 parser has handled upgrade, masking,
fragmentation and control frames, but does not automatically provide RPC
correlation.

| Transport | Message boundary | Unary response | Connection lifetime |
| --- | --- | --- | --- |
| HTTP | One HTTP request | One HTTP response, status + headers + body | HTTP keep-alive may persist |
| TCP | **Explicit 4-byte length-prefix** | One correlated REPLY or ERROR frame | TCP remains open until explicit EOF/close |
| WebSocket | One *complete binary WebSocket message* | One correlated REPLY or ERROR binary message | WebSocket remains open; closure uses RFC 6455 handshake |

The transport bindings (not yet executable guest syntax) may eventually look
like:

```ores
define unary tcp actor Calculate as
    run(TcpMessage req): TcpReply<Bytes> {
        return TcpReply.bytes(compute(req.payload));
    }
end

define unary websocket actor ChatRpc as
    run(WebSocketMessage req): WebSocketReply {
        return WebSocketReply.binary(compute(req.binary));
    }
end
```

Both examples are **design targets**, not currently accepted syntax. Default
`define tcp actor` and `define websocket actor` remain stream actors.

## Wire v1: explicit version, type, session, epoch and request ID

`UnaryTransportFrame` exposes a 26-byte inner envelope in network byte order:

| Offset | Bytes | Meaning |
| --- | ---: | --- |
| 0 | 1 | Version = 1 |
| 1 | 1 | Kind: REQUEST=1, REPLY=2, ERROR=3, CANCEL=4 |
| 2 | 8 | Positive, supervisor-assigned session ID |
| 10 | 8 | Positive connection generation/epoch |
| 18 | 8 | Positive, per-session correlation/request ID |
| 26 | 0–1,048,576 | Body bytes; CANCEL has no body |

All IDs use signed 64-bit big-endian encoding and reject zero/negative values.
Session IDs must not be reused while delayed frames or old reply slots could
remain live; the runtime is responsible for minting and retiring them.
Connection generations change after reconnects/rebinding and request IDs
remain unique within each active session generation.

- TCP: exactly 4 bytes of big-endian length (counting **only** the inner
  envelope, not the prefix), followed by the specified envelope. The parser
  checks the size before allocating: 26 through 1,048,602 bytes. A short
  prefix/payload, oversized or malformed frame is a protocol error/EOF, not
  an empty request. A connection reader must impose read deadlines and
  per-session capacity limits; this primitive does **not** do either.
- WebSocket: the **26-byte envelope + payload** is the content of one
  *complete binary message*. There is no extra length prefix. The future
  adapter must negotiate a protocol such as `ores-unary.v1`, authenticate
  the peer, validate RFC 6455 masking, fragmentation, UTF-8 for any text
  control messages, ping/pong/close ordering, and enforce byte/item credits.
  Text WebSocket messages are **not** silently treated as unary envelopes.
- ERROR and REPLY are terminal for a request; CANCEL races them and only
  one side may win. A CANCEL must not become a synthetic second reply.
  The error payload codec is a separate versioned contract; an arbitrary
  Java exception, stack trace or `toString` must never be exposed on the
  wire. Until then, transport adapters must fail closed.
- `UnaryReplySlot` atomically grants at most one terminal outcome and
  verifies transport, session, generation and correlation. Wrong-session
  replies reject even if a reconnect reuses the same epoch/request counter.
  Host envelopes are **not authorization tokens** or cryptographic MACs:
  peer authentication, connection/session binding and replay checks must be
  applied at network ingress. Do not publish actor handles/raw FDs as
  envelope fields or serialize reply slots through ordinary actor messages.
- Body byte arrays are defensively copied on construction and access;
  payload admission is capped at 1 MiB. Full streaming uses a distinct
  bounded chunk/credit protocol, not an unbounded single unary envelope.

## Bounded per-session admission and terminal credit release

`UnaryRequestRegistry` is a synchronized, supervisor-owned bounded registry,
separate from the per-request `UnaryReplySlot`. It permits at most the configured
1–65,536 active request IDs per authenticated transport/session/generation.
The registry mints monotonically increasing positive IDs (never reuses an ID in
the same session), rejects admission at capacity, and denies new work after
session closure. ID exhaustion requires a fresh session rather than rollover.

Each successful REPLY/ERROR or cancellation consumes precisely one pending
entry; duplicate/late replies are ignored, while forged future IDs, wrong
protocol, wrong session, and stale generations fail closed. Closing a session
retires all remaining entries and returns all admission credits. Registry
methods invoke neither guest callbacks nor socket I/O while holding their
monitor; a future network adapter must handle actual task cancellation,
reply delivery and timeout scheduling outside this lock.

This is bounded **per-session** admission, not a proof of a bounded global
listener/session count. The supervisor must additionally limit simultaneous
connections, total active bytes and actor in-flight work; the reply writer
must publish terminal outcomes without a second send. A terminal slot only
means the local runtime selected one outcome, not that bytes were delivered
to the remote peer.

## Other unary transports — opt-in, not inferred

Unix-domain sockets and IPC may reuse the length-prefixed envelope **only
after** endpoint permissions, peer credentials and generation fencing.
QUIC streams have their own stream identity and cancellation mapping;
QUIC datagrams and UDP are *not* reliable, ordered request/reply transports
without additional retransmission, deduplication and expiry rules.
HTTP/2 and HTTP/3 streams have independent HTTP request/response semantics;
do not wrap them as raw TCP envelopes. No generic `unary response`
conversion is allowed without an explicit typed transport codec.

## Implemented and tested versus still missing

The feature branch adds `UnaryTransportFrame`, `UnaryReplySlot`,
`UnaryRequestRegistry`, their dedicated standalone test checks, one
repeatable script and Maven JUnit wrappers.
Three consecutive local Java 21 runs passed real loopback TCP split/coalesced
writes, WebSocket binary envelope roundtrips, malicious size/version/type/
generation probes, defensive copying, wrong-session replay rejection,
3,000 frame-level cancellation/reply races, and 3,000 additional registry
completion/cancellation races with exact credit reclamation.

**Not implemented or validated:** a TCP listener/session actor adapter;
RFC 6455 upgrade and WebSocket network I/O; transport authentication; global listener/connection admission; guest AST/typechecker
handling of `unary tcp/websocket`; reply codec/type lowering; producer
stream backpressure; actor panic/deadline/close cleanup; LLVM parity; or
full JDK25 Graal Native Image. Existing HTTP PR #1399 is separate and
must still pass full CI. Never claim these guest declarations executable
based solely on host-codec tests.
