# Strict async standard-library I/O

The Java/Graal runtime treats **blocking host I/O as forbidden on guest-reachable
core/stdlib paths**. Moving a blocking syscall to a virtual thread, worker pool,
or dedicated thread does not make it a compliant backend.

Guest async APIs return native Oreslang `Future<T>` values. `await` suspends the
Oreslang continuation; selector/completion code settles the Future and the owning
scheduler decides when guest code resumes. Selector threads never execute guest
callbacks.

## Current Java/Graal status

| API | Current result |
| --- | --- |
| `network.listen_nonblocking("127.0.0.1", ...)` | real VM-shared selector listener |
| `NioTcpListener.accept_async()` | real `Future<NioTcpConnection>` |
| `network.connect_async(ipv4, port)` | real selector-backed `Future<NioTcpConnection>` for canonical numeric IPv4 |
| `NioTcpConnection.read_bytes_async(max)` | real bounded nonblocking read |
| `NioTcpConnection.write_bytes_async(bytes)` | real bounded nonblocking write |
| `NioTcpConnection.close_async()` | real descriptor close plus physical operation drain |
| `fs.*_async` | fail-closed Future until a strict platform provider exists |
| async stdio/stdin | fail-closed Future until a strict platform provider exists |
| async HTTP client/server transport | fail-closed Future until rebuilt on strict TCP/TLS |
| hostname DNS | unsupported; hostnames are rejected before connect admission |

Synchronous guest filesystem, stdio, legacy TCP and legacy HTTP transport names
are not compatibility paths. They are rejected by the compiler/runtime boundary.

`File` aliases `fs`; `net` aliases `network`. The packaged `std/net`
module exposes the staged selector-backed TCP surface, including
`connect_ipv4(host, port)`. Its explicit IPv4 name is intentional: hostname
resolution must not silently call blocking DNS.

## TCP execution model

All Java TCP readiness uses the owning `OresVM`'s `NioReadinessGroup`.
Listeners and connections own descriptors, not selector threads. The first
accepted watch lazily starts the first reactor; additional idle sockets do not
create one thread per socket.

Outbound connect uses a nonblocking `SocketChannel`:

1. the runtime parses a canonical ASCII numeric IPv4 literal without name lookup;
2. `SocketChannel.configureBlocking(false)` is required;
3. `connect()` is attempted once;
4. an incomplete connect registers `SelectionKey.OP_CONNECT`;
5. readiness completion calls `finishConnect()` off the selector stack;
6. success transfers the still-nonblocking channel into a context-owned
   `NioTcpConnection`;
7. refusal/error closes the descriptor and fails the Future.

There is currently one conservative active kernel-operation lease per TCP
channel. A second same-channel read/write operation is rejected until the first
operation physically drains. Full duplex read/write admission is a later
hardening slice.

## Cancellation and physical retirement

Cancellation is fail-closed around kernel side effects:

- accept cancellation revokes the one-shot accept watch before an accepted
  descriptor is exported;
- connect cancellation may win before `finishConnect()` claims completion and
  closes the pending descriptor;
- read cancellation may win only before kernel data consumption is claimed;
- write cancellation may win only before any bytes are committed;
- close revokes still-cancellable operations and physically closes the descriptor.

A Future reaching a terminal state is not by itself a resource-retirement proof.
Readiness watches and connection/listener operation leases remain owned until
their terminal runtime notifications drain.

Context shutdown fences new NIO admission, cancels pending outbound connects,
closes outbound connections and listeners, drains the VM readiness domain while
completion carriers still exist, then retires actor/native runtime domains.

## Unsupported providers

Filesystem, terminal/stdin/stdout, DNS and HTTP/TLS remain unsupported wherever
the only available implementation would hide blocking work behind another
thread. They must stay fail-closed until a readiness/completion implementation is
qualified for that target.

In particular:

- `AsyncNativeIo` is migration/reference code, not a strict guest transport;
- Java `HttpClient.send` / legacy JDK `HttpServer` are not strict guest paths;
- `Task.Run`, virtual threads and executor offload are not substitutes for
  kernel/host asynchronous I/O;
- regular-file output must not be mislabeled as readiness-driven pipe/socket I/O.

## Validation

The source tree contains focused tests for selector accept/read/write/close,
outbound connect success/refusal, unresolved-address rejection, cancellation and
re-arm, one-carrier scheduler progress, context lifecycle, and strict compiler
rejection of blocking guest names.

The Rust `tools/stdlib-boundary-audit` gate scans packaged `.ores` modules and
Java guest-dispatch/typechecker boundaries. It rejects synchronous stdlib I/O,
`*_blocking` / `*_sync` aliases, known legacy Java transport edges, and Java
Unicode-escape preprocessing in strict audit targets.

A GitHub Actions failure with zero executed steps is **not** runtime test
evidence. Merge qualification still requires executed exact-head JVM plus
Native Image evidence for the private source tree.
