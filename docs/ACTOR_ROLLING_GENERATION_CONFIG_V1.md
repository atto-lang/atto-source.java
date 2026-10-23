# OresVM actor rolling-generation reload — config-first v1

Status: **experimental host-only routing foundation**, not production-ready source-level reload. Java goal: https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1971 ; LLVM parity goal: https://github.com/ores-truffle-oreslang/oreslang-source.llvm/issues/887 .

## Product goal

Normal proxy/service config changes should not fork or exec a replacement OresVM, change the stable listener FD, or interrupt admitted requests. A trusted supervisor stages new actor generations inside the same runtime; compiler/config/ABI/permission admission and readiness run before atomic route publication. Old actors keep their accepted requests and connections until physical retirement is proven.

This config-first path is **different** from stateful logical-actor upgrades in Java drafts #1960 and #1967. A config-only service may overlap old and new actor-owned states indefinitely within a bounded drain policy. A stateful logical actor preserving a mutable state cell instead needs a freeze/watermark/checkpoint/migration/ABI proof, not an unsafe shared pointer.

## Runtime topology

- One OS process, OresVM and stable listener/acceptor/router, all owned by trusted CONTROL.
- One immutable revision and physical actor set per config generation; each actor has its own mutable state and resources.
- The stable listener acquires the current routing target *and* a generation lease at one linearization point.
- An independently ready new physical actor can publish in a single compare-and-switch; the old target changes to DRAINING and does not receive newly admitted work.
- Existing G1 requests/streams retain G1 leases while new G2 work executes. A slow WebSocket or TCP connection must not block G2 publication.
- Retirement may follow only after all route leases, actual actor turns, Tasks, async callbacks, external/native I/O, FFI and memory/code-image loans physically quiesce.
- A normal reload must not close/rebind the stable listening socket, supervisory CONTROL lane, tenant quota registry, or shared TLS credential provider.

## Experimental Java foundation

ActorRollingGenerationRoute<T> provides synchronized acquisition + pin, expected-epoch publication, old-generation draining, a strict live-generation bound and shutdown fencing. The route object executes no guest work or destructors under its monitor. The target must be independently validated/warmed before publishReady. A failed readiness/validation attempt must not call publishReady; it leaves the old route unchanged.

The routing counter is **not** a universal physical quiescence proof. In particular, an observer Future completion does not prove its worker/await frame or native callback has retired. acknowledgePhysicallyRetired is explicitly a trusted CONTROL bookkeeping API, usable only *after* independent proof from actor resource/Task/code-image registries. This first slice does **not** mint or validate such a receipt, cannot itself safely reclaim a real actor, and does not implement source guest or listener wiring. Tests are host-side; do not advertise production readiness from them.

## Connection policy

| Transport | New admissions after publication | G1 ownership |
| --- | --- | --- |
| HTTP/1.1 | New requests may route to G2 at a safe request boundary; no shared parser mutation | G1 in-flight request |
| HTTP/2 / gRPC unary | New streams may use G2 only with verified independent stream state | G1 streams |
| WebSocket / upgraded HTTP | New connection G2 | Existing connection and messages G1 until close/handoff |
| Raw TCP / gRPC stream | New session G2 | Existing stream G1 |
| TLS | New handshake receives latest credential snapshot | Established session keeps its negotiated state |
| QUIC/UDP | Future connection-ID-aware routing work | Existing session pinned |

A forced drain deadline, unrecoverable I/O error, incompatible executable upgrade or unbounded stream lifetime can still interrupt clients. The contract must report forced closures, never promise universal zero packet loss.

## Release gates

