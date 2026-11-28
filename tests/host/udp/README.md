# Standalone Java UDP bridge physical-loopback test

Execute from the repository root with JDK 21 or newer:

```bash
bash tests/host/udp/run.sh
```

This compiles the **actual production** `AsyncNioUdp.java` and
`UdpDatagramKernel.java` source files in isolation, with narrow
**test-only** shims for `OresContext`, `OresFuture`, `OresVM`,
`NioReadinessGroup`, and the permission enums. It sends and receives
real IPv4 loopback UDP datagrams and verifies source-port identity,
owned byte snapshots, deny-before-guest-read, invalid bytes, overlapping
I/O admission, cancellation/close/rebind, and forty send/receive cycles.

The test also uses a latch to pause an actual selector callback inside
the trusted receive permission gate, cancels its Future from another thread,
and verifies that the operation slot is **not** released before physical
execution leaves the callback. It verifies the cancelled result cannot be
republished afterward.

**Proof boundary:** This is a JDK host-side bridge test, **not** execution
against the repository's real scheduler, future, reactor, Truffle compiler,
actor lifecycle, or a linked `.atto` guest. Do not count it as those tests.
The independent `AttoAsyncUdpGuestTest` and
`AttoUdpNamespaceImportGuestTest` must still run on a JDK 25/GraalVM
runner and prove a guest 42 result. Do not put the shim classes on Maven's
main/test compilation source path; the script isolates them from production.
No network dependency, GitHub tokens, or cross-org checkout is required.
