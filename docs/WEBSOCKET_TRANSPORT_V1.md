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
The current policy also rejects percent-encoded C0/space/DEL bytes,
encoded path separators, and encoded percent signs at opening-request admission;
applications requiring alternate URI encodings must define a reviewed
canonicalization policy before enabling them. Bracketed IPv6 authorities are
validated structurally (including canonical embedded IPv4 tails) without DNS
resolution; ASCII-character filtering alone is not accepted as proof of an
IPv6 address. These intentionally restrictive checks are admission policy, not
a claim that RFC 6455 universally forbids these encodings.

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

## Selector cancellation and actor-handle ownership gate (2026-10-31)

The native accept/read `OresFuture` currently rejects observer
`cancel()` because its `AsyncNativeIo` physical operation has not yet
retired. A new loopback test checks that `cancel(true)` does not falsely
settle an accepted read and that listener close is the explicit physical
revocation path. This is **not** an implementation of cancellable native
read/accept operations: true actor-facing cancellation still needs an owned
host cancellation arbiter and post-join receipts.

The NIO bridge now removes stale selected keys after a readiness poll so a
zero-byte read/write does not spin on a selected key from the previous poll.
It also rejects a pre-interrupted worker **before** a channel read/write,
even when the socket would otherwise be immediately ready. In the listener,
a read crossing the retired-connection boundary fails rather than publishing
a USER action on a newly revoked transport.

Authored focused negative tests for pre-interrupted read/write with zero
server output, parent-listener close during an incomplete masked payload,
channel close during an incomplete selector read, and channel close during
a backpressured multi-megabyte write. The selector tests use real loopback
`SocketChannel` pairs, not a mock. These changes are **not a verified
JDK25/Graal, JUnit, or Oreslang guest execution pass** until a trusted
exact-head runner completes nonzero steps.

The next guest-facing WebSocket handle must be a *runtime capability*, not
a raw `Socket`, `SocketChannel`, or a freely constructible actor ID.
Host authority should bind the handle to a tenant-unique isolate authority
and `ActorRuntime.ActorAddress(runtimeId, actorId, generation, kind, transport)`,
plus a non-reusable connection generation. Acceptance and each operation
must validate the **current** owner and isolate; actor stop/retirement or
tenant shutdown revokes the capability and cancels/wakes the owning physical
I/O. An explicit transfer must atomically fence the old generation before
activating the new owner, including against pending writes and callbacks.
Neither matching `ActorId` alone nor a thread/carrier identity suffices.

Do not expose the current host-only listener/connection objects to guest
code. No actor-owned guest handle, generation-fenced transfer API, native
guest execution, or cross-backend parity is implemented by this milestone.
Physical close remains separate from the actor logical revocation receipt.

## Host-only actor connection lease and generation fencing (2026-10-31)

`WebSocketActorConnectionLease` is a source-local **host ledger** for one
logical accepted WebSocket connection. It issues identity-only opaque handles
with non-reusable connection identity and monotonically incremented ownership
generations, rather than exposing `Socket`, `SocketChannel`, `ActorRef`, or
an actor state pointer to guest code.

At issuance and use, trusted host code supplies the tenant identity and
authenticated `ActorRuntime.ActorAddress` (runtime UUID, ActorId, actor
generation, kind, and transport), with a live-owner predicate. An incorrect
tenant, actor, runtime, or actor generation fails closed. All these values
**must be derived from the owning runtime**, not from a guest-provided
address. Importantly, `ActorAddress` does not prove tenant membership.
An eventual runtime host bridge must independently check the new owner
belongs to the same tenant and isolate before calling this ledger.

`Connection.bindActor` now accepts a trusted actor ref, while
`bindActorHost` and `transferActorHost` are package-private test seams.
Binding is single-shot and serialized with connection retirement under
`lifecycleLock`. Transfer is rejected while any physical-operation lease
remains outstanding; it fences the old handle and issues a new generation
atomically. Transfers to another runtime instance, actor trust class, or
transport fail closed in v1. Before enqueue and again before actual guest
delivery, the trusted scheduler must validate handle/tenant/owner generation.
`Loan.enqueueIfCurrent` only permits bounded, nonblocking **host scheduler
enqueues** under the short lifecycle lock; never run guest code there.

