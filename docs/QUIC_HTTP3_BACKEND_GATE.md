# QUIC/HTTP3 Java/GraalVM native boundary — incomplete

Status: **fail-closed admission + tests only**, not QUIC network support.

`NativeQuicAdmission` binds a future UDP/QUIC provider to `OutboundEgressPolicy.ResolvedTarget`. It preserves the permitted IP, canonical SNI hostname, `h3` ALPN and DNS-resolution generation. It must not resolve the hostname again after authorization. Negative tests reject mixed permitted/denied DNS answers, http→h3 promotion, wildcard peers and h2-as-h3.

The JDK built-in HTTP/2 client and `HttpServer` are TCP / HTTP/1.x-or-2 primitives; neither supplies QUIC. Implement a native-image-compatible audited QUIC/TLS 1.3 provider, then expose Oreslang-source `std/net/quic`, plus distinct `std/http3` framing/QPACK. All guest operations need quota/buffer limits, native-I/O loans, physical retirement receipts, cancellation, deadlines, actor-generation fencing and exact-head interop validation against an independent implementation. No guest entrypoint may currently claim QUIC availability, and there must be no hidden HTTP/2 fallback.

Shared contract: `oreslang-common-source/contracts/v1/quic-net-v1.json` (companion draft).
