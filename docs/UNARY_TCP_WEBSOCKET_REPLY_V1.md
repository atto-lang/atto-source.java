# Unary TCP and WebSocket reply envelope — host foundation v1

**Implementation status (2026-10-19):** Java now has the framing/correlation
foundation plus a host-owned authenticated TCP session adapter and an
ActorRuntime one-shot unary dispatch bridge. The session adapter requires an
explicit authenticator before it reads any unary envelope, enforces bounded
connection/request/payload admission and deadlines, and keeps terminal writes
outside actor carriers. This is still **not** executable guest
`define unary tcp actor` or `define unary websocket actor` syntax. A concrete
production authenticator/listener binding, compiler lowering and full backend
validation remain release gates.

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
  supports a caller-supplied 0..1 MiB peer/session payload budget and rejects
  an over-budget length immediately after the 4-byte prefix. It can then read
  only the fixed 26-byte header, allowing session/generation, correlation and
  count/byte admission to complete **before payload allocation or payload
  reads**. Rejected but syntactically valid payloads can be drained through a
  bounded scratch buffer so framing remains synchronized. A short
  prefix/header/payload, oversized or malformed frame is a protocol error/EOF,
  not an empty request.
- WebSocket: the **26-byte envelope + payload** is the content of one
  *complete binary message*. There is no extra length prefix. The envelope
  decoder enforces the same caller-supplied 0..1 MiB peer/session payload
  budget, but it receives an already assembled message. Therefore the future
  RFC 6455 parser must enforce that limit incrementally while accumulating
  fragments; post-reassembly validation alone is not a network-memory bound.
  The adapter must also negotiate a protocol such as `ores-unary.v1`,
  authenticate the peer, validate masking, fragmentation, UTF-8 for any text
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
the same session), requires the trusted caller to supply the admitted request
payload size, rejects admission at either request-count or outstanding-payload
byte capacity, and denies new work after session closure. Byte credits are
released exactly once on reply/error, cancellation or close. ID exhaustion
requires a fresh session rather than rollover.

This registry is for **supervisor-minted local request IDs**. It is not an
inbound untrusted-peer request-ID ledger. A server-side TCP/WebSocket actor
adapter that accepts peer-chosen correlation IDs needs a distinct authenticated
inbound admission table with duplicate/replay policy; it must not mint a local
ID and then silently substitute or trust a different peer ID.

Each successful REPLY/ERROR or cancellation consumes precisely one pending
entry; duplicate/late replies are ignored, while forged future IDs, wrong
protocol, wrong session, and stale generations fail closed. Closing a session
retires all remaining entries and returns all admission credits. Registry
methods invoke neither guest callbacks nor socket I/O while holding their
monitor; a future network adapter must handle actual task cancellation,
reply delivery and timeout scheduling outside this lock.

The standalone `UnaryRequestRegistry` remains a per-session/local-request
primitive. Authenticated peer-selected IDs use `UnaryInboundRequestRegistry`,
and `UnaryTcpSessionAdapter` now delegates its live replay/count/request-byte
accounting to that same ledger instead of maintaining a parallel high-water
implementation. The adapter requires a successful
`Authenticator.authenticateAndBind(...)` before reading the first unary frame,
verifies the supervisor-minted session/generation on the fixed header before
body reads, requires strictly increasing peer REQUEST correlation IDs, rejects
future/unissued CANCEL IDs, and treats late/duplicate CANCEL as already
terminal. An authenticated syntactically valid request ID is consumed even
when bounded admission rejects it and returns a terminal overload ERROR; retry
must use a fresh larger ID. This prevents a lost overload ERROR from making the
same request identity executable later. Envelope IDs are never credentials.

The adapter independently bounds active connections, per-connection request
count and **combined pending-request + queued-response payload bytes**, global
work items and global payload bytes. A global work credit is retained when a
request becomes a queued terminal reply and is released only after that reply
is written or dropped. Response conversion also remains inside the
per-connection byte budget, so one slow connection cannot monopolize the
listener-wide response-memory allowance. Read,
request-execution and terminal-write deadlines are distinct. The read budget
now also applies as an **absolute handshake or single-frame deadline**,
independent of per-read SO_TIMEOUT, so an unauthenticated peer or a one-byte-
at-a-time trickle cannot keep an incomplete frame alive indefinitely. That
absolute deadline closes the socket on expiration. It bounds socket I/O, not
a custom authenticator doing non-cooperative CPU work; production authenticators
must honor socket closure/cancellation themselves. A write deadline closes the
whole TCP session because stream state is no longer trustworthy. Faulty actor
cancellation hooks (including callbacks that throw or never return) are
**advisory and isolated** from I/O and deadline threads. The adapter uses two
daemon cancellation workers with a bounded queue of at most
`min(maxGlobalWorkItems, 256)` tasks; request/response credits are reclaimed
independently of those callbacks. If both workers and that queue are saturated,
the callback is rejected rather than executing inline, and
`rejectedCancellationTasks()` counts dropped cancellation attempts. This
prevents timer starvation and unbounded cancellation-worker growth, but does
**not** guarantee that a non-cooperative guest task stops after transport
cancellation. The runtime's own cancellation/cooperation contract and
operational observability for cancellation rejection are release requirements.
Overload/protocol failures use an empty opaque ERROR and never serialize an
arbitrary host exception, stack trace or `toString`.