On `Connection.close()`, the logical ledger is revoked before physical
socket closure; retained in-flight loans remain counted until the owning
worker releases them. Logical revocation and a zero loan count do **not**
assert that the underlying `AsyncNativeIo` resource/selector or actor
continuation physically retired. A trusted `bindActor(OresContext, ActorRef)`
now subscribes to `ActorRef.done()` and schedules host-only socket retirement
when that exact owner is finalized. This does not yet constitute a guest
message-routing or physical tenant-isolation proof.

The authored `WebSocketActorConnectionLeaseTest` suite exercises foreign
tenant/actor/generation, old handles after transfer, outstanding-loan
transfer denial, close/stop fencing, crossed runtime/policy/transport,
delivery-after-owner-death, revoke-vs-enqueue, and transfer-vs-admission
races. Existing real TCP listener tests additionally check binding and
revocation against an accepted peer. All tests remain **NOT_EXECUTED**
until an authorized exact-head JDK25/GraalVM workflow executes nonzero
steps. The package-private methods are NOT an exposed Oreslang guest API
or a verified isolate security boundary.

## Actor-authorized operation accounting (2026-10-31)

New host-only `Connection.readForActorAsync`,
`sendTextForActorAsync`, and `sendBinaryForActorAsync` enforce
authenticated tenant+ActorAddress+generation against a bound
`WebSocketActorConnectionLease`. Raw host read/send/poll entry points
reject requests after binding. These are **package-private native host
bridges**, not guest-visible API declarations. The tenant and actor
identity parameters remain trusted host inputs; a guest must never be
allowed to supply them.

An actor read acquires a lease before native worker submission and
releases it in the executing read worker's `finally` after its socket
borrow ends. The separate `AsyncNativeIo` never-started callback also
reclaims a lease when an admitted read is discarded during shutdown.
The future's observer `cancel()` cannot forge that physical completion.
The socket read is still bounded by its absolute NIO frame deadline.
Accept admission similarly clears its single-accept flag on a discarded
never-started native worker.

Actor USER writes acquire ownership before bounded outbound admission.
The `WebSocketOutboundGate.Write` retains the lease across queueing,
physical drain, and in-flight cancellation. A queued cancellation,
terminal CLOSE supersession, or connection retirement releases a
**non-started queued** lease; an active write releases it only from
`finish()` after the owning native worker exits its SocketChannel
borrow, even if the public receipt was already cancelled. A queued
native writer discarded before worker startup retires its queued writes.
The CONTROL protocol responses have their own bounded host authority.

Active receipt cancellation now atomically revokes actor ownership
and requests a socket close via the bounded native-I/O CONTROL pool.
It never calls `Socket.close()` on the actor that requests
cancellation. If CONTROL admission fails, a one-shot, per-connection bounded host virtual
thread attempts physical closure; the already-running NIO writer also has
a finite deadline. No physical-loan success is synthesized by this path.

Actor transfer rejects any outstanding read/write owner loan, pending
writer, non-OPEN session, queued USER message, or partially assembled
fragment. `pollAndEnqueueForActor` authenticates and dequeues a USER
message while enqueuing it to a trusted **nonblocking** host scheduler.
The scheduler still must authenticate the handle at the actual actor
delivery point, and must not execute guest code in that callback. The
source regression suite now covers real-loopback actor-bound read/write,
queue-drain transfer fences, stale-handle failures, actor read shutdown,
queued cancellation vs active writer credits, and fragmented USER state.

**Not yet verified:** tests are authored but no exact-head JDK25/Graal
Maven/JUnit steps have executed. The package-private host APIs do not
implement guest-language lowering, full tenant/IsolateVM membership proof,
a native guest delivery scheduler, or native guest execution. Logical
revocation and ownership lease release still do not prove the global
`AsyncNativeIo` actor arena join receipt or socket-fd isolation.

## Authenticated actor lifecycle and scheduler-side delivery (2026-10-31)

