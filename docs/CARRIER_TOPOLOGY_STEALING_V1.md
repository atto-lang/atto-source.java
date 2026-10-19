# Topology-local native actor carriers (Java/GraalVM, draft)

**Status:** incremental native pthread-carrier scheduling improvement stacked on Java #301; not merged, not a new M:N scheduler or guest Task lowering.

This increment retains a fixed set of long-lived native pthread carriers, per-carrier bounded actor work queues, global bounded fallback, and the original 5ms steal-age grace. It adds **immutable topology-ranked victim selection** to the existing `pollStealableTask` execution path:

1. Preferred carrier still receives actor turns based on the stable ActorId affinity key (existing #301).
2. An idle worker probes aged queued work from carriers sharing a known last-level cache (Linux `physical_package_id` + `cache/index3/id`).
3. It then probes other carriers with the same known NUMA node.
4. Finally, it probes all remaining enabled carrier lanes, including unknown/remote topology; no layout/affinity hint can prevent stealing.
5. A work queue must be nonempty **and aged** before it consumes the bounded dequeue-attempt budget. A newly enqueued local queue cannot hide an old remote queue.

Topology is discovered once on native worker startup from the **actual CPU affinity target after native binder admission**; sysfs is optional. Unknown CPU/cache/NUMA metadata defaults to the global rotating policy, without inventing topology. Startup publication replaces a compact immutable plan; no sysfs access or planner allocations occur inside the ready-queue hot path. CPU affinity binding remains implemented by #301's Linux `sched_getaffinity` / `pthread_setaffinity_np` support and Darwin's soft Mach tag. A carrier is not an actor owner, task owner, or trusted capability.

Metrics distinguish preferred lane hits, same-LLC steals, same-NUMA steals and other steals. Existing queue capacity, cancellation, shutdown, starvation protection, domain bulkheads, thread lifecycle, quiescence and actor heap ownership are unchanged. The localized scan is still O(worker slots) during idle steal attempts; further work should benchmark cache misses and improve work-conserving global budgets (#1303).

**Tests:** `CarrierTopologyPlanTest` checks known/unknown tiers, LLC-ID scoping across physical packages, cycle fairness, invalid metadata and exhaustive victim coverage at 2–64 workers. Existing `NativeCarrierExecutorTest` is the required real pthread smoke, and real `ActorRuntime` host/private/GraalVM gates remain required. This commit has not established passing Maven/Actions or native-image execution.

**Out of scope:** forced per-Task pinning, dedicated OS thread per actor, SCHED_FIFO/RR/DEADLINE privileges, hard realtime, cacheline-packed SoA storage, strict group CPU reservation, cross-isolate heap sharing, LLVM CPU topology discovery, portable guest-source affinity syntax.

See native LLVM sister work `feat/native-actor-carrier-locality-20261019` and cross-repo Java #303 / #1025 / LLVM #15.