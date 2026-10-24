# Actor-owned direct TCP accept (Java/GraalVM draft)

Status: Java source integration **staged but unverified by exact-head JDK25 execution**.
This is an experimental, loopback-only, trusted-SHARED-actor feature. It does
not provide portable `std/net` parity, production Internet-facing listeners,
HTTP parsing, `SO_REUSEPORT` fallback, or native GraalVM image certification.

## Source contract

A trusted shared worker may create its own independent `SO_REUSEPORT`
listening socket via `network.listen_reuseport("127.0.0.1", port,
backlog, max_connections, max_pending)`, or via
`net_core.listen_reuseport(port, backlog, max_connections, max_pending)`
from `std/net`. The returned `NioTcpListener` is **actor-incarnation-local**.
The guest actor calls `await listener.accept_async()` and obtains an
`NioTcpConnection` owned by that same incarnation, then uses
`read_bytes_async`/`write_bytes_async`/`close_async`.

The supervisor neither accepts these connections nor dispatches accepted
sockets to workers through a mailbox. Each worker binds its own listener
and the kernel selects among those listeners at connection establishment.
Java's shared VM NIO reactor executes bounded nonblocking `accept`
syscalls; the **Oreslang actor does not park an OS thread**.

### Minimal Oreslang source

```ores
define actor Worker as
  receive(ActorMail<int> mail): void {
    val NioTcpListener listener = network.listen_reuseport(
        "127.0.0.1", mail.value, 64, 8, 16);
    val NioTcpConnection connection = await listener.accept_async();
    val Array<int> payload = await connection.read_bytes_async(1024);
    await connection.write_bytes_async(payload);
    await connection.close_async();
    await listener.close_async();
    self.end();
    return;
  }
end
```

A real source-level test is staged at
`src/test/java/dev/oreslang/runtime/SourceActorWorkerAcceptTest.java`;
this document does not claim that test has yet passed.

## Authority and lifetime invariants

1. Bind checks scoped NET permission and the actor's effective
   `IsolatePolicy.Capability.NETWORK` **before** socket creation. Only trusted
   SHARED actor turns may create actor-owned listeners in v1. Supervisor-only
   listeners remain inaccessible to actor turns. No actor may claim a foreign
   actor's listener/connection, even in the same `ActorRuntime`.
2. Every actor-owned listener is tied to its owning `ActorCell.executionDomain`,
   a unique opaque incarnation key, not an actor ID or carrier thread. No
   raw FD or OS socket is guest-visible or mailbox-sendable.
3. A bounded listener registry is admitted atomically against STOP under
   `ActorCell.lifecycleLock`. Registration failure closes the freshly created
   FD. The context owns at most four listeners in this experimental slice.
4. STOP seals the registry, closes every actor-owned listener **outside** the
   actor lifecycle monitor, and waits for each `closeAsync` Future before
   publishing actor DONE or reclaiming its memory region. Close now joins
   the actual per-channel NIO Selector watch and accept/read/write pump leases,
   not merely the cancelled public Future. Retiring accepted connections
   remain charged against the listener's `max_connections` bound until physical
   release. This does not replace sanitizer and adversarial stress validation.
5. The VM selector is shared across listeners; one active accept lease per
   listening socket. Inbound traffic does not imply round-robin distribution
   across workers, and the reactor never executes guest callbacks.
6. Reuse-port support is explicitly opt-in; unsupported platforms fail closed
   rather than silently changing to a supervisor accept/FD handoff path.

## Unproven limitations

- Per-worker listeners require a preselected **nonzero common port**.
  `port=0` on each separate listener binds separate ephemeral ports.
- Only loopback IPv4 is supported by the experimental Java path.
- Listener `socket()/bind()/listen()` configuration is still invoked
  synchronously when an actor calls `listen_reuseport`. Although accepted
  connections, reads and writes use nonblocking readiness, this bind path is
  **not yet a strict carrier-free listener bootstrap**. A future control-plane
  provisioning Future must move bind/syscall setup off actor carriers before
  claiming strict nonblocking I/O end to end.
- Actor owner-checked listeners are not generally transferable with `rt take`
  to a *different* actor. The first version uses actor-local socket creation,
  with no cross-actor FD passing or shared listener broker.
- Private/isolated and untrusted actors are explicitly denied. They need
  separately verified physical heap and sandbox/resource lifetime admission.
- Backpressure, per-connection bounded protocol parsing, TLS, streaming, worker
  death under sustained traffic, and GraalVM Native Image AOT remain distinct
  qualification tasks.
- The LLVM backend still needs `std/net` guest lowering and exact-incarnation
  native descriptor ownership; host `SO_REUSEPORT` alone is not backend parity.

## Gates before release

- JDK25 Maven `-Dtest=SourceActorWorkerAcceptTest,ActorOwnedNioListenerLifecycleTest,NioChannelPhysicalRetirementTest,VmNioWorkerReusePortTest,VmNioTcpListenerTest test`.
- Executed, nonempty exact-PR-head BUILD/TEST steps (not merely GitHub's
  workflow failure record), then repeated concurrent stress, actor STOP
  cancellation races, foreign actor denial, and leak accounting.
- Differential Graal/LLVM `.ores` fixtures, LLVM sanitizers and network
  load tests, native-image compilation, and production security review.
