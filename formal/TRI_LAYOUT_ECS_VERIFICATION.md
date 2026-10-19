# ECS layout-residency verification and host trace refinement

Status: **bounded model checked locally; Java host refinement requires exact-head real JUnit CI; Oreslang guest runtime/LLVM not certified**. This is an additional check gate for Java tri-layout host reference PR #1477 and the one-authoritative-layout contract in docs/TRI_LAYOUT_REGION_ACTORGROUP_V1.md.

## All seven residency combinations

A logical Region may retain exactly 1, 2 or 3 of AoS, SoA and AoSoA physical arrays. One layout is authoritative for every committed epoch. Other resident layouts are versioned caches. They MUST NOT become independently writable instances. AoS/SoA/AoSoA do not generally share byte geometry or mutable zero-copy aliases. Stale strong reads require reconciling the requested materialization before access.

## Finite-state model

The Python model in formal/tri_layout_model.py explores three logical rows crossing an AoSoA tile-size-two boundary, two independently addressable fields, two published write epochs, and seven nonempty resident layout subsets. It includes real physical geometry for AoS, SoA and AoSoA, a separate canonical logical oracle, cached epochs, authority migration, pinned readers, staged exclusive writers, atomic commit and abort. It checks physical content, not just whether version counters happen to agree.

A writer in a different layout must first reconcile its cache; a commit publishes only after the full staged state is valid. Read pins cannot observe an in-progress refresh or mutation. A failed/cancelled write aborts without touching either the authoritative or stale physical arrays. Missing layouts have no allocated storage or version.

RegionPermit admission validates owner, store generation, tenant, region identity, field set, row interval, selected resident layout, and read epoch. ActorGroup multi-membership never grants borrow permission. Multiple snapshot readers may use different current layouts at the same epoch. V1 conservatively disallows ALL overlapping Task write permits into one region, including disjoint fields in different physical copies, until atomic per-field/range versioned merge and conflict resolution are implemented.

Physical buffer reservation counts all resident AoS/SoA/AoSoA payloads, AoSoA tail padding, fixed reference metadata, and when fault-atomic transpose is requested a conservative scratch buffer. Reject overflow and over-budget allocation BEFORE mutation. Production must compute its true ABI alignment, allocator overhead and tenant quota independently.

## Evidence and negative mutations

The full finite state space must pass for AoS, SoA, AoSoA, AoS+SoA, AoS+AoSoA, SoA+AoSoA, and all three. Six intentionally broken paths must yield counterexamples: stale strong read; skipped reconcile; false current-epoch marking; second active writer; partial abort; and wrong AoSoA tile offset.

Run (Python 3.10+, no dependencies):

    python3 -m unittest discover -s formal -p test_tri_layout_model.py -v
    python3 formal/tri_layout_model.py

The concrete Java JUnit test TriLayoutMotionFormalRefinementTest exercises all nine authority-layout pairs, all nine row-choice pairs, deleted-row relocation across tiles, stable handles, epoch reconciliation and snapshots. Java TriLayoutMotionStore always physically allocates ALL THREE representations: single and paired residency are presently MODEL-only coverage. This is a Java HOST reference, NOT guest source compilation or cross-backend refinement.

## Production blockers

- Guest-language Region and ActorGroup syntax, type/effect/borrow enforcement, Task scheduling, physical quiescence, read/write permission proof and snapshot barriers.
- Fine-grained field/range versions with atomic multi-layout and multi-shard publish or rollback, and memory-ordering (release/acquire) proof.
- Cross-actor owner and trust-domain admission, untrusted/private isolation, tenant budgets, multi-group publication fences and lifecycle revocation.
- Fault injection at transpose/commit/allocation boundaries and native concurrent execution witnesses.
- LLVM fully AOT / zero tracing-GC implementation, Java minimized-GC arenas, and real compiled guest conformance on both backends.
- Wider AoSoA tiles, SIMD alignment, float NaN payload handling, generation overflow, schema migrations, pointer-bearing types, GPU/NUMA and benchmarks.
- Actual exact-source CI steps; failed/zero-step Actions runs are NOT verification evidence.

Bounded exploration is a finite semantic proof under its chosen model and bounds, not an unbounded correctness theorem or proof that a concrete implementation refines the model.
