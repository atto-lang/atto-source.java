# CPU locality, carrier affinity and ActorGroup data-oriented scheduling (2026-10-19)

Status: **partial executable Java scheduler hardening** on the draft branch `sched/amortized-carrier-cpu-affinity-20261019`. Do not confuse this slice with NUMA-aware Task execution, general work stealing or real guest `Region<T>` support. Related: `docs/TRI_LAYOUT_REGION_ACTORGROUP_V1.md` on PR #1477, Java DOP issues #211/#1424, Task effects #1437.

## Why: distinguish three costs

1. OS pthread context switching is the kernel rescheduling *physical carriers*; one actor or Task never needs its own pthread.
2. Logical actor/Task dispatching may happen on one long-lived carrier without an OS context switch, but touching unrelated actors/regions may churn caches.
3. Carrier migration across CPUs or a Task touching remote memory can incur LLC/NUMA costs. SoA/AoSoA improve locality when accesses are batched and chunk ownership is stable; they do not directly reduce kernel context switch count.

## What this branch actually changes

### Hard affinity: amortize rather than syscall every actor turn

`ActorRuntime.ActorCell.runBatch` previously called `NativeCarrierExecutor.bindCurrentThreadToCpu(cpu)` for every accepted actor turn when `ores.actor.affinity=cpu`. The call reaches Linux `sched_setaffinity`. Each `ActorAffinityDispatcher` lane has a dedicated one-worker underlying dispatcher, so successive turns on one physical carrier use the same desired CPU. The branch introduces `CarrierCpuBinding` (one `ThreadLocal` per *OS worker*, not per actor) and `ensureCurrentCarrierCpu(cpu)`:

- For a native single-carrier hard-affinity lane, the designated pthread binds during executor startup; construction does not return until that carrier reports successful startup affinity. Therefore no actor runnable can be admitted before the native carrier is pinned.
- The JVM-thread portability fallback cannot use the native carrier-startup handshake, so its first admitted actor turn still establishes hard affinity before guest code.
- Later turns on that same worker and CPU skip the redundant native syscall.
- A CPU change, elapsed 1-second reassertion interval, or non-monotonic clock observation triggers binding again. This is a **time-bounded reassertion**, not a claim of continuous kernel cpuset observation.
- An OS failure does not cache a successful pin; existing actor failure handling remains responsible for fail-stopping the admitted actor turn.
- Native carrier retirement clears the thread-local value. Replacement JVM fallback workers bind on their own thread-local first turn.
- CPU validity remains checked before creating affinity lanes. Hard mode must not silently be interpreted as soft affinity on platforms without supported native CPU binding.

**Not yet implemented:** asynchronous CPU-hotplug/cpuset subscription, a process-wide topology coordinator, arbitrary external FFI affinity-mutation notification between observations, or CPU-independent migrations/stealing of active actor turns. Native startup pinning and syscall-count validation are now executable; dynamic topology handling still needs separate carefully-fenced work.

### Opt-in default worker sizing for three actor domains

Setting `-Dores.runtime.actor.pool-sizing=cpu-budget` makes `ActorRuntime.DispatcherConfig.defaults()` call `ActorPoolSizing.cpuBudgeted(availableProcessors, optionalSharedOverride)`. It budgets THREE disjoint actor pools, allocating equal physical worker capacity to PRIVATE and UNTRUSTED and the remainder to SHARED.

- On 8 allowed logical CPUs: PRIVATE 2, SHARED 3, UNTRUSTED 2: total 7.
- On 16: PRIVATE 4, SHARED 7, UNTRUSTED 4: total 15.
- With 1-4 CPUs: three actor domains each need a minimum one worker; 1-3 CPUs may remain oversubscribed.
- Explicit `ores.runtime.actor.shared.parallelism` remains supported when it fits in the budget; impossible requests fail closed.
- The property defaults to `legacy` to preserve existing sizing/behavior pending real performance testing.
- Explicit `new DispatcherConfig(...)` remains authoritative. `cpu-budget` affects only defaults.

