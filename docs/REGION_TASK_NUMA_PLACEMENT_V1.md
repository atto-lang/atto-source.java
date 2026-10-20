# Host Task locality from owned regions — staged integration

Status: 2026-10-20. This branch implements an **advisory native-carrier
placement path**, not guest-visible Task/Region borrow authority, real page
placement or GPU execution.

## Executable host path

- Trusted host code which already owns valid region metadata can request
  `ExecutionPlacement.Hint.regionTask(affinityKey, preferredNuma)`.
- Hint accepts only `TASK + SOFT`; CONTROL, actor turns, thread-bound work,
  invalid negative values and out-of-contract NUMA IDs cannot acquire this
  placement via the region factory.
- `NativeCarrierExecutor.executePlacement` resolves the hint to one enabled
  carrier slot with a **known successful OS CPU affinity target** in that NUMA
  node. Search begins at the stable hashed preference but probes **at most 64
  slots**, even if the carrier pool has thousands of lanes. If no eligible
  match is observed within that budget, it falls back to the ordinary
  affinity lane; this can sacrifice affinity quality but bounds per-Task
  scheduling overhead. Never infer page residency from the preference.
- Existing bounded soft spill to same-cache/NUMA peers and then global
  queue, aging/steal fallback, cancellation, shutdown and actor execution
  ownership are unchanged.
- The CPU target lookup is prebound once at executor construction; it does
  not create a per-turn array or read sysfs inside the scheduling hot path.
- The kernel may still migrate threads after CPU hotplug/cpuset changes.
  A recorded CPU target is **not proof of actual physical page placement**.

## Corrected LLC evidence

A numeric sysfs cache ID is valid only as part of a complete known
(package, level >=2, numeric ID) tuple. Independent cache instances on
different packages or at different levels may reuse numeric IDs. Invalid,
missing or partially qualified evidence degrades to NUMA or remote ranking.
The backwards-compatible three-argument synthetic Location constructor
no longer creates a shared-LLC proof from an unqualified ID.

## Current separation of authority

A region affinity key is NEVER a borrow token. It grants no rights to:
actor `state`, Task read/write access, native pointer transfer, current
pthread TLS, untrusted actor capabilities or GPU device buffers.

Before wiring actual Oreslang source-region Tasks to this host path:

1. Integrate compiler-established allocation identities and disjoint
   access/side-effect admission from ECS planner draft Java #1623 and
   Send/Sync region plan draft Java #1474.
2. Integrate runtime-enforced scoped nonescaping region loans, including
   cancellation, resize, suspension, drop, lifetime and generation fences.
   Native host model LLVM #661 is relevant but **does not** itself grant
   guest-source access authority.
3. Have the trusted allocator supply a verifiable memory-home descriptor,
   or explicitly mark locality as unknown; do not allow guest strings/integers
   to claim an L2/LLC/NUMA memory-residency proof.
4. Measure on real pinned workloads across working-set sizes, CPU quotas,
   same-core SMT competition, L2/LLC miss counters, NUMA remote accesses and
   p95/p99 latency before making NUMA bias default.
5. For GPUs use explicit authorized device/queue/stream and allocation
   generation leases. Host NUMA hints never imply GPU kernel affinity.

## JDK-only targeted gate

Run from the PR branch checkout:

    python3 scripts/check-region-locality.py

This compiles the **actual** CpuTopology, ExecutionPlacement and standalone
RegionLocalitySmoke Java sources at release 21 with all javac warnings as
errors, then performs 10,000 seeded NUMA-selection oracle checks, invalid
hint admission tests and qualified/unqualified cache identity checks.

It tests only these three host Java sources, not the native carrier JNI loop,
real JUnit/Maven, actor state confinement, LLVM compiler execution,
GraalVM AOT or hardware cache performance. Those remain draft release gates.
