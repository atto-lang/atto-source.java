# WebSocket transport v1

Status: **host-only protocol/NIO foundation; not executed as Oreslang guest**.
No source-level actor connection handle or production WebSocket server is
claimed by this change.

## Layering

The transport is intentionally split so wire parsing cannot accidentally grant
socket or actor authority.

1. **HTTP upgrade admission** validates an origin-form HTTP/1.1 GET upgrade,
   version 13, a canonical base64 key, configured origin/auth policy, and scoped
   NET authority before a connection becomes WebSocket state.
2. **Frame codec** parses already-bounded bytes. Client frames must be masked,
   RSV bits are rejected unless a future negotiated extension owns them,
   reserved opcodes are rejected, extended lengths must use canonical encoding,
   and control frames are final and at most 125 bytes.
3. **Message assembler** enforces an independent reconstructed-message byte
   limit. Fragmented text is UTF-8 validated only after final reassembly so a
   multi-byte scalar may legally cross frame boundaries. Control frames do not
   consume or reset user-message assembly state.
4. **Connection transport** must prioritize close/ping/pong processing over a
   bounded USER-message queue and expose scheduler-owned async read/write
   Futures. No socket/JDK object may reach guest code.
5. **Actor ownership** gives one actor generation the connection capability.
   Close, cancellation, actor failure, deadline and supervisor shutdown revoke
   it exactly once; stale generations cannot read, write, or revive the handle.

## Implemented frame boundary

`WebSocketFrameCodec.decodeClientFrame` requires exactly one complete client
frame and rejects trailing bytes. It checks the configured frame limit before
allocating the decoded payload. Unfragmented text and close reasons use strict
UTF-8 decoding. Close payloads reject the one-byte form and reserved/invalid
status codes.

`MessageAssembler` accepts only text/binary data plus continuations, rejects
continuations without a predecessor and overlapping fragmented messages, and
resets after a size violation. Its message limit is distinct from the frame
limit.

`encodeServerFrame` emits unmasked canonical server frames and applies the same
control-frame and payload bounds.

All returned payloads are defensive copies. The implementation uses only
closed-world JDK primitives and has no reflection requirement, preserving the
Native Image/AOT direction.

## Implemented raw HTTP opening-request boundary

`WebSocketOpeningRequest` reads one raw request head with a total byte bound
and header-count bound. It requires CRLF line endings, ASCII without NUL,
canonical origin-form request targets, and rejects obsolete folded headers.
It retains duplicate detection before normalization: Host,
Sec-WebSocket-Key, Sec-WebSocket-Version, Origin, Content-Length and
Transfer-Encoding are singleton inputs. Upgrade and Connection may repeat and
are combined as token lists before the protocol validator runs.

Opening-request bodies are rejected: Content-Length may be absent or exactly
zero, while Transfer-Encoding is rejected. Unknown application/authentication
headers are deliberately not materialized by this minimal parser; the future
listener must pass a separately bounded reviewed header view to application
authentication rather than silently granting access to raw host parser state.

## Implemented opening-handshake boundary

`WebSocketHandshake.admit` now validates the protocol-defining fields before
any connection capability can exist: exact GET + HTTP/1.1, bounded ASCII
Upgrade/Connection token lists, WebSocket version 13, and a canonical padded
Base64 16-byte nonce. It returns the RFC 6455 Sec-WebSocket-Accept value using
the protocol-required SHA-1 construction. SHA-1 here is a protocol transform,
not a password/signature security primitive.

`requireAllowedOrigin` provides a deliberately exact browser-origin allowlist:
missing policy, `null`, wildcard spellings, and unlisted origins fail closed.
Authentication remains application-owned and must be checked alongside origin
policy before actor connection authority is granted.

The helper owns no HTTP exchange or socket. A future transport adapter must
also reject duplicate singleton handshake headers at the raw HTTP-header layer;
the current first-value HttpExchange accessor is not sufficient evidence for
that property.

## Implemented incremental framing and session backpressure

