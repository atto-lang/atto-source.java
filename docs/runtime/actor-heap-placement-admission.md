# Main-based actor heap-placement admission extraction

Source: draft #558 (actor heap isolation), ported onto `main` without
overwriting the modern `ActorRuntime`, `OresEvalRootNode`, compiler,
scheduler or capability checks.

## Guaranteed by this narrow PR

- `defaultHeapPlacement(UNTRUSTED)` requests a separate VM isolate.
- `spawnWithHeapPlacement` can admit trusted actor-arena placements only.
- It refuses unverified Graal and process placements **before spawning**.
- It refuses an untrusted actor on the host arena even when code calls it with
  the untrusted policy, while retaining the legacy bootstrap/test API.
- `heapStatusFor` reports physical independence and independent GC as false;
  owner-checked direct regions are a logical ownership tool, not independent GC.
- Supervisor-only checks and cross-runtime ActorRef validation apply.
- Existing mailbox/send/scheduling/cancellation paths are unchanged.

## Non-goals

This does **not** construct a GraalVM isolate, guarantee independent
JVM heaps, lower arbitrary guest object graphs, install native arenas,
migrate source actors, or merge draft #558. It is an admission contract,
not native isolation certification.

## Required review and validation

Run `mvn -B -Dtest=ActorHeapPlacementGuardTest test` on an approved exact-head
JDK 25 + GraalVM runner. Preserve the full build/test receipt and verify
the targeted test and all existing actor tests. As of creation, source-org
Actions jobs fail before any test step; no such passing receipt is claimed.

Until verified, keep this PR draft. Private source must not be exported
to another organization unless protected policy PRs #998 / #1217 receive
independent approvals and satisfy their live fail-closed checks.