The trusted supervisor entrypoint now accepts
`Connection.bindActor(OresContext context, ActorRef actor)` and
`transferActor(context, oldHandle, currentRef, nextRef)`, rather than
accepting a user-provided tenant UUID as production authority. It checks
that the exact OresContext owns the same AsyncNativeIo and the exact
**live actor incarnation** in its ActorRuntime. It derives tenant scope
from `context.contextId()` and rechecks scoped NET permissions. The
context-scoped `readForActorAsync`, `sendTextForActorAsync`,
`sendBinaryForActorAsync`, and `pollAndEnqueueForActor` overloads use
the same validation. Address/UUID-taking methods remain only
package-private *host test seams* and must not be exposed to guests.

`ActorRuntime.isAlive(ActorRef)` now requires the exact current cell
reference and actor generation rather than an ActorId lookup alone.
`ActorRuntime.currentActorAddress()` reads the actual executing
actor-cell identity on its current mailbox-turn carrier. It is empty
outside a real actor turn or after actor retirement.

The trusted binding subscribes to `ActorRef.done()`. A late termination
callback fences only the ActorAddress it observed at binding; a successful
transfer changes the generation so an old done() callback cannot retire
its successor. Once the current actor completes, the callback immediately
revokes logical handle authority and arranges physical socket close on a
bounded native-I/O CONTROL worker, not on the actor/future-notification
carrier. If CONTROL admission is full/closed, the one-shot fallback starts
at most one host virtual close worker per accepted connection (listener
maximum 16). Context/worker/native resource receipts are still
**separate** from logical revocation.

`Connection.dequeueForActor` now produces an opaque, one-shot
`ActorDeliveryTicket`. The ticket retains an owner-generation loan and
one of at most 16 connection-local ticket credits until the scheduler
invokes `deliverOnActor` from the *actual* authenticated actor turn, or
closes the ticket on cancellation/dispatch rejection. Transfer is denied
while any delivery ticket remains pending. The ticket revalidates current
actor runtime, ActorAddress generation, live ActorRef, and the immutable
connection handle immediately before invoking its guest handler. Guest
code does **not** run under the WebSocket lifecycle or ledger monitors.
The existing `pollAndEnqueueForActor` helper remains host-only; the
actual guest scheduler must use the bounded ticket protocol and must
close every discarded ticket. No claim is made that the actor-language
compiler is already wired to this interface.

Added focused host tests for late old-owner termination callbacks,
actor-stop closure of a pending NIO read, physical socket shutdown
following current-owner done(), ticket-vs-transfer fencing, and forged
off-actor delivery rejection. **Authored, not executed:** runner admission
and JDK25/Graal test results remain separate gates.

`OresContext.contextId()` is a **context-level scope identifier**, not
a proof that this host implements the full multi-tenant IsolateVM or
physical heap isolation contract. Higher-level tenant and isolate
membership authorization still needs exact host/guest integration
and independent security verification.

## Real ActorRuntime mailbox integration (2026-10-31)

The host-only WebSocket adapter now connects `ActorDeliveryTicket`
to an actual `ActorRuntime` continuation mailbox. The package-private
`ActorRuntime.enqueueHostActorDelivery(ref, runnable, onRetire)` validates
the exact live `ActorRef` incarnation, reserves the existing bounded
internal continuation credit, and publishes one `MessageEnvelope`
which owns both actor-turn execution and retirement cleanup.

The internal continuation mailbox's normal actor-turn drain uses
`try (MessageEnvelope activeEnvelope = envelope)` so cleanup is run
after successful delivery **or** after an exception from the handler.
The existing STOP queue-drain path closes the same envelope without
executing its continuation, returning the ticket's owner lease and
connection-local ticket credit. A rejected mailbox admission retains
cleanup responsibility with the submitting host, which closes the
ticket once. This is not a new actor-owned thread/pool; the callback
runs only through the actor's original serialized turn executor.

`Connection.dispatchNextActorMessage(handle, context, actor, handler)`
authenticates the full context, owning actor and NET permission,
removes a pending USER frame from the protocol queue, makes a bounded
ticket, and attempts real mailbox admission. The ticket checks the
*current mailbox turn's* runtime, actor identity, generation, live
owner and WebSocket lease immediately before executing the handler.
A transfer cannot proceed with queued ticket obligations. The
package-private `dispatchHostActorMessage` is a **test seam**, not
a guest-callable alternative authority.

