# Selectable physical residency — Java CPU host vertical slice

This changes the **actual Java host physical buffers** of `TriLayoutMotionStore` from unconditional triple allocations to a checked subset of `AoS`, `SoA`, `AoSoA`. This is a concrete `alloc` reference, not yet Oreslang `Region<T>` guest lowering.

Constructors:

```java
new TriLayoutMotionStore(3, 2);  // existing compatibility: all 3
new TriLayoutMotionStore(3, 2, Set.of(Layout.SOA)); // exactly 1
new TriLayoutMotionStore(3, 2, Set.of(Layout.AOS, Layout.AOSOA)); // exactly 2
new TriLayoutMotionStore(3, 2, Set.of(Layout.SOA, Layout.AOSOA),
                         Layout.SOA, primitivePayloadBudgetBytes);
```

**Safety contract**

- The selected `Set<Layout>` is copied into `EnumSet` at construction, and immutable `residentLayouts()` snapshots are returned.
- Absent layouts are **physically null**, are stamped `-1`, and all read, write, snapshot, reduction, integration and stamp requests reject them. There is NO implicit fallback and no automatic allocation of a missing buffer.
- Any selected layout may be the current authority. Exactly one logical authority is writable per epoch; when a different resident layout becomes the writer, whole-region conversion refreshes that physical replica first. Other materializations retain stale versions until a requested strong read or write reconciles them.
- Only a fully copied materialization gets a current epoch. A failed conversion remains marked stale and is retried; the host's `synchronized` boundary hides a partially refreshed stale buffer. This is **not** an atomic multi-shard transaction or user-visible borrow permit.
- Stable handles carry store identity, slot and generation. `eraseSwap` keeps the relocated survivor's handle valid, and prevents ABA on recycled slots.
- Before allocation, checked arithmetic bounds each physical JVM array and counts all resident primitive double buffers, three index arrays, generation array and epoch table. `reservedPayloadBytes()` is a **lower bound on memory reservation**, not Java heap RSS/allocator overhead, snapshot allocation, transpose scratch or tenant-global quota. The API rejects over-budget payloads before allocating; deployment must add heap margins and external quotas.

`TriLayoutSelectableResidencyTest` exercises all 7 sets of physically allocated buffers, all selected authority changes and 3x3 row update positions, stale epoch refresh, absent-layout fail-closed cases, tile padding, snapshot copies, generation-checked swap delete, bounded reservation, and 1,200 seeded mutations per residency subset. The previous all-three-layout tests remain intact.

**Proof boundary / release blockers:** These are actual Java host `double[]` arrays and one-thread-at-a-time synchronization. They do NOT constitute Oreslang guest-level typed `Region<T>`, AST/borrow checking, physical Task quiescence, actor isolation, concurrent disjoint field permits, off-heap arenas, full-AOT GraalVM, LLVM zero-GC, or backend parity. The independent seven-subset abstract model remains a separate tier. Run real JUnit and full compiler tests before merging.