`readClientFrame(InputStream,...)` reads the two-byte header and canonical
extended length before allocating a payload. The configured frame limit is
therefore enforced before reading a masking key or payload for an oversized
declaration. Frame/message configuration is hard-capped at 64 MiB at this
primitive boundary. Short reads are handled explicitly, zero-progress reads
are forced to make progress or fail, and premature EOF fails closed.

`writeServerFrame(OutputStream,...)` writes the canonical header and payload
directly without constructing a second complete wire array.

`WebSocketSessionState` separates protocol CONTROL from bounded USER delivery.
Ping returns an immediate pong action, peer close returns an immediate close
action, and neither consumes USER queue capacity. A completed USER message is
queued only if capacity exists; configured USER capacity is capped at 65,536.
USER overflow does not silently discard and continue: it transitions the
connection to closing and emits close code 1013. Both USER message count and
aggregate queued payload bytes are now independently bounded: maximum 65,536
queued messages and 16 MiB of queued payload across a session. The actual
queue can use a lower byte quota, but maxMessageBytes must fit within that
quota; rejecting incompatible configuration prevents a full-size admitted
message from exceeding queue capacity. Message length checks do not copy the
payload. Polling retires exact byte credits and physical close resets all
queued-byte accounting. This is **per-session** only; a listener must impose
separate global/per-tenant memory and connection budgets. Locally generated close
payloads are passed back through the codec's close-status validation so reserved
codes cannot be emitted. Fragmented data consumes one USER slot only after
complete reassembly.

This state machine still owns no socket or actor. A future raw-socket transport
must serialize returned CONTROL actions ahead of subsequent USER writes and
must close/revoke the transport exactly once on terminal lifecycle events.

## Native transport decision

The existing Oreslang HTTP server is implemented on JDK `HttpServer`. That
adapter does not expose a supported raw-socket ownership handoff after a 101
upgrade. The WebSocket implementation therefore must not depend on reflective
access to JDK HTTP internals. The native WebSocket listener should be a separate
bounded raw-socket transport sharing Oreslang's scoped NET permission and actor
ownership contracts.

## Implemented raw loopback upgrade bridge (host-only)

`WebSocketRawUpgrade` now exercises the existing admission helpers on a
real **already-accepted Java Socket**, without importing JDK HTTP internals.
The bridge is deliberately package-private and cannot create a listener or
grant an actor/guest handle. The future listener must first check scoped NET
permission, own its accept socket through `AsyncNativeIo` physical loans,
provide connection admission quotas, and run this bridge exclusively on a
host I/O worker (never an actor/CONTROL carrier).

The boundary requires a loopback **peer**, an exact canonical Host authority
matching the bound listener, and an explicitly allowlisted nonempty Origin.
It parses the request head with configured byte/count limits, enforces a
**single absolute handshake deadline** (not just a reusable per-read timeout),
and emits the canonical HTTP 101 response only after admission succeeds.
Denied, malformed, slow, or interrupted upgrades close the accepted socket;
successful upgrades leave a finite socket read timeout. No extensions or
subprotocols are negotiated; the client cannot select them implicitly.
This first loopback slice deliberately requires Origin even for nonbrowser
clients. Authentication and a reviewed app-header view are *not* provided
by this bridge, so it must not be treated as a public multi-tenant listener.

`WebSocketRawUpgradeTest` adds real loopback socket/byte-stream tests for
the RFC 6455 accept value, a subsequent masked text frame, Host and Origin
denials, invalid Upgrade/body framing, deadline expiry, and misconfigured
bounds. These are Java host integration tests, **not Oreslang guest execution**.

## Bounded supervisor-only raw loopback listener slice

`WebSocketLoopbackListener` now binds **127.0.0.1 only**, with a fixed
nonzero port and mandatory scoped `RuntimePermissions.Permission.NET`
authority. Only a supervisor/host carrier may create the listener or perform
accept/read; it is intentionally package-private and exposes no guest
WebSocket handle or JDK socket to Oreslang source code.

