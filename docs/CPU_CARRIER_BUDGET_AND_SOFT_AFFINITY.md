# OresVM CPU budget and actor lane locality (Java host slice)

Status: **implemented at Java host/runtime level on this branch; exact-tree
Maven/JUnit still required**. This is not an ECS/SoA codegen feature, actor
per-core binding guarantee, or proof of fully coordinated scheduler domains.

## Capacity at VM construction

`CpuCarrierBudget.effectiveProcessors()` begins with the JVM's available
processor count and applies more restrictive readable Linux cgroup limits:
`cpuset.cpus.effective` (v2), `cpuset.cpus` (v1 fallback), `cpu.max` (v2)
and CFS quota/period (v1 fallback). Fractional CPU quotas round upward
because carriers are integer workers. Missing or malformed files do not
produce imaginary permission to run on prohibited CPUs: they fall back to
the processor count/other detectable constraints. All checks happen at
startup, never per actor turn.

Default actor-pool parallelism uses this budget instead of making all
three independent SHARED, PRIVATE and UNTRUSTED pools each equal the entire
visible CPU count. The latter currently inherits the private-pool size,
so defaults choose private~=budget/4, shared=budget-2*private and untrusted
=private, each with minimum 1. If budget < 3, three independent pools
necessarily overcommit: this patch does NOT yet implement the nonparking
cross-domain physical turn permit broker needed to enforce a strict global
simultaneous-turn limit. Explicit `DispatcherConfig` and the existing
`ores.runtime.actor.shared.parallelism` override remain authoritative.

## Dispatch locality

`ActorAffinityDispatcher` keeps a stable logical preferred lane per actor.
With **soft lane** affinity, if the preferred lane has at least its bounded
share of pending turns, the dispatcher chooses a less backed-up existing
lane and increments an observable soft-spill counter. It does not create
threads/pools, bypass actor physical leases, touch private actor memory, or
change the actor's lifecycle owner. Explicit CPU-bound lanes never spill
across CPU targets. Spill placement is best effort under races, not an
ordering guarantee. Guest continuation semantics remain scheduler-owned.

## Unimplemented work

- Global nonparking admission across all actor and `nb` Task carrier
  domains, with CPU-quota fractional throttling and CONTROL wake priority.
- Topology/LLC/NUMA-aware queue ranking and bounded work stealing.
- Per-actor/region NUMA placement; executable InvertedArray SoA/AoSoA
  kernels and scoped region permits.
- Capacity refresh on live cpuset/quota changes (the budget is a snapshot).
- Hardware p95/p99, migration, LLC, context-switch and cross-NUMA benchmarks.

Run `CpuCarrierBudgetTest` and `ActorAffinitySoftSpillTest`, followed
by the full runtime regression suite. Exact-head CI which fails before
running steps is not execution evidence.
