# DRAFT: trusted outbound supervisor quota — Java/GraalVM

Tracking: [Java #1265](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1265), [shared contract #69](https://github.com/ores-truffle-oreslang/oreslang-common-source/issues/69).

This PR seeds a **host-only integer token-bucket primitive** and deterministic JUnit tests. **It is NOT currently wired to any actor send, respond, output, supervisor mailbox or shared Graal isolate bridge. Do not advertise untrusted message throttling as implemented.**

Current `ActorRuntime.spawnUntrusted` host-test compatibility path rejects direct untrusted `ActorRef.send` / arbitrary IPC. Do **not** relax that denial as part of a bucket primitive; parent/supervisor reporting needs a narrowly authenticated capability and a verified protected-guest bridge first.

## Remaining wiring / threat model

- Add stable trusted lineage/supervisor/tenant identities to routing metadata and immutable privileged policy snapshots. Never take identity, policy, clocks or rate state from an untrusted message or guest heap.
- Implement **atomic multi-scope** token + byte + outstanding-credit reservations, rollback provisional admission before publication; hold start-rate charges on published cancellation, and ack-on-release; the single bucket in this PR is **insufficient** to enforce hierarchy or bytes.
- Map every possible guest->supervisor data path (ActorMailman, ActorRef sends, actor output/respond, channel bridge, group proxy) to the gate; disallow alternate direct enqueue.
- Use CONTROL configuration off the data hot-path and protected, bounded independent runtime panic/exit/cancel transport.
- Keep pooled UNTRUSTED Graal isolate identity; prove real guest code and physical boundary rather than a host-only Java test helper.
- Exhaustive adversarial tests: clock rollback, burst, concurrent send, reincarnation, child fanout, forged sender/control messages, max bytes, mailbox capacity, cancellation races, and shutdown.
- No core dependency on JVM clocks/threads/heap. Runtime-owned state only.

Safety: untrusted actors cannot access or mutate bucket instances. A standalone host helper is intentionally not represented as an end-to-end security fix.

## Addition: untrusted HTTP/TCP egress quotas

New host-only `TrustedUntrustedNetworkQuota` has default independent budgets of **10 HTTP requests in flight, 10 HTTP starts/s (burst 2), and 10 outbound TCP sockets connecting/open**. Its runtime-owned HTTP and TCP leases release exactly once, including cancellation/close races. The standalone JUnit fixture covers rate and concurrency separately, 11th-connection refusal, malformed policy, and concurrent starts. **This class is not yet attached to `NativeIo.httpText`, `NativeIo.connect`, or any real untrusted guest egress bridge.**

Do not weaken the current `IsolatePolicy.untrustedActor()` with an ambient NETWORK grant: protected guest request -> authenticated privileged egress broker is the only allowable path. Once added, validate the original URL, redirect chain, final hostname + resolved IP, DNS rebinding/SSRF rules, socket pool accounting, host-side cancellation, quotas across restarts/descendant lineages, and aggregate tenant caps before dispatch. A TCP connection-start budget must also be enforced to stop rapid connect/disconnect storms; HTTP/2 streams consume HTTP request concurrency even on a reused socket. The counters here are per instantiated host quota object and **not restart-proof until bound to a stable trusted lineage**.

## Concrete outbound dispatch integration sites

Verified Java source path as of this draft:
- `NativeIo.httpText` handles outbound GET/POST, checks `RuntimePermissions.Permission.NET`, then calls `HttpClient.send` with redirects set to NEVER.
- `NativeIo.connect` validates NET, creates/registers a `Socket`, calls blocking `connect`, and exposes a host-managed `TcpConnection`.
- `NativeHttp` is an *inbound HTTP server* and rejects UNTRUSTED handlers; it is not the outbound quota hook.
- `ActorRuntime.spawnUntrusted` forces an empty capability set even when callers request additional capabilities, so the existing native I/O paths remain unavailable to UNTRUSTED actors.

Integrate only through an authorized **protected guest -> trusted egress broker**. On broker dispatch bind the authenticated actor ID to the stable child lineage Scope, supervisor Scope and tenant Scope. Do not permit an untrusted payload to specify the Scope objects, monotonic timestamps or broker callback. Call `TrustedHierarchicalNetworkGate.tryStartHttp` or `tryOpenTcp` **before** external I/O, `commitDispatch` before actually performing the I/O, and `abortBeforeDispatch` only if the operation was never submitted. On completion/cancellation, stop the underlying host operation and close all leases exactly once. Never release a live socket permit merely because a guest cancelled an awaited future.

This foundation currently uses a **single per-gate lock** to keep reservations and settlements atomic. For production-scale tenant and supervisor scopes use deterministic lock ordering or partitioning with equal atomicity. The runtime must persist Scope identity across restart and route changes; tests reusing a Scope are not proof that the spawn path already does so. Physical socket limits must count HTTP client pools/keepalive as well as raw TCP sockets; these helper classes do not yet measure pooled HTTP sockets. DNS permission checks must validate resolved addresses, not just a hostname supplied by a guest.

## Security-audit correction: no delayed token refund

The pre-dispatch reserve/abort interface originally returned rate tokens to the current token bucket. That let a caller hold reservations across refill epochs and cancel later to create a burst beyond the configured maximum. **Now a reservation published to the trusted broker permanently spends its HTTP/TCP start token, whether or not dispatch succeeds.** Cancellation, timeout and close only return the in-flight HTTP or connecting/open TCP credit. A partial child/supervisor/tenant reservation rejected *inside the host admission lock before publication* may reverse its provisional tokens. The helper name `rollbackUnpublished` (native `rollback_unpublished`) is deliberately limited to this use.

Revoking/closing a policy denies *new pre-dispatch commits*, but an already committed host I/O operation still needs explicit cancel-and-drain or physical socket close. Native gates enforce scope/lifetime teardown assertions; they do **not** replace a broker shutdown fence. Java pre-constructs quota admission envelopes before consuming tokens to avoid stranding quotas on allocation failure. None of these changes grants untrusted NETWORK capability or constitutes demonstrated guest-to-broker enforcement. CI executions and sanitizer evidence remain pending.

## 2026-10-17 hardening: atomic scope revocation and direct egress denial

A pre-dispatch **three-scope** lease now checks child, supervisor and tenant revocation status while holding the same trusted gate lock as supervisor Scope.close, before any scope commits. The previous sequential commit could partially mutate child/supervisor leases and throw (Java) or abort the native process (LLVM) when a later scope had already been revoked. Deterministic all-three-role rejection and Java/native concurrent revoke-vs-commit regressions were added.

The real Java NativeIo outbound HTTP and raw TCP call sites also now explicitly reject a direct UNTRUSTED actor turn through TrustedNetworkIngressGuard. The four guarded entry points are HTTP request submission, TCP connect, TCP read and TCP write; ordinary socket close remains available for cleanup. This is defense in depth and **does not create a protected guest->broker network path**. The real actor-turn JUnit case is added but not executed by the source-only carrier; the carrier verifies exact source hash and performs a host-only simulated identity check. Protected isolate and host-network cancellation semantics remain pending.

## October 17 audit: real HTTP lifecycle prototype

Added `TrustedHttpLoopbackProbe` and `TrustedHttpLoopbackProbeTest` to the Java draft. The trusted host can reserve child/supervisor/tenant HTTP quota, initiate asynchronous fixed-loopback HTTP I/O, and release the lease on the underlying response's completion instead of the caller's cancellable future. No redirects or ambient HTTP proxy. The local-server cancellation regression is authored but **not executed in the source CI or pinned 19-blob carrier**; it remains unverified and grants no untrusted NETWORK capability.

[Carrier run #39095558019](https://github.com/scintilla-run-test/gleam-runner-conformance/actions/runs/39095558019) passed the existing source-exact host suite and uploaded the [19-blob host-only evidence artifact](https://github.com/scintilla-run-test/gleam-runner-conformance/actions/runs/39095558019/artifacts/12124566191). This artifact does not include the two newly added loopback probe files. Protected guest-to-broker integration and real host I/O cancellation/teardown remain release blockers.