The first host slice uses `AsyncNativeIo` for asynchronous accept and
single-frame read work. Its registered `ServerSocket` and accepted `Socket`
have physical I/O loans, registered-resource retirement and shutdown wakeups.
The accepted socket is upgraded through `WebSocketRawUpgrade` before a host
connection is returned. Each listener enforces a single outstanding accept,
at most **16 active connections**, a backlog at most 128, frames and messages
up to **64 KiB** each, and 16 queued USER messages per connection (thus
at most 1 MiB of queued payload per connection and 16 MiB per listener from
this queue in the worst configured case). The host connection supplies one
outstanding async read and processes frame-level ping/close replies directly
on an I/O worker. USER messages can be polled by host code, not guest actors.

An accepted connection slot is released **only on physical connection close**
(or on failed admission), never because its Future happened to settle.
Synchronous task rejection resets single-flight state without releasing an
owned connection. If registration races context shutdown, the registered
socket stays under `AsyncNativeIo` retirement/quarantine rather than being
force-closed ahead of the physical loan. This is a host foundation, not a
replacement for explicit generation-fenced actor connection capability.

The next patch needs exact-source Java/Graal native tests for the listener,
including permit release and shutdown races. The existing
`WebSocketRawUpgradeTest` provides real loopback 101/denial and masked
frame tests, including silent/slow-drip head deadlines, but no Oreslang guest
has run this new listener.

## Native-I/O owner/retirement hardening: 2026-10-31

The host-only `WebSocketLoopbackListener` now tracks every successfully
published child connection in an identity-based set guarded by the same
lifecycle lock as listener closure. A concurrent accepted-socket upgrade
cannot publish a child after shutdown takes its retirement snapshot.
Closing the listener explicitly closes all published children and clears
their USER queues; duplicate child close does not return a second permit.
The logical listener object is also registered in `AsyncNativeIo`, so
context shutdown retires active children *after native workers have joined*
rather than merely closing a raw `ServerSocket` and leaving USER state
stranded in memory.

`AsyncNativeIo.ownsResource` distinguishes rejected registrations that
*were* retained for post-join cleanup from a fully retired context that
never took ownership; callers must close the latter socket themselves.
This closes an earlier leak for failed register-after-retirement.

A shared `WebSocketSocketDeadlineInput` now applies an **absolute**
deadline to both single-byte and bulk socket reads, including the entire
masked-frame payload. A peer slowly supplying partial payload bytes cannot
keep extending the socket's per-read idle timeout indefinitely. The current
frame deadline uses the configured handshake timeout and should be split
into independently configured read and write deadlines once the native
actor API is integrated. The original classic read/write path remains available
only to the separate raw accepted-Socket host fixture; production loopback
listener sockets are now channel-backed and use the NIO paths below.

## First host-only serialized outbound slice (2026-10-31)

`WebSocketOutboundGate` reserves at most 16 pending or active USER writes and
at most 1 MiB of their retained payload bytes per connection, independently
of a four-slot CONTROL budget. Its limits are configured with the listener's
frame limit (at most 64 KiB). A selected in-flight frame holds its credit until
the writer's physical operation returns; queued cancellation releases credits,
whereas cancellation after writing starts closes the connection to prevent a
partially serialized frame from being followed by other frames.

`WebSocketLoopbackListener.Connection.sendTextAsync` and `sendBinaryAsync`
are host-only methods. They validate scoped NET permission, reject calls from
actor carriers, defensively own bounded USER frames and use one dedicated
serialized host-I/O write lane per accepted connection. The existing PONG and
peer-CLOSE responses are now admitted through reserved CONTROL slots, instead
of writing directly on the host read worker. A close response supersedes
waiting USER and PONG entries, while it cannot interrupt bytes already on
the wire. Deadline expiration or writer failure closes the socket and retires
pending outbound admissions. A canceled *running* write also closes the
socket; Future settlement is not physical loan retirement.

## Host-only selector transport slice (2026-10-31)

The listener now accepts through a blocking `ServerSocketChannel` adapter,
which yields an accepted `Socket` with a real `SocketChannel`. After bounded
HTTP head parsing, `WebSocketRawUpgrade` switches the connected channel to
nonblocking mode and writes the canonical 101 response through
`WebSocketNioDuplex.writeOpeningResponse`, using the same absolute deadline
as handshake admission. The socket does not revert to blocking mode after 101.

