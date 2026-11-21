# Strict NIO TCP client routing contract v1

This contract defines the first outbound TCP path that is valid when
`ores.runtime.io.mode=nio-only`.

## Routing

`NativeIo.connectAsync(...)` always uses the owning VM's
`NioReadinessGroup` through `VmNioTcpClient`.

The source surface exposes:

- `network.connect_async(host, port) -> Future<NioTcpConnection>`;
- `NioTcpConnection.read_bytes_async(maximum) -> Future<List<int>>`;
- `NioTcpConnection.write_bytes_async(bytes) -> Future<int>`;
- `NioTcpConnection.close_async() -> Future<void>`.

`NioTcpConnection` is a distinct affine runtime handle from the legacy
`TcpConnection`; the compiler does not conflate blocking and selector-backed
capabilities.

The legacy `NativeIo.connect(...)` / source `network.connect` path owns
`java.net.Socket` or worker-backed compatibility state and can block. It
remains available only in `legacy-compat` mode. In `nio-only` mode it fails
before socket work is admitted.

The generic `AsyncNativeIo` virtual-thread worker path is likewise rejected
before admission in strict mode. JDK `HttpServer` and synchronous
`HttpClient.send` remain fail-closed until HTTP is rebuilt on the VM-owned
TCP transport.

## DNS boundary

The first NIO client contract accepts numeric IPv4 literals only.

Java hostname resolution may perform blocking resolver work. Strict mode must
not claim nonblocking TCP while silently resolving a hostname synchronously.
Hostname and IPv6 support therefore require a separately bounded asynchronous
resolver contract.

A numeric address is parsed without DNS before `SocketChannel.connect`.

## Connect lifecycle

Each connect owns:

- one nonblocking `SocketChannel`;
- at most one one-shot `OP_CONNECT` readiness watch;
- one bounded host connect-deadline admission;
- one terminal `OresFuture<Connection>`.

The connect deadline is delivered by a daemon host timer that performs no
guest work. At most 1,024 live connect deadlines exist process-wide. Timeout,
cancellation, failure and success all release that admission exactly once.

A successful connect transfers the channel to one VM-owned client connection.
A failed or cancelled connect closes the descriptor before its terminal
notification drain is treated as retirement.

## Connection ownership

The initial client connection contract admits one outstanding read or write
operation at a time. Concurrent duplex operations remain fail-closed until a
separate read-lease/write-lease ownership protocol is specified.

Read and write operations:

- attempt bounded nonblocking syscalls directly;
- use one-shot `OP_READ` / `OP_WRITE` watches only when the syscall would
  block;
- never run guest code on the selector thread;
- revoke their watch on cancellation;
- release operation ownership only after terminal notification drain.

EOF is represented by an empty read result.

## VM shutdown

Every pending or established outbound client belongs to one `OresVM`.

VM close:

1. fences new NIO admission;
2. closes VM-owned listeners and outbound clients;
3. cancels pending connect/read/write watches;
4. closes descriptors;
5. stops and joins the readiness reactor;
6. proves listener and client retirement;
7. only then tears down root and CONTROL carriers.

## Evidence requirements

Focused tests cover:

- strict-mode client/server echo on one shared VM selector;
- rejection of hostname/DNS fallback before reactor admission;
- invalid port/deadline bounds;
- VM close while a client read is pending;
- single-operation connection ownership and cancellation reuse;
- strict rejection of legacy blocking connect and virtual-thread I/O work.

These are authored tests until an exact-head source-org job actually executes
them. A workflow with zero executed steps is runner admission/infrastructure
failure, not a failed TCP/NIO test.

Alternative-org SOURCE-FREE qualification remains a distinct runner/security
signal and is not private-source compiler/runtime evidence.
