# ProxyActor: actor identity across process and machine boundaries

Status: **design contract / not an executable Oreslang feature** (2026-10-10).
This document proposes a source-level proxy actor declaration, compiler contracts,
and a native transport protocol. No existing ActorRef network/Ipc backend may be
represented as providing these guarantees until corresponding CI and fault tests
pass.

## Motivation

A local `ActorRef<T>` represents a local actor mailbox capability. Remote or
out-of-process actors need a **locally scheduled proxy actor** that participates
in the normal OresVM supervision model, discovers an endpoint, authenticates
the *remote actor incarnation*, and forwards messages/results/streams without
executing the remote worker implementation locally.

A ProxyActor is **not the remote actor**: local proxy identity, destination
service identity, remote actor identity, connection identity, and the selected
transport are all distinct.

## Proposed source syntax

```oreslang
define contract Transformer as
    pub async fnc transform(Data input): ResultData;
end

// Declaration: no user-written receive/forward/reverse implementation needed.
// Parser, type checker, resolver and backend work are still required.
define proxy actor TransformProxy
    conforms to Transformer
    discover "workers.transform.v1"
    transport auto
as
end

pub fnc build_pipeline(): ActorStream<RawData, ResultData> {
    return spawn RawReader()
        pipe spawn TransformProxy()
        pipe spawn ResultWriter();
}
```

`discover` is a **service key**, never an unvalidated executable URL; routing
to an endpoint requires trusted discovery and a negotiated authenticated
handshake. `transport auto` selects only transports allowed by the
deployment policy and security boundary. The user may constrain it to
`ipc`, `tcp`, or `http2` with no silent fallback to weaker authentication.
This proposed grammar intentionally preserves `|>` for eager function application
and `pipe` for actor-stream topology.

`spawn TransformProxy()` should return `ActorRef<TransformProxy>` immediately.
`await ref.ready` settles only after local proxy initialization,
endpoint discovery, authenticated remote handshaking, actor identity/contract
version negotiation, and stream-credit setup. It should not mean that an
unhealthy destination will never subsequently fail. The proxy must have
a distinct state machine and bounded retry budget.

`forward()` (local -> remote) and `reverse()` (remote -> local) are internal
protocol paths, not automatically public actor methods. Application-facing
methods are taken from `Transformer`, including typed mail and stream ports.
The proxy must not provide a bypass around a contract or capability check.

## Identities and fences

```text
local ProxyActorRef ID
  |   service key / authority / capability
  v
resolver => endpoint lease(s) => authenticated connection
                                      |
                                      v
                    remote ActorId + incarnation + contract hash
```

Address and session **must not** stand in for remote actor identity.

Use an immutable `RemoteActorAddress` comprising logical
namespace/tenant, service, remote actor ID, incarnation/epoch, and contract
schema hash. An endpoint lease additionally includes endpoint URI, transport,
issued-at/expiry, authority and monotonically checked routing generation.

When reconnecting, the proxy either (a) resumes an authenticated session
to the **same actor incarnation**, preserving the negotiated stream state, or
(b) reports a terminal actor-replaced outcome and requires an explicit
supervisor/rebind policy. It must never silently route a pending command
to an unrelated instance merely because discovery now points elsewhere.

Use separate unguessable operation IDs and stream IDs. A remote request ID is
scoped to the actor incarnation, session epoch and endpoint protocol version.
Sequence numbers are per *stream*, not global to all actors. Wraparound,
replay and duplicate acknowledgments must be tested.

## Lifecycle state machine

```text
NEW -> DISCOVERING -> CONNECTING -> AUTHENTICATING -> READY
            |                           |             |
            +------> FAILED <-----------+             v
                                                  DEGRADED
                                                      |
                                             reconnect same ID
                                                      v
                                                  READY
                                                      |
                                            close / cancel / stop
                                                      v
                                                  DRAINING
                                                      |
                                                      v
                                                     DONE
```

Each transition requires a generation fence so late connection results cannot
restore an old session after cancellation or an endpoint rollover. Local
proxy lifecycle `done` is distinct from remote actor `done`; the latter can
be exposed via a supervised remote-status stream. A disconnected proxy does
not prove the remote actor is dead. Actor parent cancellation closes transport
admission, cancels local pending operations, revokes delegated capabilities
and drains work as its policy allows.

