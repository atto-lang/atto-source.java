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

`bash scripts/test-proxy-outbound-loopback.sh` compiles the five actual Java files (transport plus four test programs) with `javac --release 21` and executes five successive runs of the client/authority, end-to-end forwarding, and lifecycle/stream-shutdown checks. The regression checks 201/404/204/302 status fidelity, repeated `Set-Cookie`, hop-by-hop header stripping, bounded admission under an unreleased response, 2 MiB streamed body, forbidden authorities/credentials/encoded path/HTTPS and redirect refusal, response cancellation/permit reclamation, HEAD semantics and real end-to-end host-only proxy forwarding (201/404/302, preserved Set-Cookie, hop-by-hop stripping, 2 MiB stream).

Five consecutive local JDK21 runs of all four programs passed. The lifecycle program also passed 30 additional single-program real-socket runs. All committed file blobs below were independently matched to the tested bytes:

- `src/main/java/dev/oreslang/runtime/ProxyOutboundLoopback.java`: `a8ab9830c655b3e4d84c58b5aab18a4d17964e70`.
- `src/test/java/dev/oreslang/runtime/ProxyOutboundLoopbackCheck.java`: `83b382f17bb422b8e9f6f88616caf9532c72fdf8`.
- `src/test/java/dev/oreslang/runtime/ProxyLoopbackForwardingWireCheck.java`: `03d2e5722696c1ed0b58aa60e351bbe1ea8b80cb`.
- `src/test/java/dev/oreslang/runtime/ProxyOutboundLifecycleCheck.java`: `80c4820f574e818087f5d9cb7cbb70e4217c8ca5`.
- `src/test/java/dev/oreslang/runtime/ProxyOutboundHandoffRaceCheck.java`: `f88cc40c2750cff9c336ffd72da74c3f2f82d5f1`.
- `scripts/test-proxy-outbound-loopback.sh`: `4a3647841073591f641a3ce251cad5377921324e`.

The workflow `.github/workflows/proxy-outbound-loopback.yml` runs all four real-wire tests in the **source repository** on GitHub-hosted Java 21. In the observed PR run the workflow failed before any job steps executed, so it does not supply compiler evidence. A queued or unexecuted job is **not** proof of CI success. No cross-organization private-source mirror is authorized by this work.

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

## Late-closure admission race fix

A deterministic real-socket regression reproduced a permit being reclaimed while the downstream body writer was still blocked. The old code failed the assertion that the writer remained accounted. The fix retains admission until both the body close and the physical copy worker finish. Five runs of each of four socket test programs passed locally with Java 21, and an additional 50 bounded handoff tests passed. No JDK25/guest conformance is claimed.

Private-source cross-organization mirroring remains unauthorized: the source-main approved-destination allowlist is empty, per CI security issue #1501. No private source was transferred.

## 2026-10-23 follow-up: stream-start and raw-header admission

This PR now includes the previously isolated, locally verified four-file hardening patch. The response lifecycle uses a synchronized state transition to order `streamBody` startup against `close`. A second stream call is rejected, close-before-start cannot reactivate a released response, and a slow downstream writer keeps its outbound permit until the physical copy worker finishes. The owner monitor is not held across network reads, writes or `body.close()`.

The `filtered(HttpHeaders)` helper now verifies **all** received header field names, values, field count (100), and aggregate character/byte estimate (32,768 Latin-1 header octets) **before** stripping hop-by-hop fields. That closes a bypass in which a discarded oversized `Connection`-nominated field previously escaped the retained-header budget. This is an admission check after Java `HttpClient` has parsed headers, **not** a pre-allocation limit on incoming wire header bytes; an earlier transport/parser-level bound remains required.

New regressions:

- `ProxyOutboundStartCloseRaceCheck.java`: 150 synchronized close/start race attempts per execution, closed-before-start denial, permit retirement and reuse.
- `ProxyOutboundHeaderBudgetCheck.java`: oversized discarded fields, raw field count overflow, malformed `Connection` tokens, and repeated `Set-Cookie` preservation.
- `scripts/test-proxy-outbound-loopback.sh`: runs six Java 21 socket/header programs five times each.

The exact previously tested patch was applied to the same PR head and all four Git blob hashes match the locally verified patch:

- Transport: `d6ee5966178e59de55befa3e2cfe4a394e6e8426`.
- Stream-start race test: `1b2f2a13869a72bfc4258f04abebfefa13c7bfb2`.
- Header-budget test: `4f7f3355435b6284d051f171e1ebd2e86dfb3ff6`.
- Test runner script: `d00371952f10c80781783d13905acddd305ab828`.

Local evidence for these exact file bytes, from the isolated audit: **30/30** test-program executions passed (six programs × five runs), plus ten additional stream-start/close and ten additional header-budget executions. The 150-turn race test ran 2,250 race attempts in total. Tests used JDK 21; they do not establish JDK 25, Graal guest execution, actor integration or production viability.

**GitHub Actions:** exact branch-head job failed before any execution steps, so the CI result is neither a compiler failure nor a pass. The source `main` private-source transfer destination allowlist remains empty. No private-source cross-organization mirror is approved or attempted. Refer to [issue #1501](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1501).

The integration remains draft until the context-owned `AsyncNativeIo` bridge, actor-local and resource-scoped network permissions, host I/O worker isolation, verified tests and general-egress destination pinning are implemented and reviewed.

## Context-owned loopback admission bridge (2026-10-23)

The trusted Java runtime now has a narrow package-private `AsyncNativeIo.requestProxyLoopback(OresContext, URI, String)` entry point. The caller's `OresContext` must own the exact `AsyncNativeIo` instance (preventing cross-context permission laundering). Before creating or using the outbound transport, the entry point performs full canonical loopback URI/method validation and synchronously calls `context.requirePermission(NET, "127.0.0.1:<port>", "proxy.loopback.request")`. The existing `requirePermission` evaluates both current actor/isolate NETWORK authority and the scoped `RuntimePermissions.NET` allow/deny rules. A failed permission check never creates or dispatches a request.

The transport is lazy, bounded by the context's existing I/O capacity (up to 128), and registered under the existing `AsyncNativeIo` resource set. Closing the owning I/O context cancels the HTTP requests, closes retained responses, and terminates the client pool. No guest method, reflection handle or `ActorRef` is added; this method remains trusted package-private host code.

`ProxyOutboundContextAuthorityTest` adds JUnit regression contracts for entry-point visibility, canonical GET/HEAD loopback authority admission, rejection of unsafe authority/path forms, and explicit scoped NET grants with deny precedence.

**Verification distinction:** These context integration additions have not yet passed full Java/Graal Maven/JUnit, JDK25 AOT or actual Oreslang guest execution because source-org Actions jobs are failing before any test steps. Earlier standalone JDK21 socket proof establishes only the outbound transport behavior, not this new context integration. No cross-organization mirroring of private source has been performed; the independently approved carrier allowlist is still empty (see #1501).

Remaining gates: real `OresContext`/actor guest integration tests, safe `OresFuture` response ownership and body-I/O scheduling on reactor workers, transport-level preallocation header limits, non-loopback egress with pinned addresses and TLS, and secure CONNECT.
