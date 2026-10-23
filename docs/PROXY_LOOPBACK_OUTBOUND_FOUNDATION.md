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

## Typed future and bounded body-copy handoff (follow-up, October 23)

The runtime now has a **package-private** trusted-only `AsyncNativeIo.copyProxyLoopback(OresContext, URI, method, OutputStream hostSink)`. It calls the existing context-owned, actor- and resource-scoped NET gate first, then adapts the transport's `CompletionStage` into an `OresFuture<ProxyBodyCopy.Receipt>` with `OresFuture.from`. The receipt is an immutable snapshot of status, header pairs and transferred byte count, **not** a raw Java response/stream capability. All actual body reads and downstream writes are dispatched to the context's virtual-thread I/O executor. The sink must be trusted host code, never a guest closure; these APIs remain non-public and do not yet constitute an Oreslang guest API.

`ProxyBodyCopy` owns the response-to-worker transition. A cancelled future cancels pending response acquisition, while cancellation of a running copy is observed by the stream worker. The response remains physically owned by the transport until its worker returns and closes it. Success is published **after** close, so an observable success does not precede admission-lease retirement. Worker rejection starts a short-lived virtual-thread cleanup operation rather than closing a blocking body stream on the caller's actor carrier. The context's existing resource shutdown closes the outbound client and outstanding bodies.

The standalone JDK21 regression `ProxyBodyCopyCheck` verifies 150 concurrent cancellation/stream ownership interleavings per run, success delivery, cancellation before acquisition, rejected-worker cleanup, and a close gate proving the future cannot settle before body shutdown. **Five local runs passed** against exact committed source/test blobs:
- `ProxyBodyCopy.java`: `bed82a52d45bfe212173d9a77f89a727c9dd42b3`.
- `ProxyBodyCopyCheck.java`: `a10e4b3b0f3690178b74fd02a9c7207c2dc68c37`.

`scripts/test-proxy-outbound-loopback.sh` now includes the helper and test, alongside the existing HTTP socket programs. An added JUnit reflection test asserts the typed handoff and receipt are not public guest APIs.

**Validation limitation:** this standalone test exercises the JDK-only handoff with a synthetic response source, not the full `AsyncNativeIo`/OresFuture integration, actual Oreslang guest runtime or production actor scheduler. Full-source Maven/JUnit and JDK25/Graal execution are still required. No general egress, arbitrary upstream origins, CONNECT or host-to-guest callback delivery has been introduced. Private cross-org source mirroring remains unauthorized while main's approved destination allowlist is empty (security issue #1501).

## Worker-first acquisition and actor-owned cancellation (follow-up)

A hardened internal copy API now accepts a **trusted host acquisition supplier** rather than an already-started upstream `CompletableFuture`. The `ProxyBodyCopy.start` worker must be accepted before that supplier executes. If the I/O executor rejects a submission, **no HTTP request has been launched and no response exists to clean up**, eliminating the unbounded per-rejection emergency cleanup virtual thread.

`AsyncNativeIo.copyProxyLoopback` now validates the canonical loopback destination and evaluates actor-local NETWORK plus resource-scoped NET authority **synchronously on the originating actor turn**; the authorized acquisition supplier runs later on a virtual I/O worker. The runtime has a separate `activeProxyCopies` admission counter capped by its I/O capacity, with capacity retained until the physical worker exits even if the `OresFuture` has already been canceled. The worker uses a virtual-thread wait for upstream response headers, never a scheduler carrier. All response close/lease retirement occurs on that host I/O worker.

The resulting `OresFuture<Receipt>` is registered via **`ActorRuntime.ownCurrentActorFuture`**, reusing the existing per-actor pending-operation ledger. When one actor stops, its pending copy Future is canceled and cancellation propagates to its host CompletionStage; sibling actors' Futures remain independent. There is no second actor-owner registry to synchronize. The JUnit `ProxyActorOwnedFutureTest` checks this using independent actors A/B and host completion stages (not a live guest HTTP call).

Local focused JDK21 validation of the worker-first algorithm: a behavior-equivalent self-contained harness compiled and completed **5 runs × 200 cancellation races = 1,000** with rejection-before-acquisition, pre-dispatch cancellation, close-before-success and cancellation of a pending upstream stage. This is **not** a passing full-repository build or exact-blob comparison for the entire `AsyncNativeIo`/ActorRuntime integration.

`ProxyBodyCopyCheck` also now explicitly waits for acquisition to start before testing pending-HTTP cancellation; an independent staged-worker test verifies cancellation *before* acquisition starts, avoiding timing-dependent assertions.

**Still required:** executable source-repository Maven/JUnit and actual Oreslang guest code proving cancellation/await/backpressure against a real socket, a bounded deadline/abort mechanism for a physically blocked downstream writer, and reviewed external DNS/IP/TLS/CONNECT networking. Worker cancellation never falsely retires a physically blocked stream. Private cross-organization source mirroring remains unauthorized without the independently approved destination and carrier contract from #1501.

## Exact-source JDK21 worker-first verification and CI gate

A subsequent local check reconstructed **the exact committed `ProxyBodyCopy.java` Git blob** (`fc52afc277a03b04499ac644f544e82e91a10d8d`) and compiled it using `javac --release 21`. An independent, deterministic host-only harness passed **ten successive runs of 200 cancellation/physical-close interleavings (2,000 total)**, plus worker-rejection-before-acquisition, cancellation-before-dispatch (no network start), cancellation-after-acquisition, and close-before-success. The only compiler diagnostic was a deprecation/removal warning for `ThreadDeath` handling. The focused harness verifies the exact helper source, not the complete Java runtime or Gradle/Maven integration.

`ProxyActorOwnedFutureTest` verifies use of the existing `ActorRuntime.ownCurrentActorFuture` ownership hook with two actors and independent `OresFuture.from(CompletionStage)` adapters. The source workflow now includes this test in the JDK 25 Maven gate.

**CI remains unexecuted:** at PR head `c63b38cd600005e3d3c3cd44200465bae0f9ad62`, Actions run `40003784169` marked both `java21-loopback` and `jdk25-context-gate` as failed with zero steps executed. This is not evidence of exhausted billed minutes, nor a compiler/test outcome. No private code was copied to an unapproved CI organization.