If admission fails *after* a USER message was dequeued, the bridge
closes the ticket, revokes the connection's logical authority and
requests physical socket closure on the native host side. Continuing
to accept wire messages after silently dropping the dequeued USER
message is explicitly forbidden. Normal empty-queue polling is not
treated as an admission failure.

New focused fixtures in `WebSocketActorMailboxDeliveryTest` and the
real TCP `WebSocketLoopbackListenerTest` check exact-once execution
on the authenticated actor turn, duplicate-delivery avoidance, STOP
of a queued ticket while an actor handler is running, bounded
continuation admission and 1,024-slot saturation, and fail-closed
connection retirement on mailbox rejection. The focused WebSocket
workflow includes this test suite.

**Not demonstrated:** these are authored host-level tests only.
No JDK25/Graal Maven/JUnit job executed at the exact head because
GitHub Actions runner admission remains unavailable. The compiler
does not yet lower native WebSocket messages into a pure `.ores`
actor mailbox handler; it must use this trusted bridge and keep
all guest lifecycle/tenant/isolate checks intact. Neither host-side
callback delivery nor context identity alone proves physical tenant
heap isolation or WebSocket guest parity on LLVM/.NET/WASM.

## Cancellation checkpoint and scheduler refusal hardening (2026-10-31)

The trusted `ActorDeliveryTicket.deliverOnActor` path now invokes
`ActorRuntime.schedulerSafepoint()` and revalidates its owner-generation
loan immediately before guest handler invocation. A STOP that has
already won the actor runtime's cancellation fence rejects delivery
without guest execution. This is a cooperative boundary; it does not
promise to preempt guest code that has already begun executing.

`ActorRuntime.enqueueHostActorDelivery` now distinguishes an accepted
mailbox envelope from a failed dispatcher submission. After admission,
the mailbox **owns** the on-retirement callback; if scheduling is
rejected, the actor failure path initiates STOP/drain and the caller is
still told the ticket was admitted. The caller never fabricates
ownership return when the queue still contains the envelope.
Fatal JVM/linkage errors are not disguised as ordinary scheduler
rejections.

`WebSocketActorMailboxDeliveryTest` now also includes a host
TurnExecutor-refusal scenario that expects zero guest delivery,
actor failure, and exactly one accepted-envelope cleanup.

**Verification limitations:** 14/14 source-only contract predicates
matched at the exact reviewed head before this documentation edit.
This is *not* proof of compile/test success. The full focused
JDK25/GraalVM Maven/JUnit suite has not executed. The approved
self-hosted runner/CI admission requirements and private test
repository authorization remain independent gates. An attempted
source-free branch creation at the alternate private host was
blocked by the integration safety control. No private source was
mirrored, and no alternate CI success was claimed.

## First actor binding must fence unleased host I/O (2026-10-31)

A trusted actor bind is a one-way capability publication. The connection
now rejects initial `bindActorHost` unless it is OPEN and has **no** raw
host read, active writer, queued outbound USER/CONTROL work, pending USER
message, incomplete fragment, or abort/retirement request. This matches
the subsequent transfer drain gate: the new actor cannot inherit socket
I/O or USER protocol state admitted before any actor-generation loan
existed. A refused bind leaves the host connection unbound so the
supervisor can finish and drain its host-only work first.

Two real-loopback JUnit fixtures assert refusal during an in-flight raw
NIO read, with a queued raw USER message, and with an unfinished raw TEXT
fragment. After host receipt completion and USER drain they validate
successful first binding, handle use, and rejection of raw host APIs.

Source governance is independent of available compute. The checked-in
`.github/private-ci-approved-repositories.txt` currently approves no
cross-organization private source destination without an independent
reviewed exact owner/repository ID. Recent successful source-free runs
in `beamscale/bmscl-compiler` and `usa-acc/usa-acc-e2e` confirm some
private runners executed steps, but do not approve exporting this
private tree. No source mirror was made. All new tests remain
**NOT_EXECUTED** until exact-head JDK25/Graal Maven steps run.

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
