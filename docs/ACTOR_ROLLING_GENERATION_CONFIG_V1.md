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

Host setup (before creating the validated coordinator described below):

```java
NativeHttp.Server listener = http.listen("127.0.0.1", port, 128, 1_048_576, 30_000);
NativeHttp.Handler g1 = http.handler(v1Code, "Proxy", ActorRuntime.ActorKind.SHARED).pooled(4);
g1.warmup().get(10, TimeUnit.SECONDS); // real actor get_ready() barriers
var dispatch = new NativeHttpRollingDispatcher(listener, 1001, g1, 4);
dispatch.start();
// Create NativeHttpRollingReloadCoordinator for validated G2 publication.
// Close the coordinator/dispatcher only on explicit service shutdown.
```

The constructor rejects unpooled/unready initial handlers and foreign-context handlers; promotion warms all new actor workers and rejects unhealthy/stale/foreign candidates. Unpublished candidates receive stop/cancel requests; the terminal cleanup proof is still separate. Multiple concurrent candidate warmups are bounded to 8 and the route's own live-generation cap is enforced. A rejected candidate is never automatically promoted. A fatal dispatcher/listener error invokes explicit shutdown and is not promised to be interruption-free.

**Current limits:** this path handles same-context, config-versioned `NativeHttp.Handler` pools. It does *not* yet support cross-`OresContext` executable hot-code cutovers, guest-callable `reload` commands, WebSocket/TCP/HTTP2 migration, per-worker physical retirement receipts, or typed ABI/capability migration. The server's existing `Exchange.completion()` is a **transport completion marker**, not proof of all Task/FFI/native/actor code being physically quiescent. `NativeHttpRollingDispatcher.acknowledgePhysicallyRetired` now **always throws** and retains the old generation until real physical-lifetime registries can issue an authenticated, epoch-bound retirement receipt. This is deliberate quarantine, not a memory-reclamation implementation.

`ActorRollingHttpSocketTest` is the first real loopback HTTP socket + `ActorRuntime` SHARED-actor acceptance fixture: a G1 request is deliberately held, a G2 request completes over the **same** listener/port/PID, and G1 later finishes. It tests the routing hypothesis, not the full `NativeHttpRollingDispatcher` guest path; that integration fixture remains a release blocker.

The existing source `NativeHttpTest` uses Oreslang-compiled `http.listen` and `http.handler` separately; those source-level tests must be extended to exercise the rolling coordinator after safe host exposure and security review. Keep the PR draft until a real repository Maven/Graal build and tests execute.

## Draining a completed old HTTP generation

1. Let the promoted route stop admitting **new** G1 exchanges.
2. Wait for `dispatch.admissionsDrained(oldEpoch)` (all G1 HTTP exchanges have reached their transport terminal marker).
3. Call `dispatch.requestDrainAfterAdmissions(oldEpoch)`. This sends nonparking `gracefulStop()` to G1 pooled actors; repeated calls retry incomplete stop signaling. This *does not* close the permanent listener or G2.
4. Wait until **each** G1 actor `get_done()` is successful, and independently verify physical Tasks, native I/O, FFI, arena, code-image leases and callbacks have retired. Failed actor exits are **not** certified as clean.
5. **Do not attempt physical retirement yet.** `dispatch.acknowledgePhysicallyRetired(oldEpoch)` is intentionally disabled and throws even if actor termination and transport drain have succeeded. Keep the old generation quarantined until a separate authenticated receipt is implemented for all physical loan kinds. This also means the bounded live-generation cap eventually prevents additional reloads rather than risking unsafe reclamation.

A failed/unpublished candidate has its pool fenced and cancelled; its registry reference is removed without altering the serving route. This is cancellation, **not** proof of immediate memory reclamation. Future production integration must consume authenticated retirement receipts and replace the caller-attested bookkeeping API.

At present, an HTTP request is assigned a route at **rolling dispatch admission**, not when the kernel accepts the TCP connection. Requests queued at the transport boundary during a cutover can be assigned to G2; in-flight G1 exchanges with an acquired routing lease remain G1. This ordering is intentionally documented for the initial config-only path. A future source-level interface can make ingress-before-dispatch affinity stricter if required.

## Real NativeHttp loopback integration fixture (2026-10-23)

`NativeHttpRollingDispatcherIntegrationTest` now exercises the **actual** `NativeHttp.Server` HTTP socket, `Exchange.claim`/move/response, SHARED `ActorRuntime.spawnCodeActor` workers, and `NativeHttpRollingDispatcher` (not a substitute JDK `HttpServer` used as a mock). It specifically tests:

- single bound listener and process PID, with a G1 request deliberately deferred *between actual actor mailbox turns* (without parking its scheduler carrier);
- bad G2 worker initialization fails without publication while another request is still served by G1;
- subsequent healthy G2 warmup, atomic generation promotion and G2 request completion **before** the original G1 response;
- G1 cannot drain or retire until the old exchange finishes, and successful actor termination still does **not** automatically call physical reclaim;
- wrong expected epoch, duplicate initial pool, foreign `NativeHttp` context, and an unready initial pool fail closed;
- exclusive `Server.accept` ownership; the test-only trusted `NativeHttp(ActorRuntime)` constructor binds only literal loopback addresses and is package-private.

