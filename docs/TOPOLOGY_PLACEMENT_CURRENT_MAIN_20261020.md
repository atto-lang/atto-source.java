# Topology and placement primitives on current main

This is the first phase of recutting stale NUMA PR #1500 onto current Java main.

It deliberately contains only dependency-free host primitives and tests:
`CpuTopology`, `ExecutionPlacement`, their JUnit coverage, and the JDK-only
`RegionLocalitySmoke` runner. It does **not** port the old stacked
NativeCarrierExecutor/JNI affinity implementation.

Security and correctness boundaries:

- NUMA and cache metadata are advisory scheduling evidence, not borrow authority.
- Unqualified numeric cache IDs do not establish shared LLC identity.
- NUMA selection probes at most 64 carrier candidates and otherwise falls back
  to the stable affinity hash.
- Region placement accepts only soft TASK hints; CONTROL, actor-turn and
  thread-bound placement cannot claim region locality.
- No actor state, native pointer, GPU buffer or memory-residency authority is
  granted by a locality hint.

The next phase may integrate these primitives with a current-main carrier
affinity implementation after this independent layer has exact-source JDK
and Maven evidence.

## October 21 data-locality audit and bounded placement hardening

The host advisory planner now considers two distinct locality requests:

- A trusted **CPU target** selects the lowest observed locality rank within a
  fixed 64-slot scan: same advertised logical CPU, then same qualified LLC,
  then same known NUMA node. Unknown locality retains the original hash.
- A trusted **NUMA target** selects among eligible observed carriers using
  deterministic highest-random-weight slot ranking, **not** the first local
  slot after the hash. This prevents a long run of remote slots from funneling
  unrelated stable Task keys onto one boundary carrier, which otherwise
  concentrates private L1/L2 working sets. Selection is allocation-free and
  retains a stable target for each key while the pool/topology are unchanged.

**Current limitation:** Linux discovery records the highest known unified L2+ cache per CPU, not a complete L1D/L2/LLC hierarchy, SMT sibling map, measured cache occupancy, or cache-line size. Thus same-LLC ranking does not prove same-L2 or reduced L1D/L2 miss rates. CPU targets and system masks can change after topology discovery.

These are preferences among *advertised host carrier targets*, not observations
that the OS is currently running the task on a particular CPU. They do not
move pages, pin pthreads, enable per-actor execution parallelism, grant Task
memory loans, select a GPU, or prove a reduction in hardware cache misses.
Runtime wiring is **not present in this phase**. A remote-only bounded window
returns the preexisting hash instead of scanning thousands of slots.

### Audited integration gaps and sequence

1. **Data layouts (Java #1822, #1505; LLVM #657).** Preserve one authoritative
   actor-owned representation at a time for AoS, SoA, AoSoA and SoAoS. For
   independent SIMD/ECS column slices, disjoint interval loans and generation
   checks must precede any parallel Task access. Cache-line padding of
   *mutable shared metadata* is a separate issue from padding every entity,
   which often increases footprint and miss rate. Do not claim SIMD from tile
   names: inspect real vectorization and AoSoA remainder paths.
2. **CPU locality (Java #1496/#1773; LLVM #672).** The OS schedules physical
   carriers; logical actors can suspend/resume on different carriers. Preserve
   single-flight actor execution and CONTROL priority under any affinity scheme.
   Use effective allowed cpusets, detect carrier retirement/hotplug, and permit
   bounded fairness spill/steal rather than starving work to preserve affinity.
   The Linux pthread opt-in and Java hard-binding drafts require independent
   integration and CI; this planner is not wired into either.
3. **NUMA memory policy.** A CPU/NUMA topology hint is not proof of physical
   page location. Allocation policy, first-touch, actual page migration,
   NUMA telemetry, and cross-node spill need a trusted-host API with explicit
   failure/fallback behavior. Never infer ownership or page residence from
   sysfs numeric cache IDs alone.
4. **GPU affinity (Java #703/#1024).** The current typed GPU buffer/fence and
   ActorGroup placement work remains draft/fail-closed. Device context, per-device
   ordered submission queue, stream/fence lifecycle, pinned staging buffers,
   resource budgets, data transfer cost, and kernel-dispatch continuations must
   be bound to an authorized device broker. Never block actor carrier threads
   while awaiting GPU completion or carry a host-native pointer in an actor
   message; do not describe GPU affinity as implemented.
5. **Measurement gate.** Establish CPU-target-local vs spilled throughput,
   actor p50/p95/p99 turn latency, CONTROL-to-delivery latency, Task starvation,
   migrations, run-queue occupancy, bytes moved, remote-NUMA accesses, cache
   miss-rate/MPKI, and allocated bytes per turn. Capture perf counters and
   cache-line sizes per real machine; test dense sequential, sparse/gather,
   hot/cold fields, heterogeneous ECS archetypes, and 1/2/4+ socket/cgroup
   placements. Compare AoS/SoA/AoSoA/SoAoS with identical data and work,
   controlling warmup, GC, Turbo/frequency, and thread oversubscription.
   Record absolute throughput plus normalized measurements, and **reject a
   locality optimization that regresses tail latency/fairness** despite a
   lower miss count.

### Current scope of evidence

The source-only JDK component gate tests the deterministic planner and its
bounded 64-slot scan, including a 4,096-key adversarial NUMA-boundary fixture.
It cannot establish a hardware miss-rate improvement, Linux JNI binding,
native/Graal AOT behavior, multi-backend parity or actual GPU work.
