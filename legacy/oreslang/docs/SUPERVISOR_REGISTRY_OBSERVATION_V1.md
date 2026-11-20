# Host supervisor registry observations (stage 1)

**Implementation status:** Real Java `ActorRuntime` supervisor registry adapter, **host-only PARTIAL snapshots**. **Not** a reserved CONTROL snapshot protocol, native Oreslang HTTP server, executable guest-source `ObservableActor<E>` trait, authenticated tenant admission, or full actor-health inspector.

This is an intentionally limited predecessor to the Oreslang Runtime UI's [broker PR #5](https://github.com/ores-truffle-oreslang/oreslang-runtime-ui/pull/5) and [frontend PR #4](https://github.com/ores-truffle-oreslang/oreslang-runtime-ui/pull/4). It does not take dependencies on their unmerged JavaScript or expose raw runtime objects through HTTP.

## Implemented

- `ActorRuntime.supervisorRegistryForObservation()` captures the real registered supervisor identities into a host-only immutable map. Guest actor/mailman callers are rejected by existing supervisor-context and affinity rules.
- `SupervisorRegistryObservation` accepts an authenticated server-side **session object**, asserted tenant string, **exact runtime-local supervisor handle** and bounded grant TTL (maximum 60 s). A grant is an unforgeable host-local object identity; not a serialized bearer token, guest `ActorRef`, ActorGroup membership or CONTROL capability.
- The read requires the same session object, tenant and fixed registration generation 1. It verifies the exact supervisor object remains registered in the same runtime before and after enumeration, honors explicit maxNodes (1–10,000), maxDepth (0–64), and per-call elapsed budget (at most 5 s), a maximum of eight active reads overall and one per grant, and supports revocation.
- Tree nodes derive from the actual `ActorSupervisor.parentId()` registry relationships. Only structural supervisor ID, scoped parent ID, fixed registration generation, `observed` state and observation timestamp are emitted. Structural child IDs outside the scoped subtree do not appear.
- All results are **`partial`**. The adapter deliberately omits actor records, payloads, mailbox contents, group membership and raw failures. It emits explicit `SUPERVISOR_REGISTRY_ONLY_NO_CONTROL` and `ACTOR_LIFECYCLE_COVERAGE_UNAVAILABLE` warnings. Node/depth budget truncation exposes bounded `missingSupervisorIds` and warnings. Registry mutation is explicitly flagged.
- Record fields correspond to the browser's proposed v1 snapshot envelope plus internal `tenantId`, `scopeRootId`, `scopeGeneration` for the server observation broker. Server-side redaction is mandatory; do **not** forward these internal fields to browsers.
- Tests exercise real runtime supervisor hierarchy, roots and descendants, scoped access, foreign runtime, wrong session/tenant/generation, expiry, revocation, actor attempt to mint/read, node/depth cut, elapsed budget, concurrent supervisor registration, and actor payload non-disclosure.

## Important non-claims

1. **No true runtime generation fencing yet.** `scopeGeneration=1` is only the current supervisor *registration* placeholder; the supervisor registry currently has stable UUID identities without restart/incarnation lifecycle. Exact incarnation rollover still requires a runtime generation source and acceptance tests. Do not present it as a real restart-generation receipt.
2. **No tenant authentication inside ActorRuntime.** The trusted host bootstrap asserts the tenant string; it must authenticate the session and authorize the requested subtree before minting a grant. Guest callers cannot mint through this API, but a compromised host is out of scope. Do not treat a caller-supplied tenant ID as proof.
3. **No globally atomic image or native CONTROL delivery.** Concurrent registry changes, actor failures and disappearing workers are not fully represented. `partial` is deliberate even for an unchanged tree.
4. **No strict hard-real-time execution bound.** The read checks a monotonic elapsed budget between traversal nodes, but the initial registry copy and child index construction are synchronous host work. An actual asynchronous CONTROL service needs independent runtime-owned reservations, deadline enforcement and cancellations.
5. **No native HTTP gateway or ObservableActor event streaming.** Implement the server-side binding between the Java adapter and the separate observation broker only once session/CSRF/Origin/privacy checks and an authorized transport are proven.
6. **No backend parity claim.** Java-host source code is not an executable Oreslang guest contract; LLVM/.NET/Wasm/Forge require independent implementation and exact-head proof.

## Evidence / promotion

Focused exact-head CI workflow: `.github/workflows/supervisor-registry-observation.yml` uses GraalVM JDK 25 and executes `SupervisorRegistryObservationTest` and `ProximateSupervisorSeparationTest`. Authored JUnit sources and a workflow are **not passing tests** until GitHub reports actual executed steps and Surefire results. If source-org Actions jobs record `steps=null`, classify that as runner admission and do not claim implementation failure or success. Do not mirror private Java source to another organization while the privacy/provenance restriction is unresolved.

Remaining P0: replace this host-only registry adapter with **actual runtime-reserved supervisor CONTROL** request/response, stage bounded child fanout, authenticated tenant/subtree grants, true generation/incarnation tracking, and explicit child-termination/timeouts; prove real Java/GraalVM guest execution before admitting the endpoint to a production HTTP server. Keep the change draft until executable results are obtained.