The byte counters are **wire/buffer admission counters**, not a claim that they
measure every transient JVM object or actor-heap byte. ActorRuntime mailbox/
heap limits remain an independent layer. The host one-shot actor bridge now
uses a lazy immutable unsigned-byte view over the request's defensive
`byte[]`, rather than eagerly allocating one boxed `Long` per wire byte before
actor admission. ActorRuntime can therefore apply its message-graph node bound
from the list size before a second large collection is materialized.

The current Java one-shot bridge still does **not** promise that every
wire-valid 1 MiB unary body is actor-executable: ActorRuntime currently caps a
message graph at 100,000 nodes, so large byte-list payloads fail closed before
guest execution. A production `Bytes` lowering needs a compact sendable value
whose accounting is byte-based rather than one graph node per octet.

The current adapter accepts an already-created `Socket`; a production listener
must additionally own bind/accept permissions, backlog policy, concrete peer
authentication and lifecycle supervision.

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

The feature branch now adds `UnaryTransportFrame`, `UnaryReplySlot`,
`UnaryRequestRegistry`, `UnaryInboundRequestRegistry`,
`UnaryTcpSessionAdapter` and `UnaryTcpActorDispatcher`, dedicated
standalone/JUnit checks and a repeatable warning-clean Java 21 smoke script.
The separate inbound registry captures the bounded strictly-monotonic
peer-ID/replay contract independently of the transport loop so future TCP,
WebSocket and IPC adapters can share the same fail-closed rule.

The current TCP session regression uses real loopback sockets for successful
dispatch, replay/session fencing, per-connection and cross-connection overload,
per-connection response-byte rejection/reclamation, CANCEL propagation,
request deadlines, authentication failure, connection capacity and
session-close cleanup. It also includes a deterministic blocked
writer/reader socket proving that the terminal-write deadline closes the
session and reclaims queued response credits. Ten local Java 21 passes of the
session suite completed across this hardening cycle, including 2,500
completion-vs-CANCEL races. The fixed-header framing and current session test
sources used for the final local runs match their committed Git blobs; the
adapter's locally compiled executable source normalizes to the same
comment/whitespace-independent code hash as its committed GitHub blob.

`UnaryTcpActorDispatcher` is a host/compiler integration bridge into real
`ActorRuntime` mailbox execution for PRIVATE/SHARED one-shot unary callables.
It transports only a frozen data map, propagates transport cancellation to the
actor, waits for the actor finalization/carrier-leave barrier before publishing
success, and applies a strict lossless Bytes-style result codec. UNTRUSTED
transport dispatch remains deliberately rejected until a compiler-owned source
executor can bind it without a trusted captured host factory. Its source and
strict codec compile warning-clean against the inspected ActorRuntime/OresFuture
surface; full-runtime JUnit coverage is committed but still needs an executing
Maven CI runner.

**Still not production-complete:** the branch includes an opt-in
`UnaryTcpMutualTlsAuthenticator` using TLS 1.3, mandatory client-certificate
verification, an explicit certificate authorizer and in-channel session binding.
The JDK-only disposable-cert loopback gate is
`scripts/test-unary-mtls-loopback.sh`. Production still requires independent
review of the TLS context/truststore, certificate-authorizer policy, transport
permissions and certificate rotation/revocation behavior; it must not treat
a subject DN or a successful handshake alone as application authorization.
An opt-in `UnaryTcpTlsListener` now owns a server TLS 1.3-only accept loop,
mandatory client authentication and pre-accept connection slots, with an
idempotent permit lease so shutdown and worker completion cannot double-release
capacity. It requires a **pre-bound** host-provided `SSLServerSocket`; selection
of address/backlog, network capabilities, per-peer rate limits, OS endpoint
permissions and trusted SSLContext remain an audited deployment responsibility.
The disposable-cert smoke script also runs a real listener/unary reply/shutdown
check. Production still needs those host bindings and source-level
`define unary tcp actor` AST/typechecker/lowering rather than the current
one-shot host bridge; UNTRUSTED source-executor binding; RFC 6455
upgrade/incremental-fragment WebSocket networking; LLVM parity; and full JDK25
Maven/Graal Native Image validation. Existing HTTP work is separate and must
still pass its own full CI. Never claim the guest declarations executable from
host transport tests alone.