When `ores.actor.affinity=cpu`, the runtime now additionally requires enough
**distinct configured logical CPUs for every dedicated actor carrier across all
three domains**: `2 * privateParallelism + sharedParallelism`. The three
dispatchers already use consecutive global lane offsets, so satisfying this
gate means PRIVATE, SHARED and UNTRUSTED lanes do not wrap modulo the CPU list
and become hard-pinned competitors on the same logical CPU. Impossible hard
pinning fails before any actor pool is created.

The configured CPU order remains host-authoritative. This gate does not infer
physical cores or SMT siblings: for cache-sensitive deployments, list one
logical CPU per physical core before adding sibling threads, until verified
topology-aware ordering is integrated.

This is **not** yet a process-wide CPU coordinator: CONTROL/root/ordinary Task pools, JVM GC, host I/O and OS processes are not counted; the apparent single-worker headroom is not pinned or reserved. `availableProcessors()` is not a full physical-core/NUMA topology enumeration. For a real global budget, include cpuset and CPU quota, worker-domain requirements, SMT, memory locality, dynamic topology changes, and priority/fairness.

## Next scheduler/region integration slices (not implemented)

1. **Topology:** expose allowed Linux CPU mask, cgroup CPU quota, physical cores/SMT siblings, cache/NUMA node maps, and online/offline reconfiguration. Keep macOS/other OS affinity fail-closed for hard pinning; soft affinity degrades gracefully when unsupported.
2. **Ready-work sharding:** store Task and actor queue descriptors in cache-conscious contiguous columns where profiling supports it, but keep actor-private heaps and exclusive supervisor authority unchanged. Group membership is not state-borrow authority.
3. **Safe Task stealing:** only queued/unclaimed logical work is eligible. Rank same worker, shared LLC, same NUMA, then remote NUMA. Use execution-domain capability/Task lease, `Send` proof, region generation, row/field read/write permissions and physical completion fences. Never steal an active actor continuation, thread-affine FFI, mutable loan or CONTROL work. Hysteresis prevents bouncing.
4. **Tri-layout placement:** `Region<T>` may maintain AoS, SoA and AoSoA concurrently with exactly one current mutable representation. Scheduler moves computation toward a region shard. It does **not** transpose all rows when moving a Task; stale projections rebuild on explicit layout access and must obey epoch/dirty-range fences.
5. **CONTROL priority/fairness:** choose bounded admission and explicit starvation constraints without making `SCHED_FIFO` or `SCHED_RR` default policies.

## Validation

- Checked-in `CarrierCpuBindingTest`: skip repeated binds across 10,000 turn-like invocations; per-thread isolation, CPU change, timed reassertion, clock regression, native bind failure retry, invalid CPU.
- Checked-in `NativeCarrierExecutorTest`: logical-turn multiplexing, startup CPU established before the first runnable, and rejection of multi-carrier pools for the single-CPU startup-pinning constructor.
- Checked-in `ActorPoolSizingTest`: small CPU counts, 5-256 CPU budgets, explicit SHARED requests, fail-closed oversubscription and determinism.
- Local OpenJDK 21 compiled exact GitHub blobs with `javac -Xlint:all -Werror` using minimal compatible JUnit test stubs. All **9** source test method bodies passed. This is **host-model-only** evidence; it is not a full Maven/JUnit, Graal Native Image, JNI integration or LLVM differential pass.
- Exact PR-head GitHub CI and runnable benchmarks remain required.

## Future acceptance gates

- Full exact-head Maven/JUnit, native pthread JNI and guest tests; Linux CPU-mask tests and non-Linux expected rejection; fail-closed pin failure during a live actor turn.
- Control queue starvation, thread replacement, shutdown, executor creation failure, CPU-hotplug/cpuset mutation, 1-64 CPU hosts, real vs synthetic topology; no actor lease double-execution.
- 1K/100K/1M actors with mixed Task workloads and varying skew: throughput, p95/p99 latency, queue wait, involuntary switches, migrations, LLC misses, remote NUMA access, affinity syscall count, tri-layout conversion bytes, allocated/live bytes and total carrier count.
- Run independent experiments for `legacy`, `cpu-budget`, `affinity=none/lane/cpu`; never extrapolate SIMD/NUMA performance from method-count tests alone.

The target is stable long-lived pthreads, bounded and fair CPU resources, actor/TASK execution-domain safety, and low-cost data-local computation—not per-actor CPU ownership.
