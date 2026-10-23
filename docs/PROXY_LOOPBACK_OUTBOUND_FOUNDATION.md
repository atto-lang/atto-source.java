# Loopback outbound HTTP host foundation (not a guest API)

## Scope

`ProxyOutboundLoopback` is a trusted Java backend transport **proof**, not a production reverse proxy, an unrestricted `HttpClient` API, or a working Oreslang `http.request_async` guest binding. It is intentionally limited to direct HTTP/1.1 requests to literal `127.0.0.1:<port>` with canonical paths, GET/HEAD methods, no credentials, no alternate proxy, no DNS resolution, and no redirect following. HTTPS, arbitrary remote egress, CONNECT, client-supplied request headers/body, host/transport response headers, and request forwarding from guest Oreslang are not enabled.

## Verified foundation

- A nonblocking `request` start returns `CompletableFuture<Response>`; `Semaphore.tryAcquire` enforces a configurable 1..1024 bound and rejects immediately on overload.
- A successful response preserves its upstream status and repeated response fields, including distinct `Set-Cookie` values. It filters standard hop-by-hop and transport-owned response fields and fields named by `Connection`. Invalid field values and header totals over 32 KiB/100 entries fail closed.
- A single-consumer streaming helper copies bytes using an 8192-byte read buffer and checks cancellation before read and write. The downstream `OutputStream` remains caller-owned. All blocking body I/O must run on a transport I/O worker, never an actor carrier.
- Closing a response is idempotent and releases its concurrency permit exactly once. A canceled future cancels the upstream request; late response arrival closes the response if the downstream answer is already terminal. A real cancellation/timeout/close interleaving stress suite remains necessary before runtime integration.
- Redirects are not automatically followed. Host-only code does not gain Oreslang mailbox sendability or unrestricted network authority.

## Exact-source JDK21 validation

`bash scripts/test-proxy-outbound-loopback.sh` compiles the two actual Java files with `javac --release 21` and executes five successive runs of both the client/authority checks and a separate end-to-end listener-to-upstream forwarding wire test. The regression checks 201/404/204/302 status fidelity, repeated `Set-Cookie`, hop-by-hop header stripping, bounded admission under an unreleased response, 2 MiB streamed body, forbidden authorities/credentials/encoded path/HTTPS and redirect refusal, response cancellation/permit reclamation, HEAD semantics and real end-to-end host-only proxy forwarding (201/404/302, preserved Set-Cookie, hop-by-hop stripping, 2 MiB stream).

Five consecutive local JDK21 runs passed on the following exact committed Git blobs, independently matched to tested bytes:

- `src/main/java/dev/oreslang/runtime/ProxyOutboundLoopback.java`: `ab9f4828e96ec85adac9f526a5ea203cbaa3872f`.
- `src/test/java/dev/oreslang/runtime/ProxyOutboundLoopbackCheck.java`: `2b4580dd591a2aa959df6b535841c4f0570fb271`.
- `src/test/java/dev/oreslang/runtime/ProxyLoopbackForwardingWireCheck.java`: `03d2e5722696c1ed0b58aa60e351bbe1ea8b80cb`.
- `scripts/test-proxy-outbound-loopback.sh`: `31e0bb351b2b1894226c13c19ca8f346e910b833`.

The workflow `.github/workflows/proxy-outbound-loopback.yml` runs the same pair of real-wire tests in the **source repository** on GitHub-hosted Java 21. In the observed PR run the workflow failed before any job steps executed, so it does not supply compiler evidence. A queued or unexecuted job is **not** proof of CI success. No cross-organization private-source mirror is authorized by this work.

## Next runtime gates

1. Integrate the transport with context-owned `AsyncNativeIo` and strictly capability-bound request/stream ownership, cancellation, terminal actor drain and mailbox backpressure.
2. Replace the loopback-only target admission with a reviewed destination capability that resolves and **pins** addresses at connection creation; enforce egress policy, private/metadata ranges, TLS authority/identity and DNS rebinding protection, including connection reuse. Do not merely widen this helper's hostname filter or rely on a preflight DNS lookup before a second client resolution.
3. Add a typed Oreslang outbound API returning status, complete headers, streamed body, and explicit close/cancel semantics; conform its lifetime to actor-owned arenas and scheduler domains.
4. Add a real inbound-to-outbound HTTP/1.1 response adapter with validated hop-by-hop stripping, framing, HEAD semantics, error-before-commit behavior, cancellation and connection pool/timeout limits.
5. Implement CONNECT separately with bounded bidirectional socket streams and half-close handling; expand adversarial real-wire tests.
6. Verify full Java/Graal Maven/JUnit, guest compilation and Java 25 AOT/hybrid on an authorized runner. Standalone Java21 socket proof is **not** that evidence.

See [transport issue #1942](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1942) and [CI security issue #1501](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1501). The native proxy applications must remain Oreslang-only.

## Explicit boundary of the forwarding demonstration

`ProxyLoopbackForwardingWireCheck` creates a Java `HttpServer` listener, a separate upstream loopback `HttpServer`, and a Java virtual-thread test adapter that forwards GET requests through `ProxyOutboundLoopback`. This is **test fixture code only**, not Oreslang source, an actor/middleware pipeline, or a deployable proxy listener. It demonstrates that the outbound primitive can preserve actual HTTP status/headers and stream 2 MiB end-to-end without exposing arbitrary egress.
