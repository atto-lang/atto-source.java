# Loopback outbound HTTP host foundation (not a guest API)

## Scope

`ProxyOutboundLoopback` is a trusted Java backend transport **proof**, not a production reverse proxy, an unrestricted `HttpClient` API, or a working Oreslang `http.request_async` guest binding. It is intentionally limited to direct HTTP/1.1 requests to literal `127.0.0.1:<port>` with canonical paths, GET/HEAD methods, no credentials, no alternate proxy, no DNS resolution, and no redirect following. HTTPS, arbitrary remote egress, CONNECT, client-supplied request headers/body, host/transport response headers, and request forwarding from guest Oreslang are not enabled.

## Verified foundation

- A nonblocking `request` start returns `CompletableFuture<Response>`; `Semaphore.tryAcquire` enforces a configurable 1..1024 bound and rejects immediately on overload.
- A successful response preserves its upstream status and repeated response fields, including distinct `Set-Cookie` values. It filters standard hop-by-hop and transport-owned response fields and fields named by `Connection`. Invalid field values and header totals over 32 KiB/100 entries fail closed.
- A single-consumer streaming helper copies bytes using an 8192-byte read buffer and checks cancellation before read and write. The downstream `OutputStream` remains caller-owned. All blocking body I/O must run on a transport I/O worker, never an actor carrier.
- Closing a response is idempotent and releases its concurrency permit exactly once. A canceled future cancels the upstream request; late response arrival closes the response if the downstream answer is already terminal. A scope now tracks admitted calls and response handles, cancels pending work and closes delivered bodies on context shutdown, and shuts down the client connection pool without blocking a carrier on a join. A complete independent concurrency/formal audit remains necessary before actor-runtime integration.
- Redirects are not automatically followed. Host-only code does not gain Oreslang mailbox sendability or unrestricted network authority.

## Exact-source JDK21 validation

`bash scripts/test-proxy-outbound-loopback.sh` compiles the four actual Java files (transport plus three test programs) with `javac --release 21` and executes five successive runs of the client/authority, end-to-end forwarding, and lifecycle/stream-shutdown checks. The regression checks 201/404/204/302 status fidelity, repeated `Set-Cookie`, hop-by-hop header stripping, bounded admission under an unreleased response, 2 MiB streamed body, forbidden authorities/credentials/encoded path/HTTPS and redirect refusal, response cancellation/permit reclamation, HEAD semantics and real end-to-end host-only proxy forwarding (201/404/302, preserved Set-Cookie, hop-by-hop stripping, 2 MiB stream).

Five consecutive local JDK21 runs of all three programs passed. The lifecycle program also passed 30 additional single-program real-socket runs. All committed file blobs below were independently matched to the tested bytes:

- `src/main/java/dev/oreslang/runtime/ProxyOutboundLoopback.java`: `a7736a5488fab6230c031729d9f3a94ad4907516`.
- `src/test/java/dev/oreslang/runtime/ProxyOutboundLoopbackCheck.java`: `2b4580dd591a2aa959df6b535841c4f0570fb271`.
- `src/test/java/dev/oreslang/runtime/ProxyLoopbackForwardingWireCheck.java`: `03d2e5722696c1ed0b58aa60e351bbe1ea8b80cb`.
- `src/test/java/dev/oreslang/runtime/ProxyOutboundLifecycleCheck.java`: `80c4820f574e818087f5d9cb7cbb70e4217c8ca5`.
- `scripts/test-proxy-outbound-loopback.sh`: `36da58be7160bb32256c6d22b93b794682367059`.

The workflow `.github/workflows/proxy-outbound-loopback.yml` runs the same three real-wire tests in the **source repository** on GitHub-hosted Java 21. In the observed PR run the workflow failed before any job steps executed, so it does not supply compiler evidence. A queued or unexecuted job is **not** proof of CI success. No cross-organization private-source mirror is authorized by this work.

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

## Scope-owner shutdown and pending delivery hardening

A `ProxyOutboundLoopback` instance now implements `AutoCloseable` with an explicit owner lifecycle. Calls register before I/O launch; each response holds its one-use admission lease until its body closes. Owner close rejects new requests, cancels pending futures and underlying requests, closes delivered-but-unread responses, and calls `HttpClient.shutdownNow()` so idle pooled connections do not outlive the owner. It never holds the owner monitor across socket/body shutdown. Each permit is released exactly once through an atomic retirement guard, and a late response cannot publish after closure.

The new real-socket `ProxyOutboundLifecycleCheck` uses latches to exercise close after delivery, close during an uncommitted upstream response, cancellation of an in-flight request without closing the owner, reuse of the reclaimed capacity, and scope shutdown while a streaming worker is blocked on a slow downstream sink. The transport checks scope closure between response-body chunks. Passing this test is not proof of all memory-order, close/suspend, TLS, starvation or actor death races. In particular, `HttpClient` cancellation is asynchronous and retirement is joined to its terminal completion; capacity remains occupied until that completion instead of being unsafely reused early.

**Not implemented:** `OresContext` or `AsyncNativeIo` construction/registration and resource scoped `NET` permissions, actor ownership transfer, actual guest `Future<...>` exposure, and a non-loopback proxy client. Those remain the next gates. The direct host-only loopback helper must not be exposed as a general-purpose guest network API.
