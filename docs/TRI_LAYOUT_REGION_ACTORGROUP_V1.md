# Tri-layout regions and data-oriented ActorGroup projections (v1)

Status (2026-10-19): **proposed language/runtime contract with a narrow Java host-only executable reference**. The reference `TriLayoutMotionStore` supports four fixed `double` fields, fixed capacity, one-thread-at-a-time host synchronization, three in-memory physical layouts, epoch reconciliation, stable generation-checked handles, and snapshots. This does **NOT** implement guest Oreslang syntax, production actor integration, borrow-permit lowering, GraalVM arena allocation, Java AOT, LLVM lowering, zero-GC native storage, SIMD, GPU kernels, or a parallel scheduler. Do not claim those features from this PR.

See also [DOP issue #211](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/211), [inverted-class issue #1424](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1424), [Task/region permits issue #1437](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1437), [ActorGroup event bus](ACTOR_GROUP_EVENT_BUS.md), [multi-membership](ACTOR_GROUP_MULTI_MEMBERSHIP.md), and the draft `INVERTED_CLASS_DOP_V1.md` / `SHARED_STORAGE_ECS_TASKS_V1.md` on Java PR #1423. Native `DopLayout.hpp` already has separate AoS/SoA/AoSoA layout primitives; this proposal covers **simultaneous materializations** and coherence, not a replacement for them.

## Decision: store all three when requested, with one mutable authority

One logical `Region<T>` may keep three physical representations resident at once:

- **AoS:** packed per-row field sequence, suitable for full-row operations and ordinary class-like APIs; does not imply N managed objects.
- **SoA:** one contiguous column per field, suitable for field-only scans, reductions and SIMD-friendly loops.
- **AoSoA<K>:** tiles of K rows, with per-field subcolumns inside each tile, suitable for chunked Tasks and some SIMD/GPU placement profiles.

Each representation has a **physical allocation identity** and a **materialized epoch**. At every committed write epoch, exactly one representation is authoritative for the modified field/range (v1 may track whole-region authority); all other resident representations are **versioned cached materializations**, never independently writable aliases. A read of a cached layout must reconcile to the requested epoch before exposing values. `stale_ok` / bounded-lag policies require explicit application opt-in and cannot satisfy strong actor-state guarantees.

Three resident buffers are not three independent writable instances. AoS, SoA, and AoSoA are distinct byte organizations: except for degenerate schemas, arbitrary mutable views across those representations cannot be simultaneously zero-copy. Native byte buffers may be zero-copy *within* their layout. A layout-to-layout refresh generally copies/transposes data. On demand and adaptive materialization control its cost.

### Proposed library API (illustrative, NOT executable Oreslang)

```oreslang
let region = Region<Motion>.with_capacity(
    100000,
    layouts: #all,               // physically retain AoS, SoA and AoSoA
    authority: #soa,             // initial authoritative representation
    aosoa_tile: 32,
    sync: #lazy,                 // refresh stale copies before access
    budget_bytes: memory_budget
);

let rows = await region.snapshot(layout: #aos, consistency: #epoch);
let xs = await region.project(Motion.x, layout: #soa, consistency: #epoch);

await region.compute(
    layout: #aosoa,
    reads: [Motion.vx, Motion.vy],
    writes: [Motion.x, Motion.y],
    fnc (chunk) { chunk.x += chunk.vx * dt; chunk.y += chunk.vy * dt; }
);
```

`layouts: #auto` may retain just one representation when memory or workload makes triple materialization a net loss. `#all` means reserve all three, **not** eagerly convert all three on every write; `sync: #eager` is an explicit alternative for read-heavy workloads, with mutation amplification measured. Existing single-layout `aos`, `soa`, and `aosoa<K>` contracts remain legal.

### Epoch and publication protocol

1. Admission validates schema/ABI, tenant/domain, allocated bytes, capacity, tile alignment, and exclusive authority. No untrusted actor memory is implicitly enrolled.
2. Read requests declare layout, field footprint, row interval and consistency. If `materialized_epoch < required_epoch`, wait for the owner to reconcile the needed fields/ranges, or fail by deadline/budget. No stale data masquerades as `latest`.
3. Write requests present a compiler-verified exclusive `ExecutionPermit<Region,AccessSet>`, resolve a concrete physical layout, and hold its scoped loan. An ownership-preserving promotion/migration is done *before* the writable span is exposed.
4. Commit publishes version, store incarnation, touched fields/ranges and any structural epoch using release/acquire ordering where needed; other materializations become invalid over their intersecting footprints. Readers of one materialization cannot observe a partially converted row.
5. A failed, cancelled or panicked Task cannot publish a partial logical commit. Either roll back via staged writes/undo or explicitly define partial-commit semantics with an observable progress token. Never advertise transactional safety based on a mutex alone.
6. Reclamation/hot-reload/teardown requires physical Task quiescence, draining pins, device/IO fences, and generation revocation. A cancelled Future is not evidence that the worker has stopped accessing the buffer.

P0 reference conservatively stores one global `epoch`, one `authoritative` layout, and one `materializedEpoch` per layout; access is synchronized at host method granularity and conversion is whole-region. It does not yet implement field-range dirty masks, partial rebuild, scoped loans, or atomic multi-region commits. Any production implementation must gate the missing pieces before parallel execution.

### Structural mutations and identity

Identity is `(owner/store incarnation, slot, generation)`, not a physical row pointer. `erase_swap` updates the relocated row-to-slot mapping while keeping the survivor's handle valid, invalidates the removed handle, and never revives a slot after generation exhaustion. `push`, `erase`, `reorder`, `reserve`, `migrate`, and hot-reload schema conversion are structural effects that exclude active column/row readers and writers. Publish a structural version and invalidate/reconcile every materialization before issuing a fresh view. Full-row snapshot is a value copy; native field spans are scoped pinned borrows.

Do not expose the host Java reference's internal arrays to actors. Its `synchronized` methods are deliberately not OresVM carrier-safe production locking; runtime admission must use non-parking permits/Task suspension and chunk-local locks or barriers.

## ActorGroup use without breaking actor isolation

A group is a **cooperation and data-indexing boundary**, not a substitute for an actor's exclusive lifecycle owner. An actor can belong to multiple groups. A group membership token is never a memory-borrow capability.

There are three distinct integration paths:

1. **Batch actor messaging:** bounded fanout via existing `ActorRef` mailbox or authorized event bus; messages retain ordering, backpressure, trust-domain copy/isolation and admission-vs-delivery semantics. No mutation of actor-private state occurs outside callbacks.
2. **Published read-only projection:** actors explicitly publish selected data-only fields to group-owned SoA/AoSoA/AoS snapshot shards at a declared epoch. Readers can query/reduce these buffers quickly, but projections are not authoritative actor state. Publication needs ACLs, revocation, quotas and explicit staleness.
3. **Group-owned region:** a shard/region owns POD fields independently of any private actor heap; actors may hold checked **handles** and submit changes via owner-authorized operations. Parallel `nb` Tasks may read/write disjoint regions only with verified permits. Actors whose behavior must react to changes are notified through authorized delivery, not directly entered by a compute worker.

A group spanning private/shared/untrusted isolates or tenants may not share raw pointers or mutable buffers. Promotion/copy/serialization is explicit. Private/untrusted bus subscriptions retain existing fail-closed rules. Multi-group memberships must not provide two independent writers to the same region, and each snapshot must be fenced by the correct membership/actor generation and trust domain.

### Sharding, read path and consistency

Partition by stable actor incarnation, group-defined key, or data locality; all partitions have bounded budgets. Query and reduction requests execute over local SoA/AoSoA shards. A coordinator combines per-shard values; for a full scan computation remains O(N), while only O(shard count) partial aggregates need travel back to the coordinator. Returning N individual rows still requires O(N) transfer. Single-epoch group snapshots need a **consistent distributed/partitioned cut** (barrier or MVCC-style pinned shard epochs), not merely sampling each shard's latest value at different times.

Avoid storing raw `ActorRef` in portable POD columns; use stable opaque numeric IDs plus protected runtime lookup tables. Stable row handles must not be confused with actor lifecycle authority.

## Memory and backend budget

Keeping three full resident layouts roughly triples the row payload versus one full materialization (plus AoSoA padding, handles, epoch tables, and fragmentation). Budget reservation must count all representations and temporary conversion buffers, with overflow checks *before* mutating the original. Permit `lazy`, `eager`, and `adaptive` refresh according to measured access locality. Do not promise a performance win when work alternates layout on each operation: frequent whole-region conversions can dominate computation. Optimize later with per-field dirty bitsets, dirty row ranges, chunk rebuild, selective columns, and measured layout-switch amortization.

- **LLVM:** zero-GC AOT native arena/region buffers, with lifetime and deterministic destruction; no mandatory managed-object-per-row design. LLVM source actors/Task lowering remain separate parity blockers.
- **Java/GraalVM:** full AOT target, minimal GC via actor-scoped arenas/off-heap/primitive-backed region storage; the current `double[]` Java host reference is heap-managed proof-only, not evidence of low-GC production behavior.
- **Freestanding core:** fixed-size layout descriptors, bounds/proofs and borrowed spans may live in `core`; heap-allocating region storage belongs in `alloc`. ActorGroup integration belongs in optional actor libraries; actor-free regions and ECS must remain supported.
- **ABI:** field order/type-width/alignment/endianness, tile size/padding, enums/booleans, stable-handle representation, source schema version, actor-domain identity and error outcomes require pinned cross-backend conformance. Strings, resources, pointer-bearing records and foreign handles are fail-closed pending independently verified lowering.

## Small executable Java host reference

`src/main/java/dev/oreslang/runtime/TriLayoutMotionStore.java` implements:

- packed `double[]` AoS, four typed `double[]` SoA columns, and tile-major contiguous `double[]` AoSoA allocated simultaneously;
- epoch-authority switching, lazy conversion before access, fixed-capacity `insert`/`eraseSwap` with checked generations, whole-row `get`/`set`, bulk `integrate`, column reduction and detached snapshots;
- immutable-by-copy snapshot isolation, closed-store rejection, checked size arithmetic and saturated store IDs;
- no leaked primitive arrays and no live mutable cross-actor access.

`src/test/java/dev/oreslang/TriLayoutMotionStoreTest.java` covers all-layout updates and reads, mixed authority transitions, deleted/foreign handle rejection, snapshot isolation, capacity/overflow, and 2,500 randomized operations checked against an independent row map. A standalone local OpenJDK 21 compile with `-Xlint:all -Werror` passed; four test bodies passed under a minimal locally supplied JUnit-compatible assertion harness. **Real Maven/JUnit, Oreslang guest compiler and LLVM parity are not verified** on this PR. The host harness was not real JUnit and does not prove production semantics.

## Release gates for an actual production ActorGroup feature

- [ ] Real JUnit/Maven exact-head build, guest source compiler and negative typing tests; check source syntax stays rejected until lowering is complete.
- [ ] LLVM typed native counterpart with no GC, ASan/UBSan/TSan where applicable, and actual LLVM Oreslang source parity.
- [ ] Compiler-verified `reads/writes` footprint, dynamically resolved handles, checked `ExecutionPermit`, scoped Task quiescence, cancellation/panic/hot-reload/teardown fences.
- [ ] Atomic or explicitly delimited multi-column commits, consistent cross-shard snapshots, dirty range and partial refresh correctness.
- [ ] Actor privacy/trust-domain rejection, multi-membership and cross-tenant tests, publication revocation, backpressure and memory accounting.
- [ ] Differential random/property fuzz with delayed readers, conversion faults, concurrent mutators, structural churn, ABA, recycled arenas, generations, schema evolution and device pinning.
- [ ] CPU and memory benchmarks on row-only, column-only, chunked, mixed-pattern and alternating-layout workloads at 1K/100K/1M rows; record conversion bytes, allocations, wall-time, cache misses and p95/p99 latency. Disable the extra caches when their cost exceeds benefit.

Neither the stored layouts nor ActorGroup membership changes the serial actor-handler contract, supervisor authority, source-level ActorRef transport, or independently schedulable Task semantics.
