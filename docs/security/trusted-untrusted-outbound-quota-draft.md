# DRAFT: trusted outbound supervisor quota — Java/GraalVM

Tracking: [Java #1265](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1265), [shared contract #69](https://github.com/ores-truffle-oreslang/oreslang-common-source/issues/69).

This PR seeds a **host-only integer token-bucket primitive** and deterministic JUnit tests. **It is NOT currently wired to any actor send, respond, output, supervisor mailbox or shared Graal isolate bridge. Do not advertise untrusted message throttling as implemented.**

Current `ActorRuntime.spawnUntrusted` host-test compatibility path rejects direct untrusted `ActorRef.send` / arbitrary IPC. Do **not** relax that denial as part of a bucket primitive; parent/supervisor reporting needs a narrowly authenticated capability and a verified protected-guest bridge first.

## Remaining wiring / threat model

- Add stable trusted lineage/supervisor/tenant identities to routing metadata and immutable privileged policy snapshots. Never take identity, policy, clocks or rate state from an untrusted message or guest heap.
- Implement **atomic multi-scope** token + byte + outstanding-credit reservations, rollback if seal or mailbox enqueue fails, and ack-on-release; the single bucket in this PR is **insufficient** to enforce hierarchy or bytes.
- Map every possible guest->supervisor data path (ActorMailman, ActorRef sends, actor output/respond, channel bridge, group proxy) to the gate; disallow alternate direct enqueue.
- Use CONTROL configuration off the data hot-path and protected, bounded independent runtime panic/exit/cancel transport.
- Keep pooled UNTRUSTED Graal isolate identity; prove real guest code and physical boundary rather than a host-only Java test helper.
- Exhaustive adversarial tests: clock rollback, burst, concurrent send, reincarnation, child fanout, forged sender/control messages, max bytes, mailbox capacity, cancellation races, and shutdown.
- No core dependency on JVM clocks/threads/heap. Runtime-owned state only.

Safety: untrusted actors cannot access or mutate bucket instances. A standalone host helper is intentionally not represented as an end-to-end security fix.

## Addition: untrusted HTTP/TCP egress quotas

New host-only `TrustedUntrustedNetworkQuota` has default independent budgets of **10 HTTP requests in flight, 10 HTTP starts/s (burst 2), and 10 outbound TCP sockets connecting/open**. Its runtime-owned HTTP and TCP leases release exactly once, including cancellation/close races. The standalone JUnit fixture covers rate and concurrency separately, 11th-connection refusal, malformed policy, and concurrent starts. **This class is not yet attached to `NativeIo.httpText`, `NativeIo.connect`, or any real untrusted guest egress bridge.**

Do not weaken the current `IsolatePolicy.untrustedActor()` with an ambient NETWORK grant: protected guest request -> authenticated privileged egress broker is the only allowable path. Once added, validate the original URL, redirect chain, final hostname + resolved IP, DNS rebinding/SSRF rules, socket pool accounting, host-side cancellation, quotas across restarts/descendant lineages, and aggregate tenant caps before dispatch. A TCP connection-start budget must also be enforced to stop rapid connect/disconnect storms; HTTP/2 streams consume HTTP request concurrency even on a reused socket. The counters here are per instantiated host quota object and **not restart-proof until bound to a stable trusted lineage**.
