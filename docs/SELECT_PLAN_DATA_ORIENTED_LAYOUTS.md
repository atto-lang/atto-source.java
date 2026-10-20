# Data-oriented SelectPlan and DynamicSelectPlan storage

Status: **design contract / implementation gates, not an implemented multi-layout engine** (2026-10-20).
Tracked Java staging: [dynamic plan PR #1747](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1747)
and [static loop-hoisting PR #1753](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1753).
Cross-reference [inverted classes and columnar DOP](INVERTED_CLASS_DOP_V1.md),
[actor-independent ECS and shared storage](SHARED_STORAGE_ECS_TASKS_V1.md), and
[selection/cancellation semantics](channels-select-and-actor-cancellation.md).

## Principle: logical select is independent of physical layout

A SelectPlan is **one logical, ordered set of arms**. A DynamicSelectPlan is
a versioned sequence of immutable plan snapshots. The compiler may reuse a
snapshot in hot loops. A selection execution is **not** the snapshot: it owns
fresh cancellation, winner arbitration, ready observations and waiter registrations.

Separate the following storage lifetimes:

1. **Plan description, cold-ish:** source arm IDs, stable logical indices,
   keys, policy hints, operation classes, target capability IDs, payload
   descriptors, source/actor generation, immutable metadata.
2. **Plan snapshot, read-mostly:** one authoritative physical representation,
   logical-index-to-physical-location mapping, stable generation, versioned FAIR
   state and layout ID. Published atomically; old snapshots stay alive while
   outstanding selection executions reference them.
3. **Selection execution, short-lived/hot:** registration ID, pending/decided
   state, selected arm, cancellation/cleanup authority, admission credits,
   ready probes, Future callbacks, and waiter handles. Never shared merely
   because an immutable descriptor is shared.

Do **not** represent each execution as a new heap-allocated copy of all
logical arm descriptions. Equally, do **not** keep live channel/Future waiters
armed across loop iterations to save allocation; they belong to the execution.

Neither an ECS world nor an actor is required to use ordinary channels/tasks.
The ECS analogy is for **dense allocation, archetype grouping, generations,
chunked iteration and ownership**; it must not be a mandatory language/runtime
dependency. Freestanding `core` must not acquire an OS, heap or ECS dependency.

## Storage alternatives: one authoritative layout per snapshot

| Layout | Example physical representation | Favored access | Tradeoff |
| --- | --- | --- | --- |
| AoS | `Arm[] {kind, target, key, payload, ordinal}` | small plans, complete-arm access, frequent edits | scanning one field touches unrelated data |
| SoA | `kind[]`, `target[]`, `key[]`, `payloadRef[]`, `ordinal[]` | kind/readiness/target scans across many arms | scatter when committing a whole arm |
| AoSoA | bounded chunks: `Chunk<N>{kind[N], target[N], flags[N], ...}` | local, bounded scans; CPU vectorization where safe | tail handling, per-chunk metadata |
| SoAoS | separate typed arrays of compact structs (e.g. `ReadArm[]`, `WriteArm[]`, `FutureArm[]`) plus logical-index map | heterogeneous operations and operation-specific hot fields | indirection to preserve lexical ordering |

**SoAoS** here explicitly means *structure of arrays of small structs / type-grouped
arrays of compact arm records*. The abbreviation is less standardized than
AoS/SoA/AoSoA; this document defines precisely what Oreslang would implement.
AoSoA may itself use SoAoS-like typed fields within a bounded chunk.

Layout selection is an internal choice first, not a source-language
promise. No fixed arm-count thresholds or SIMD performance claims until
benchmarks establish them. Tentative policy: simple AoS for small plans,
SoA for field-major scans, AoSoA for dense large probes, SoAoS for mixed
READ/WRITE/FUTURE groups; `auto` can choose **at construction/publication**.
No continuous speculative reshuffling on every selection.

A plan snapshot stores **only one authoritative physical layout**. Explicit
migration publishes a new snapshot after copying/relocating into the new
representation; do not synchronously maintain AoS+SoA+AoSoA mirrors. Keep the
old snapshot pinned for in-flight operations. Migration is not `select`
arbitration and cannot commit I/O.

## Logical ABI: operation order survives physical reordering

Regardless of layout, preserve:

- Stable source logical arm index `ordinal` and key for each snapshot.
  `SelectResult.index`, keyed results and PRIORITY use **logical**, never
  physical/compaction order.
- One insertion-order definition, including after dynamic `put`,
  replacement and `remove`; replacement preserves ordinal when possible.
  A new snapshot may renumber **its own** logical indices, not old results.
- FAIR rotates by logical candidate index at its specified ticket/admission
  point, not by chunk, storage group, or iteration order. Source-site tickets
  and mutable-plan FAIR generations remain distinct.
- RANDOM shuffles candidates fairly regardless of physical group; no
  operation class receives extra weight because of layout or chunk width.
- Default is fallback, not a prematurely selected event; it consumes no
  operation and cannot rotate FAIR credit.
- Readiness is only an observation. It cannot itself dequeue buffered values,
  commit writes, finalize Futures, or call guest continuations. The existing
  atomic admission/commit protocol remains the single authority for winners.
- Duplicate Future identities must get one consistent readiness observation
  per arbitration pass, even if their arms reside in separate type groups.
- Cancellation detaches all loser registrations exactly once; losing producer
  Futures are not canceled or consumed; channel close and rendezvous preserve
  the existing observable contract.

The conceptual internal ABI is an **immutable** `PlanView` with methods
`count()`, `logicalArmAt(ordinal)`, `operationAt(ordinal)`,
`targetAt(ordinal)`, `payloadAt(ordinal)`, and a bounded readiness scan
that returns a *candidate logical index*, never an authoritative winner.
The compiler and runtime must retain the ability to enumerate arms in
lexical order. Do not expose `Arm*` pointers or JVM object layout as a public ABI.

## ECS-like allocation, but no mandatory ECS dependency

For large numbers of concurrent *selection executions* (not just arm count
within one plan), use an actor/task-domain-local slab or chunk arena. A
plausible internal table is:

```text
ExecutionSlab (per owner/trust domain)
  executionId[]       // {slot, generation}, no UUID required
  snapshotRef[]       // version-pinned immutable PlanView
  state[]             // pending / committed / canceled / failed
  selectedLogical[]   // sentinel while pending
  policy[]            // FAIR / PRIORITY / RANDOM
  admissionCredits[]
  waiterRange[]       // start+length in a bounded registration arena

WaiterChunks (AoSoA or SoA; bounded, with free lists)
  executionSlot[] / executionGeneration[]
  logicalArm[]
  targetCapabilityId[] / targetGeneration[]
  waiterKind[]
  status[] / attachmentGeneration[]
```

This is **not yet the Java implementation**. Current Java uses
`LinkedHashMap` + immutable `SelectSet` snapshots and allocates
`CaseRegistration` objects and `CaseRegistration[]` per selection.
The `ACTIVE` registry is a global linked set guarded by one coordinator.
AoSoA alone will not remove that coordinator's contention; sharding is a
separate transaction/protocol change that must preserve atomic rendezvous.

Use generation-checked slot handles rather than bare reusable slot numbers.
Generation wrap is fail-closed (retire the slot rather than accepting ABA).
No pointers or mutable capabilities may cross actor or isolate trust domains.
A shared immutable description is eligible for zero-copy sharing **only**
inside a separately authorized allocation/trust domain. One actor cannot
gain another's raw channel or Future just by sharing a PlanView.

## Mutation and snapshot publication

- `DynamicSelectPlan.put/remove` constructs a new logical version. The
  published view is immutable. Existing pending registrations retain the
  original view/version/key/index, and completion publishes the old result.
- Preserve stable key/index lookups without repeatedly materializing
  `LinkedHashMap.values()` on every selection; rebuild on mutation or
  controlled compaction, not in a `for`-loop hot path.
- Avoid O(arms) copy on *every selection*. An O(arms) snapshot rebuild on
  mutation is initially acceptable under the 256-arm admission cap, provided
  measured mutation churn stays bounded and old views are reclaimed safely.
- Bounded deferred retirement: snapshots referenced by pending operations
  cannot be reclaimed, and adversarial churn must not retain an unlimited
  number of old versions. Apply owner-scoped memory quotas/backpressure
  before admission; never silently drop or mutate an in-flight operation.
- Layout migration is a structural change: versioned publication with no
  observation of half-initialized columns, and no leaked old physical slots.
  Borrowed column/row views pin one generation and block unsafe relocation.
- Hard maximums apply to **logical arms**, **pending selections**, **pending
  arm registrations**, **memory**, and **retained plan generations**.
  Quota failures must be atomic, refund all credits, and preserve the
  originals of losing sources.

## Hot/cold split and future optimization gates

Cold: source-location metadata, names, debug strings, AST references,
full target capabilities, typed payload backing, diagnostics.
Hot: arm kind, logical index, capability ID/generation, readiness summary,
registration/execution generation, quota state, fairness ticket.

For Java, reference-bearing arrays remain Java references. A Java SoA
prototype is **not** proof of contiguous native POD columns, zero GC, or
SIMD. For LLVM/native, require explicit typed POD layouts/alignments and
separate root tables for references, pinned payloads and nullable fields.
The Java and LLVM logical ABI must match without relying on host struct
packing or hidden reflection.

Future strategy must not make the compiler's static loop hoisting dependent
on a particular physical representation. `SelectPlan` caches descriptor
storage; `DynamicSelectPlan` updates versioned descriptors; neither caches
armed registrations or payload-evaluation side effects.

## Measurable admission gates

1. **Semantic equivalence:** randomized/model-checked AoS/SoA/AoSoA/SoAoS
   with byte-identical logical winners/results for FAIR, PRIORITY, RANDOM,
   duplicates, default, buffer/rendezvous, Future/Channel mixtures, close,
   cancellation, races, admission failure and keyed mutation.
2. **Allocation discipline:** instrument per-select case-object
   construction, descriptor snapshots, waiter allocations and retirement.
   Stable plans in loops must not reconstruct arm descriptors. New layout
   should reduce allocations/bytes, not just relabel JVM object arrays.
3. **Correctness under churn:** mutation during pending waits, old generation
   completion after removal/replace, reentrant callbacks, actor restart,
   stale slot reuse and cancellation racing close/commit.
4. **Benchmark matrix:** arm counts `1,2,4,8,16,32,64,128,256`;
   ready sets `none/one/all`; mixes `READ/WRITE/FUTURE`; sparse and
   dense readiness; low/high mutation churn; pending selections from
   `1` to admission limit. Record operations/s, p50/p99 latency,
   allocations/op, retained bytes, fairness distribution and CPU cost.
5. **Parity:** exact-head Java/GraalVM and LLVM/native compilations; native
   ASan/UBSan and race/interleaving fixtures. Keep native paths fail-closed
   until ownership/borrow, snapshot retirement and concurrency behavior pass.
6. **Profile safety:** `core` remains freestanding/no mandatory heap.
   Allocation-dependent table layouts belong in optional alloc/runtime
   layers. Hard-real-time profiles require preallocated bounded stores,
   explicit admission budgets and no hidden compaction in the hot path.

This is a **physical optimization**, not a new selection model. Correctness
and predictable cancellation outrank SIMD and throughput wins.