Lifecycle/control frames must remain independently admissible even when a
data channel is full. There can be only **one native thread executing the
proxy actor's code at a time**, including reconnection callbacks; transport IO
threads enqueue mailbox turns and never invoke proxy guest code directly.

## Native transport and service discovery

Providers (pluggable):
- `static` for tests and fixed topologies;
- local OS-process registry + Unix domain socket for same-machine IPC;
- DNS SRV, orchestrator/service registry, or authenticated control plane for
  other hosts.

Backends (pluggable):
- Unix domain socket/pipe with peer credential checks and capability fencing;
- framed TCP **with mutual TLS** for untrusted networks;
- HTTP/2 streams for existing HTTP infrastructure; HTTP and TCP still use the
  same actor-envelope semantic protocol. HTTP/1 request/response is not
  automatically a bidirectional ordered stream.

Discovery results are untrusted until endpoint identity/tenant authorization,
certificate/peer identity, remote ActorId incarnation and contract hash are
validated. No SSRF via arbitrary discovered URLs; apply address-policy
allowlists and DNS rebinding protections. No fallback from authenticated
TCP to unauthenticated plaintext. Forward portable serialized data, not raw JVM
object references, bare file descriptors or unrestricted memory capabilities.

Transport selection is *deployment policy*, not part of ordinary application
logic. IPC/TCP/HTTP2 parity is proven against the same protocol conformance suite.

## Versioned frame envelope

Every frame has:
- protocol version, message kind, actor namespace/ID/incarnation;
- session epoch + stream ID + request ID + per-stream sequence;
- content schema/codec/version (e.g. msgpack/protobuf), payload bytes and size;
- deadline, trace/correlation fields, per-hop delegated authorization scope;
- credit window, acknowledgment level and optional idempotency key;
- integrity/authentication via the negotiated secure transport.

Wire kinds: HELLO, READY, DATA, CREDIT, ACK, EOS, ERROR,
CANCEL, HEARTBEAT, CLOSE. Acknowledgment must distinguish accepted,
enqueued, executed, and durably committed **if** the backend can prove each.
No exactly-once semantics are implied by a single ACK, an open TCP connection,
a reconnect, or an idempotency-key header.

## Streaming and backpressure

A proxy can appear in the middle of an `ActorStream<I,O>` chain.

Per-stream bounded windows, explicit CREDIT, queued byte limits, operation
quotas and cancellation keep both memory and transport queues bounded.
Do not acknowledge credits merely on TCP read: replenish credits only after
downstream admission (or documented durable persistence). Preserve order per
stream, with a clearly declared policy for multiple message lifetimes in one
Oreslang actor. No total order among distinct actors or stream IDs.

On link failure, unacknowledged operations are **outcome unknown** unless a
durable exactly-once protocol exists; retries are opt-in for idempotent,
replayable operations. Fatal errors propagate through `trap`/Option/Error
contracts and terminate or supervise the pipeline as configured.

PRIVATE/UNTRUSTED destination changes require serialization/copy and
capability validation; SHARED zero-copy is local-only and must never be
silently extended across process or machine boundaries.

## Implementation dependency order

1. Actor `StreamStage<I,O>` typed ports, local `pipe` graph and bounded
   suspension-aware mailbox/output credit path.
2. Proxy declaration parsing and contract hash generation; compile-time
   validation of available discovery/transport policies.
3. A native in-process fake transport and deterministic lifecycle/credit
   protocol model (prefer Rust or Oreslang, not a new Java userland API).
4. Unix domain IPC backend with same-host worker integration tests.
5. Authenticated TCP and HTTP/2 adapters plus service-discovery providers.
6. Fault injection: pause, partition, reorder, duplicate, stale lease,
   actor restart, reconnect, malicious discovery, cancellation and shutdown.
7. Exact-tree verification on a funded GitHub Actions runner; only promote
   verified backends and supported actor kinds.

Acceptance criterion: a source program may freely substitute a local stage
with a ProxyActor implementing the same typed stream contract, without changing
`pipe` syntax. This does **not** promise identical latency, ordering across
different remote streams, delivery during a partition, or exactly-once effects.
