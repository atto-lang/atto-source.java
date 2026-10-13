# Java/GraalVM NIO network kernel substrate

Status: low-level host proof only. Native Oreslang std/net and HTTP/1.1
source are being implemented separately. This class is **not guest-visible**.

\`NativeNioTcpKernel\` uses JDK nonblocking \`ServerSocketChannel\` and
\`SocketChannel\` plus \`Selector\` readiness. It never creates or calls the
JDK HttpServer, java.net.Socket input/output streams, synchronized blocking
connection I/O, an implicit per-request virtual thread, or a guest callback.
\`poll\` may park only a dedicated I/O reactor thread; it explicitly rejects
actor execution carriers. Accepted channels remain nonblocking.

This prototype does not yet implement Oreslang-owned capability handles,
listener/connection authority checks, the ROOT-carrier exclusion proof,
fixed-size buffer leases, cancellation/close fencing, backpressure quotas,
deadline-wakeup arbitration, registration wakeups, bounded event queue,
thread-safe cross-thread registration or continuations. It is tested only as
a standalone host primitive, not production async TCP.

No source-visible operation may use this primitive until those guarantees and
Java/LLVM conformance tests are in place. The existing NativeHttp and
AsyncNativeIo implementations still use blocking-style Java I/O and must not
be described as a strict nonblocking transport.