The production constructor must remain lazy with respect to `OresContext.actors()`: `OresContext` creates `NativeHttp(this)` as a field initializer *before* assigning its `ActorRuntime`. Any eager actor-runtime dereference breaks every normal guest HTTP context. The host loopback fixture uses an explicit actor runtime solely to avoid requiring a privileged guest-controlled reload API for testing.

The transferred `Exchange` is revocable: host CONTROL abort uses `abortFromRollingDispatcher(dispatcher)`, which checks the actual listener's exclusive dispatcher authority rather than illegally reusing the exchange's old mutable ownership capability. This avoids stranded exchanges if a dispatch is rejected after transfer.

`NativeHttp` rejects new worker startup after close and refuses promotion of candidate worker pools with existing in-flight exchanges.

**Verification caveat:** as of this change, this new full NativeHttp test is committed and included in the exact-head workflow but **has not been executed**. Prior standalone host routing smoke/stress is not proof of this path, and the separate full Oreslang guest-source hot reload and physical Task/FFI/code quiescence proofs remain release blockers.

## Supervisor transaction (current implementation)

`NativeHttpRollingReloadCoordinator` is a host CONTROL-only policy gate, not a new guest capability. `Config` copies all settings into immutable strings, validates service ID/revision/input limits, and requires a monotonically increasing revision with the same service identity. The host supplies explicit ABI/capability/config validation and a trusted candidate factory. Invalid validation never invokes the factory. The coordinator limits simultaneous in-flight reloads to eight; a stale route epoch returns `STALE` without constructing a candidate.

```java
var config1 = new NativeHttpRollingReloadCoordinator.Config(
    "proxy", 1L, Map.of("upstream", "10.0.0.1:9000"));
try (var tx = new NativeHttpRollingReloadCoordinator(
        dispatch, config1,
        (previous, next) -> validateProxyAbiCapabilitiesAndListener(next),
        next -> http.handler(compileValidatedConfig(next), "Proxy",
            ActorRuntime.ActorKind.SHARED).pooled(4))) {
    var updated = new NativeHttpRollingReloadCoordinator.Config(
        "proxy", 2L, Map.of("upstream", "10.0.0.2:9000"));
    var outcome = tx.reload(1L, updated).get(10, TimeUnit.SECONDS);
    assert outcome.status() == NativeHttpRollingReloadCoordinator.Status.PUBLISHED;
    // The old actor generation is still draining, not physically reclaimed.
}
```

The `promoteAfterWarmup` entrypoint is now package-private runtime integration plumbing, not a general public host API; the coordinator is the validated entrypoint. Explicit `close()` shuts down the owned listener and is **not** part of routine reload. Same-context Java-code handlers are supported in this slice; executable code-image exchange, Oreslang supervisor syntax and full LLVM parity remain separate work.

`NativeHttpRollingDispatcherIntegrationTest` now also includes configured transaction success/failure, a deliberately failing candidate ready barrier, invalid-input preflight, stale revision rejection, repeated G1→G2→G3 reload on one port/PID, and a concurrent candidate publication race. These checks are present in source but have not been confirmed to execute in GitHub Actions: existing exact-head jobs fail **with zero executed steps**. Do not claim a passing Maven/Graal test run.

## 2026-10-24 audit: fail-closed core and private-runner admission

**Core retirement is now disabled as well as HTTP retirement.** `ActorRollingGenerationRoute.acknowledgePhysicallyRetired(epoch)` always throws. Otherwise a trusted host could bypass the dispatcher's fail-closed gate and silently evict a still physically referenced actor target. After a bounded number of promotions, the generation cap blocks further reloads until a real physical authority can mint and validate a one-shot epoch+actor+all-loans-quiescent receipt. No path in this draft claims to reclaim a real actor safely.

`NativeHttpRollingReloadCoordinator` now commits its published-config metadata directly from the route-publication completion callback, before settling the success Future. An extra queued coordinator completion step previously could be rejected by explicit shutdown after the route was published, causing a false failed result and stale metadata. The observable result now has the config installed first; any concurrent read during the small route-then-metadata interval remains a future unified publication authority concern.

Actual cross-org GitHub Actions experiment: a **source-free** runner canary was pushed to a temporary private branch in `messaging-intel/msgint-analytics` (run `40057428099`). It failed with zero executed job steps. The probe workflow was removed after collecting evidence. Other private organizations' Oreslang runner canaries were also found failing before steps. **No Oreslang source bytes were transferred across organizations**, because a usable Actions runner/minutes budget could not be confirmed.

The branch's JDK25/Graal Maven JUnit and live `NativeHttp` tests remain **unexecuted** and are not claimed passing. The core route, loopback socket fixture, and integration test now explicitly assert rejection of physical retirement even after traffic drains and actor terminal observation. No full repo compilation has been proven.
