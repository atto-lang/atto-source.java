# Five portable Java policy algorithms authored in AttoLang

## Scope
Stack on draft Java PR #2544; do not touch or merge active parallel agents'
harness, text, actor-sync, stack-trace or source-admission work.

Source-owned algorithms now live in five distinct `stdlib/*.atto` modules:
1. `layout_policy.atto` — AoS/SoA/AoSoA bounded length and offset planning from Java `DataOrientedLayout`.
2. `region_policy.atto` — slice validation and read/write conflict independence from Java `DopRegionSchedule`.
3. `dense_index.atto` — canonical unsigned dense32 logical index coding from Java `ManagedReferencePolicy`.
4. `actor_result_policy.atto` — fan-in limits and bounded local correlation-ID uniqueness from Java `ActorResults`.
5. `task_parallel_policy.atto` — bounded turn/slot admission math from Java `TaskMapParallel`.

These are substantive guest algorithms, **not .ores-to-.atto renames** and not Java wrappers. All five are closed-registry `ores.std` sources (legacy namespace `std/` also allowed, but explicit `.ores` is rejected for these new modules). No host OS access, Java interop, synchronous I/O, allocation capability, reflection or scheduler service.

## Security and remaining Java exceptions

- `layout_policy` is intentionally restricted to rows <= 1,000,000, fields <= 1,024, scalar <= 256 bytes, alignment <= 4,096, AoSoA lanes <= 1,024; every intermediate fits signed i64. Host `DataOrientedLayout.Plan` still checks native allocation and full-domain overflow.
- `region_policy.independent_metadata` is for *metadata evaluation only*. A guest-provided `same_root` value is **not** a runtime-minted Root identity. Host `DopRegionSchedule` must still verify actual roots and dispatch authority.
- `dense_index` uses positive i64 unsigned word representation; Java `int` carries equivalent bits, not identical signed numeric rendering. The host still owns and validates the reference table and the actual bits at FFI/storage boundaries.
- `actor_result_policy` local integer IDs are **never authenticated actor addresses/UUIDs**. To cap worst-case O(n²), the borrow-only uniqueness check supports at most 256 IDs even though count admission accepts 65,536. Trusted host `ActorResults` remains responsible for authenticated reply correlation, future observation and actor origin.
- `task_parallel_policy` is planning ONLY. Child admission, cancellation, quiescence, permits, actor turns and TaskScope ownership remain in Java's runtime. A computed budget never authorizes scheduling.

No Java implementation removal in this tranche. Java currently remains an authoritative safety guard/compatibility implementation until verified compiler-backed parity and API lowering replace source-expressible portions safely. No new runtime host primitive is introduced; the sole Java production edit is a compiler-owned source-registry entry.

## Acceptance and evidence

`CanonicalAttoHostPolicyExtractionTest` pins the five imported guest sources, exact unit IDs, no Java islands, no shadowing and no legacy source duplicates; runs five compiler-backed `.atto` fixtures; and compares bounded layout, conflict and dense32 values to the existing Java host oracle. This test is **authored, NOT EXECUTED** until real exact-head job steps and guest receipts exist.

Before claiming implementation parity or replacing Java, require actual nonzero-step Java/Graal guest tests (positive and negative), comprehensive parameterized differential tests and independent review. Port the same fixtures to LLVM, .NET NativeAOT, Wasm and Forge/Cranelift. Source-free host smoke, compiler-only admission and `steps=[]` are not guest-parity proof. No cross-org private source mirroring or privileged access introduced.

## Deterministic differential matrix (additional acceptance gate)

`AttoJavaPolicyDifferentialTest` now generates one real compiled .atto
guest per module and compares: 72 seeded AoS/SoA/AoSoA layout shapes and
offsets with Java DataOrientedLayout, 90 seeded region-root/slice/mode
conflicts with Java DopRegionSchedule, unsigned dense32 extremes with
ManagedReferencePolicy, and 80 bounded task-admission vectors with an
independent pure Java arithmetic oracle. Invalid negative cases are also
pinned. Task admission is a pure *contract vector*, not evidence of Java
scheduler integration. Actor-result identity remains host-owned.

This code is authored but the compiler-backed tests remain NOT_EXECUTED
until exact-head nonzero-step CI job/fixture receipts exist.
