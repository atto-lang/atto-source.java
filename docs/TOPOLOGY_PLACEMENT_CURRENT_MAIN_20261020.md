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