`WebSocketNioDuplex` uses per-operation selectors with finite absolute
monotonic deadlines: OP_READ decodes one bounded masked frame through the
existing strict codec; OP_WRITE serializes canonical unmasked frame header and
payload via nonblocking scatter/gather writes. Zero-progress reads/writes
must wait for readiness rather than spin or park an actor. No socket stream is
used after channel mode changes. A read and a write may coexist on separate
host workers while `WebSocketOutboundGate` permits only one physical writer.
Deadlines and closed sockets fail the operation, and the native-I/O physical
loan remains held until its worker exits. This is actual nonblocking kernel
channel I/O, but selector waiting may park *host* workers.

The legacy raw-accepted-Socket fixture continues to use blocking stream I/O;
it is not the production listener path. Neither this NIO adapter nor
`WebSocketOutboundGate` exposes guest socket authority. The first NIO
integration and real loopback JUnit cases are authored, **NOT_EXECUTED** on
the exact Java/Graal source head; no performance/production claim is made.
Separate configurable read/write/handshake deadlines, global/tenant memory
budgets, terminal close-drain protocol, actor-generation ownership, guest
source methods and GraalVM Native Image evidence are still required.

`WebSocketLoopbackListenerTest` contains eight authored real-loopback,
real-`AsyncNativeIo` integration scenarios: successful accepted frame,
parent-close USER queue clearance, capacity exhaustion with healthy-peer
progress, pending accept interrupted by close, native-I/O context sweep,
slow-drip masked payload, post-retirement registration cleanup, and
scoped-NET denial before bind. These tests are **authored, not verified as
executed**. The package-private `bind` seam takes injected native-I/O
and NET-authorization implementations for tests; the production entrypoint
always supplies `OresContext.requirePermission(NET, ...)`.

Even if host tests pass, they do not prove Oreslang guest execution,
generation-fenced actor delivery, cancellation-aware Future retirement,
globally bounded memory, selector/runtime execution evidence or backend parity. Keep this
branch draft until authorized exact-head CI records nonzero steps and
independent review is complete.

## Host-side actor-incarnation claim foundation (draft, NOT_EXECUTED)

A package-private `WebSocketActorClaim` now binds one accepted connection to
one exact live `ActorRuntime.ActorRef` incarnation. A supervisor/host must
issue the claim; actor carriers cannot self-issue or rebind it. The actor
runtime checks the **reference identity**, generation, runtime affinity,
termination state, and the currently active actor mailbox turn. Holding an
actor ID or a Java object reference on a non-actor thread is not authority.

Once a physical connection is claimed, USER sends are accepted only from that
actor's active turn; ordinary host sends and sends by another actor fail
closed. The connection cannot be claimed again, even after actor termination.
Transport `close()` atomically revokes the claim. Actor finalization also
revokes it using a brief admission monitor without executing socket I/O or
running Future completion callbacks on the actor carrier. A shared daemon
host-retirement executor (one physical worker, one cancellable fixed-delay
sweep per open listener) scans the listener's at-most-16 registered children
and closes any connection whose claimed actor is stopped or revoked. Listener
shutdown cancels its sweep and independently retires all children. This
provides automatic physical cleanup for idle actors without per-connection
threads, subject to host-worker scheduling and OS close completion.
The claim's monitor also fences USER admission together with the bounded
`WebSocketOutboundGate.enqueueUser`: a USER write is either admitted
before revoke or rejected after revoke. Actor incarnation liveness
reads only the immutable ActorRef identity and atomic stopped gate: it does
not reenter the actor lifecycle monitor while the claim admission monitor is
held. This avoids a lifecycle-lock inversion when an actor finalizes and
settles done() callbacks under its own lock. Healthy retirement sweeps
allocate no per-tick child snapshots; only stale claims are collected.
Scoped NET authorization and the existing bounded asynchronous writer still
apply.

