# QUIC/HTTP3 Java/GraalVM native boundary — fail-closed hardening

Status: **policy-pinned host preflight only**, NOT guest QUIC or HTTP/3 support.

The `NativeQuicAdmission` host-only factory consumes an `OutboundEgressPolicy.ResolvedTarget` for an individual physical connection. The original authorized DNS set and resolution generation are preserved, and the selected IP is used directly instead of re-resolving a hostname. Only HTTPS + ALPN `h3` is admitted. A selected address is a strict nonnegative index inside the authorized set (no modulo wrap). Canonical DNS TLS identity is required; invalid IDN, non-lowercase, trailing-dot, multicast and unspecified destinations are rejected.

`PinnedPeer` is an immutable class with a **private constructor**. This prevents arbitrary package-local runtime code from minting apparently authorized tokens. The separate `ResolvedTarget` policy object remains trusted-runtime-only and is NOT an unforgeable guest capability. Future adapters must check the source actor/tenant and policy generation at the moment of UDP connection, and must not expose any peer descriptor to untrusted guest code.

**Evidence:** The exact committed Java `NativeQuicAdmission.java` Git blob was compiled and exercised under JDK 21 with a synthetic `OutboundEgressPolicy` stub. This proves syntax and local fail-closed descriptor behavior **only**. It does not execute the repository's real DNS policy/JUnit suite, Native Image, TLS handshake, UDP transport, QUIC streams, QPACK or guest stdlib.

Before enabling the guest `std/net/quic`, implement an audited Native Image compatible QUIC/TLS 1.3 engine with bounded asynchronous UDP, native I/O loan generation, actor cancellation/physical retirement, connection credit limits and independent peer interoperability. `std/http3` is separate from QUIC transport. HTTP/2 over TCP MUST NOT stand in for QUIC.

Any external funded GitHub Actions test carrier must be privately governed with explicit source snapshot and destination authorization, independent review, protected `main`, exact Git-tree integrity and nonzero executed job steps. Zero-step source Actions failures do NOT count as test executions.
