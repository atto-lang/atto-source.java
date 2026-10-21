# Topology-local native actor carriers (Java/GraalVM, draft)

**Status:** incremental native pthread-carrier scheduling improvement stacked on Java #301; not merged, not a new M:N scheduler or guest Task lowering.

This increment retains a fixed set of long-lived native pthread carriers, per-carrier bounded actor work queues, global bounded fallback, and the original 5ms steal-age grace. It adds **immutable topology-ranked victim selection** to the existing `pollStealableTask` execution path:

1. Preferred carrier still receives actor turns based on the stable ActorId affinity key (existing #301).
2. An idle worker first probes an SMT sibling / same physical core when both CPUs have a package-qualified `core_id`.
3. It next probes a package-qualified shared **unified L2**, then a package-qualified shared highest unified L2+ cache (the LLC).
4. It then probes other carriers with the same unambiguous known NUMA node.
5. Finally, it probes all remaining enabled carrier lanes, including unknown/remote topology; no layout/affinity hint can prevent stealing.
6. A work queue must be nonempty **and aged** before it consumes the bounded dequeue-attempt budget. A newly enqueued local queue cannot hide old remote work.

Topology is discovered once on native worker startup from the **actual CPU affinity target after native binder admission**; sysfs is optional. Unknown CPU/cache/NUMA metadata defaults to the global rotating policy, without inventing topology. Startup publication replaces a compact immutable plan; no sysfs access or planner allocations occur inside the ready-queue hot path. CPU affinity binding remains implemented by #301's Linux `sched_getaffinity` / `pthread_setaffinity_np` support and Darwin's soft Mach tag. A carrier is not an actor owner, task owner, or trusted capability.

Metrics distinguish preferred lane hits, same-core steals, same-L2 steals, aggregate same-LLC steals, same-NUMA steals and other steals. The existing same-LLC counter deliberately remains an aggregate: same-core and same-L2 peers necessarily qualify as same-LLC when the planner admits those finer tiers, preserving the old monitoring meaning while exposing more detail. Existing queue capacity, cancellation, shutdown, starvation protection, domain bulkheads, thread lifecycle, quiescence and actor heap ownership are unchanged. The localized scan is still O(worker slots) during idle steal attempts; further work should benchmark cache misses and improve work-conserving global budgets (#1303).

**Tests:** `CarrierTopologyPlanTest` checks known/unknown tiers, LLC-ID scoping across physical packages, cycle fairness, invalid metadata and exhaustive victim coverage at 2–64 workers. Existing `NativeCarrierExecutorTest` is the required real pthread smoke, and real `ActorRuntime` host/private/GraalVM gates remain required. This commit has not established passing Maven/Actions or native-image execution.

**Out of scope:** forced per-Task pinning, dedicated OS thread per actor, SCHED_FIFO/RR/DEADLINE privileges, hard realtime, cacheline-packed SoA storage, strict group CPU reservation, cross-isolate heap sharing, LLVM CPU topology discovery, portable guest-source affinity syntax.

See native LLVM sister work `feat/native-actor-carrier-locality-20261019` and cross-repo Java #303 / #1025 / LLVM #15.

## Audit hardening — October 19, 2026

- Periodic global sweeps use an **independent rotating cursor**, rather than sharing a cursor sampled only every 16th poll. This prevents fixed-step aliasing from indefinitely overlooking remote lanes when worker count is a multiple of 16.
- Victim offset arithmetic is widened to 64-bit intermediate calculations before modulo, avoiding duplicate or omitted candidates when Java's signed 32-bit steal counter wraps.
- Linux cache discovery is no longer tied to `index3`. A bounded scan of at most 32 sysfs cache indexes admits only **Unified L2** for the L2 tier and the highest consistent **Unified L2+** identity as LLC. Core/L2/LLC numeric IDs require a known physical package qualifier. Conflicting same-level cache IDs fail that cache fact closed without fabricating locality.
- Sysfs text reads are capped at 64 ASCII bytes before decoding, NUMA directory inspection is capped at 256 entries, and ambiguous multi-NUMA membership degrades to unknown.
- Mocked sysfs tests, Integer.MIN_VALUE/MAX_VALUE victim coverage, and bounded global-probe coverage for up to 128 workers were added.
- Real CPU/NUMA performance and long-running fairness remain unqualified until source-exact CI and benchmarks complete.


## L1/L2 interpretation

Same physical `core_id` is treated as the nearest *scheduler* relationship after the exact preferred carrier, but it is not automatically the fastest placement for two simultaneously CPU-bound Tasks: SMT siblings share execution resources and may contend. The L2 tier therefore remains separately visible in metrics. Benchmark policy may eventually choose to skip the same-core tier for saturation workloads while retaining it for cache-hot continuations; this draft does not guess that workload property.

No L1 cache-sharing claim is made. Linux topology does not expose a portable, architecture-independent meaning that justifies scheduling based on a naked L1 numeric ID. Hardware-counter evidence—not topology names alone—remains the acceptance gate.
