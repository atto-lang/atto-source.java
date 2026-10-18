# Transport actor declaration admission (2026-10-18)

**Status: Java parser/AST admission and fail-closed type/runtime guard, NOT an
executable HTTP, IPC, TCP, FFI, proxy or WebSocket actor adapter.** Do not advertise
source-network actors as implemented merely because their declarations parse.
This document extends `ACTOR_UNARY_STREAM_TRANSPORT_API_V1.md` and does not
override its runtime acceptance gates.

## Semantic axes

A declaration contains independent, serialized attributes:

| Axis | Values | Meaning |
| --- | --- | --- |
| Actor isolation | shared/private/untrusted | memory/capability domain; not a transport |
| Reply model | stream (default), unary | `receive` vs exactly-one `run` response |
| Transport | actor mail (default), http, ipc, tcp, ffi, proxy, websocket | runtime-owned adapter |
| Direction | local, inbound, outbound, duplex | what external traffic it receives/initiates |
| Admission | serial or explicit bounded quotas (future) | outstanding logical work, not native thread count |
| Requirements | `requires` names | capabilities that must be proven/granted |
| Effects | `with` names | upper bounds that must be checked on caller/handler/adapter |

`serial` currently describes the existing single logical actor mailbox
admission contract. It **does not** authorize physically parallel actor
executions. Explicit multi-carrier execution is a separate proposed feature
requiring verified actor state loans, memory ownership and scheduler fences.

## Canonical source forms (Java parsing only; transport execution gated)

```ores
define http actor InboundServer as end

@Outbound
define http actor OutboundClient
    requires(net.connect, tls.client)
    with(io, suspend)
as end

@Duplex
define tcp actor Session requires net.listen with io, suspend as end

define ipc actor Agent as end
define ffi actor ForeignWorker as end
define proxy actor RemoteWorker as end
define websocket actor SocketPeer as end
define unary http actor Handler as end
define serial unary http actor ExactlyOne as end
```

A transport actor without a direction annotation defaults to **inbound**
for HTTP, IPC, TCP and WebSocket, **outbound** for FFI and proxy.
These are descriptive defaults, not a listener bind or a remote connection.
`@Inbound`, `@Outbound`, and `@Duplex` are mutually exclusive,
zero-argument actor annotations. Other actor-level annotations
(`@Route`, `@Bind`, `@Codec`, `@Retry`, `@Remote`, `@Ffi`,
`@TransportDirection`, etc.) must be *rejected until their typed semantics
and lowering are installed*. Never retain them as ignored decoration.

`requires` declares capabilities (e.g. `net.listen`, `net.connect`,
`tls.client`, `process.spawn`, `ffi.call`, `ipc.connect`).
`with` declares effect bounds (e.g. `io`, `suspend`, `network`).
Both clauses accept comma-separated dotted names, optionally parenthesized.
Duplicate clauses, duplicate members, contradictory modifiers and
contradictory direction annotations are syntax errors.
Because the current Java effect checker does not prove these declarations,
**all nonempty `requires`/`with` clauses fail at type checking**.
No decorator/annotation can grant an unstated capability.

`unary` is orthogonal to HTTP and stream transports. Unary IPC/TCP/
WebSocket will need an explicit versioned envelope, correlation, framing,
timeouts and exactly-one terminal outcome before executable admission. A TCP
read is not one request. WebSocket message frames are not RPC correlation.

## Transport-specific safety gates

- **HTTP inbound:** listener owns socket and exchange. The actor receives
  a minted scoped request façade, not a raw FD or forgeable `HttpExchange`.
  Streaming has one response writer; unary has one encoded return with no
  manually committed writer. Request completion and cancellation fence old
  response generations.
- **HTTP outbound:** a client is distinct from a server route. `net.connect`,
  TLS policy, redirect/SSRF controls, DNS capabilities, deadlines, connection
  reuse, byte budgets and response disposal are validated explicitly.
- **IPC:** local authentication, peer identity/credentials, rights/capability
  transfer, framing, quotas and cleanup precede actor dispatch. Untrusted
  actors never receive raw privileged handles.
- **TCP:** byte-stream framing is explicit; reads/writes cannot assume
  packet boundaries. Half-close, EOF, backpressure, owner/lease lifetime,
  cancellation and write ordering must be proved.
- **WebSocket:** handshake origin/auth/TLS, fragmentation, max frame and
  message lengths, UTF-8 checks, ping/pong, close ordering, and one terminal
  close are owned by the transport adapter.
- **FFI:** pin/ownership of native memory, thread affinity, ABI/version,
  reentrancy policy, traps/panics and callback lifetime are proven. FFI
  calls may block and cannot occupy an actor carrier without declared
  effects and an executor bridge. No foreign pointer enters ActorMail.
- **Proxy:** represents a remote actor identity, **not** a new instance of
  the local actor. Endpoint/auth, schema/codec agreement, actor incarnation
  and generation fencing, replay protection, deduplication and bounded
  credits are verified before forwarding.
- **Untrusted isolation:** all payloads and leased capabilities are scoped,
  copied/serialized under budget and never derived from remote flags alone.

## Compiler checks

1. Lex actor prefixes contextually; ordinary variables/functions called
   `http`, `serial`, `proxy`, etc. remain legal elsewhere.
2. Preserve every protocol dimension through AST transforms, annotations,
   imports, linkers, incremental ABI hashing, JIT and native lowering.
3. Reject unknown/duplicate annotations, protocol conflicts, unverified
   effects/capabilities, unbound external protocols, and unsupported unary
   handlers **before** spawn, I/O, capability transfer or reply-slot creation.
4. Runtime independently rejects specialized actors if a path bypasses
   type checking. No fallback from HTTP/TCP/etc. to ordinary mailbox actor.
5. Require an actual adapter and an authorized bind contract before
   flipping each protocol/role combination from `NOT_EXECUTABLE`.
6. Prove stream/unary handler signatures across inherited declarations,
   including private runtime-only dispatch. Ordinary callers never invoke
   `ref.receive()` or `ref.run()`; only `send`/`request` are public.
7. Test both backends with the same valid/invalid source fixtures and
   differential negative tests. A parser acceptance test does not count
   as a transport runtime success.

## Current integration status

- Java `main` before this patch: neither full declaration grammar nor
  runtime transport dispatch was available.
- This audit branch: parses and retains the semantic dimensions; emits
  explicit compile and runtime denial until a real adapter/effect checker
  is wired. Plain mail actors retain their existing path.
- LLVM `main`: narrowly scoped source actors; no source-level transport
  guest adapter parity. Native socket primitives and C++ unit tests do
  not establish `define tcp actor` executable support.
- Older stacked #543/#545/#546/#547 work is reference implementation
  material, not evidence of current-main support. Reconcile it
  semantically and preserve the newest compiler/runtime gates.

**Promotion criterion:** exact-tree CI with JDK25 Maven + source guest
execution, native compiler/CTest/sanitizers, bad-input and cancellation
tests, no runner-allocation errors and explicit documented per-protocol
status. Keep this work unmerged if those gates have not run.