1. Stage/validate candidate, verify actor readiness and permissions; fail without affecting serving traffic; exact stable PID/listener FD across repeated reloads.
2. Real Oreslang guest source: hold a G1 handler while a G2 handler completes; G1 finishes correctly, no stale admission after publication.
3. Atomic route lookup + lease and stale expected-epoch/ABA handling with race/fault tests. Separate logical ActorId, incarnation, route epoch, and code generation.
4. Bounded concurrent generations and predictable backpressure; 1,000+ reloads with bounded memory, sockets and code images.
5. Physical receipt: mailbox, Task, continuation, I/O/FFI, actor heap/arena and code image fully quiescent before reclaim; failure quarantines resources.
6. Mixed HTTP/1, HTTP/2, gRPC, WebSocket, TCP, TLS tests under real load; drain deadline and backpressure tests.
7. Source-compiled Graal JIT + native image and LLVM differential tests, including ASan/UBSan for native. Unsupported LLVM guest constructs fail closed.
8. Executed exact-source-tree CI evidence. A workflow that fails before running tests proves neither pass nor fail; private mirrors require reviewed destination authorization.

The host-only proof here intentionally does not replace Java #1960/#1967, LLVM #606, ABI identity #494, actor memory lifetime #1566 or transport shutdown #1940.
## Experimental HTTP/1.1 host integration (draft PR #1973)

The first bridge to the existing `NativeHttp.Server` is now `NativeHttpRollingDispatcher`, a trusted host CONTROL object. It owns the server's exclusive accept lane, not a second socket. The single virtual-thread CONTROL executor takes one accept at a time, atomically acquires the active `ActorRollingGenerationRoute<NativeHttp.Handler>` target and its lease, registers `Exchange.completion()`, then invokes the exact pooled handler. The target remains pinned for the exchange's lifetime. Normal G1 -> G2 promotion does not call `Server.shutdown()`, `Server.close()`, or rebind.

Sample **host-side** flow after source/config admission, never guest authority:

```java
NativeHttp.Server listener = http.listen("127.0.0.1", port, 128, 1_048_576, 30_000);
NativeHttp.Handler g1 = http.handler(v1Code, "Proxy", ActorRuntime.ActorKind.SHARED).pooled(4);
g1.warmup().get(10, TimeUnit.SECONDS); // actual actor get_ready() barriers
try (var dispatch = new NativeHttpRollingDispatcher(listener, 1001, g1, 4)) {
    dispatch.start();
    NativeHttp.Handler g2 = http.handler(v2Code, "Proxy", ActorRuntime.ActorKind.SHARED).pooled(4);
    boolean published = dispatch.promoteAfterWarmup(1, g2).get(10, TimeUnit.SECONDS);
    // The same listener is still bound; new requests route to g2.
    // G1 is still draining: do not stop its actors or reclaim its code here.
}
```

The constructor rejects unpooled/unready initial handlers and foreign-context handlers; promotion warms all new actor workers and rejects unhealthy/stale/foreign candidates. Unpublished candidates receive stop/cancel requests; the terminal cleanup proof is still separate. Multiple concurrent candidate warmups are bounded to 8 and the route's own live-generation cap is enforced. A rejected candidate is never automatically promoted. A fatal dispatcher/listener error invokes explicit shutdown and is not promised to be interruption-free.

**Current limits:** this path handles same-context, config-versioned `NativeHttp.Handler` pools. It does *not* yet support cross-`OresContext` executable hot-code cutovers, guest-callable `reload` commands, WebSocket/TCP/HTTP2 migration, per-worker physical retirement receipts, or typed ABI/capability migration. The server's existing `Exchange.completion()` is a **transport completion marker**, not proof of all Task/FFI/native/actor code being physically quiescent. `acknowledgePhysicallyRetired` is trusted host bookkeeping only and must not be called to free a pool without an independently verified terminal witness.

`ActorRollingHttpSocketTest` is the first real loopback HTTP socket + `ActorRuntime` SHARED-actor acceptance fixture: a G1 request is deliberately held, a G2 request completes over the **same** listener/port/PID, and G1 later finishes. It tests the routing hypothesis, not the full `NativeHttpRollingDispatcher` guest path; that integration fixture remains a release blocker.

The existing source `NativeHttpTest` uses Oreslang-compiled `http.listen` and `http.handler` separately; those source-level tests must be extended to exercise the rolling coordinator after safe host exposure and security review. Keep the PR draft until a real repository Maven/Graal build and tests execute.
