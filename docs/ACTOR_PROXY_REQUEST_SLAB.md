# Actor-owned proxy request slab — Java/GraalVM host implementation

Status: **experimental physical storage**, not wired to the guest Oreslang compiler or the reverse/forward HTTP data planes. `ActorProxyRequestSlab` is a trusted host substrate intended for the proxy applications under `ores-truffle-oreslang`.

## What is real here

- Physical preallocated **SoA** or **AoSoA** (16, 32 or 64 lanes) **primitive integer** columns. It uses an O(1) free list and **no per-admission or per-completion JVM wrapper object allocation**. A fixed eight-word receipt ledger per request slot is built into the same actor-managed charged region.
- Storage uses the existing `ActorRuntime.reserveActorShard` lease and is accessed only during a logical actor's turn, not based on worker thread identity. For PRIVATE actors it uses their existing direct-memory arena; for SHARED actors it uses a charge-accounted JVM primitive array. Untrusted actors are denied by the existing allocator.
- Handles are packed `long` values: row + monotonic incarnation for requests; receipt lane + monotonic serial for I/O claims. Serial counters never wrap to an old live handle. Duplicate, out-of-order, stale and forged receipts fail closed. A RETIRING slot stays occupied until **all** issued receipts settle.
- Actor finalization revokes the backing and refunds quota. An explicit `close()` refuses while in-flight slots are present. **Do not treat physical finalization as an external-I/O drain**: callbacks and buffers must be joined independently by the actor runtime before teardown.

## Explicit boundaries

The eight-receipt limit and maximum of 65,536 slots are initial bounded engineering choices, not measured optimal values. `pin` is bounded O(8); `admit` and `retire` are O(1). Scans of the packed fields and ingress routing remain separate future work. The implementation does not provide source-level `SoA<T>`, `AoSoA<T,N>`, ECS queries, zero-copy transport handles, HTTP/CONNECT, atomic admission across threads, per-request TLS state or network buffers. It is not safe to mutate one slab concurrently across independent actor turns. Never accept user-provided ticket or receipt numbers as external authority.

Real guest lowering, cross-backend LLVM storage, pinned byte buffers, receipt ownership checks, scheduler callbacks and cancellation/handoff drain must precede production release. In particular, the current host `ActorShardLease` turn checks prioritize safety over low overhead; measure backend wall-time, p99 latency and instruction/cache counts before calling this optimized in end-to-end terms.

## Tests and evidence

`ActorProxyRequestSlabTest` exercises the existing actor runtime across SoA/AoSoA layouts, slot reuse after two late completions, stale request ID, duplicate receipt, pin cap and backpressure, arena revocation, and private actor arena byte refunds. `.github/workflows/actor-proxy-slab-exact-head.yml` checks out the exact GitHub head and invokes focused JDK25/GraalVM Maven JUnit.

A **standalone JDK21 smoke** of the exact new slab Java source against an API-equivalent local actor-lease stub passed (including seeded 12,000-step slot exercise across ten capacities and layouts). That smoke validates JDK syntax and deterministic slot state transitions, **not** the real ActorRuntime owner checks, Maven/JUnit, JDK25, Graal AOT, LLVM, guest language, or actual proxying. No cross-organization private-source mirroring is authorized by this document.