This is **not yet a safe end-to-end guest API**. There is no source-level
`WebSocketServer`/`WebSocketConnection` builtin or guest-side resolver,
async actor receive bridge, strict global outbound quota,
or proven fairness under many concurrent listener sweeps. The host-only
retirement sweep must still be exercised under stalled I/O and concurrent
shutdown: it is not a kernel-level hard-real-time close guarantee. Already
admitted or in-flight bytes can race with actor finalization, and successful
Future settlement is not physical-loan retirement. A source-level API must
also ensure no guest handle can bypass this ownership gate. No actor guest,
Graal Native Image, or multi-backend execution is claimed by these authored
host tests.

`WebSocketActorClaimTest` now authors five actor-runtime checks (owner
turn, foreign actor/runtime, stopped owner, self-claim denial, and admission
after revoke). Listener tests require automatic idle actor-stop socket
retirement, actor-failure idle cleanup, supervisor-close races, and the
host-bound actor-turn TCP send. These still require an
approved exact-head CI execution receipt before they are evidence.

## Internal opaque connection handles (draft; NOT_EXECUTED)

`WebSocketConnectionHandles` is a bounded host-only lookup table for a
future closed-world Oreslang `WebSocketConnection` builtin. Its private
`Handle` constructor carries table identity, a fixed slot, and a
non-wrapping generation. A handle has no socket/NET/ActorRef getter,
is not Serializable, and redacts its string representation. Possession
does not grant authority: every send checks the exact running actor
incarnation, scoped NET policy, existing outbound capacity, and the
atomic send/revocation fence established in the actor-claim slice.

A host supervisor may claim an authorized/accepted connection once, with
a capacity of 1..16 slots per table. Reuse requires **completed** socket close, listener-permit cleanup,
and native-I/O loan exit—not merely the early logical `retired` CAS,
claim revocation, or completion of a Future. The host-only
`Connection.isFullyRetired()` probe requires the cleanup flag, closed
socket, and no remaining native-I/O resource ownership. Actor-turn
lookup uses only an atomic logical retirement flag; it does not lock the
native-I/O resource ledger. Reused slots advance their generation; old
handles fail even if the slot number is the same. Table shutdown revokes all claims immediately but
never closes OS sockets on an actor or CONTROL carrier; the shared
host-owned listener retirement sweep performs that work. Failed table
admission does **not** transfer accepted-socket ownership: the caller
must close rejected host sockets to avoid idle-resource retention.

A canceled **active** write can settle its Future on an actor carrier.
Its callback now records an atomic abort request and revokes the actor
claim, leaving physical closure to the shared host retirement worker.
The serialized writer will not start a new frame while abort is pending.
An already-started frame may still race with closure; this is not a
hard-real-time cancellation guarantee.

`WebSocketConnectionHandlesTest` authors real-loopback cases for a
correct owning actor, cross-table and host impersonation, a saturated
table, stale-handle rejection after physical retirement and slot reuse,
actor-turn revocation, and table shutdown. The focused exact-head Maven
workflow includes this test class. These are **authored tests**, not
executed Java/Graal or source-level guest evidence.

**Unimplemented:** no guest-level builtin dispatch, source type checker
integration, compiler lowering, Oreslang-visible opaque handle registry,
actor `receive_async` bridge, `Result` conversion, global
per-application handle/memory quotas, or guest echo. Do not pass the Java
Handle directly through guest interop or claim this is a functional
Oreslang `WebSocketConnection` API. Source-visible handles must be
unforgeable values resolved by the owning isolate's closed-world bridge,
with no Java object/string deserialization authority.

## Not yet implemented

- An executable Oreslang-source WebSocket listen/accept/claim/read/send API.
- End-to-end actor/generation-owned connection dispatch and retirement.
- Source types such as `WebSocketServer`, `WebSocketConnection`, or actor
  dispatch/claim tokens.
- Complete close-handshake/half-close and deadline-bounded peer-drain behavior;
  full application/supervisor aggregate byte-credit accounting.
- Compression/extensions.
- Java/Graal AOT execution evidence and LLVM parity.

The first source consumer remains
`oreslang-websocket-server-demo.ores`; it must stay bootstrap-only until the
upgrade, connection and actor-lifecycle layers execute end to end.
