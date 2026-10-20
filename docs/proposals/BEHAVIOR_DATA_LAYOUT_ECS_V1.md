# Data-oriented behavioral models: Statecharts + Petri nets + ECS (v1)

Status: **DRAFT design / bounded host reference only**, 2026-10-19. No new supported guest syntax, no canonical `core/*` implementation and no LLVM/Graal parity are claimed by this PR.

## Relationship to existing work

- **#1522** specifies Symbol-keyed Statechart and Petri-net semantics. This PR adds a **separate physical storage and scheduling contract**; it does not copy the earlier reference checker or assume that #1522 is merged.
- **#1423 / #1445** define or prototype inverted classes / SoA; **#1505** prototypes selectable residency; **#215** provides the opt-in ECS archetype frontend; **#1623** prototypes bounded compiler-issued provenance/conflict planning.
- The freestanding `core/alloc/actor/std` dependency DAG remains an architecture proposal (see `docs/CORE_ALLOC_STD_BOUNDARY.md`). Do not falsely publish host Java-backed records as bare-metal `core`.

## Userland-first placement

| Layer (proposed) | Responsibilities | Must not assume |
| --- | --- | --- |
| `core/behavior` | Pure typed Symbol identities, transition/arcs, small fixed-capacity state/marking steps, explicit bounds | heap, reflection, OS, actors, scheduler, global registry |
| `alloc/behavior` | Allocator-bounded compiled state/arc tables, AoS/SoA/AoSoA column stores, sparse Petri incidence, migration | ambient system allocation |
| `actor/behavior` | Optional mailbox adapter / gen_statem-like serial event commit, cancellation, supervision | an actor per state or per entity |
| `std/behavior` | timers, durable journal, diagnostics, serialization, visualization | a change in transition semantics |
| userland ECS | behavior-state component, transition systems, explicitly declared reads/writes and deferred structural barriers | that all state machines are ECS entities |

The same pure transition kernel MUST work with ordinary Oreslang code, `nb {}` Tasks, embedded loops and optional actors. ECS is an **optional orchestration/storage layer**, not the required runtime for a Statechart or Petri net.

## Semantic versus physical representation

### Two behaviors, one storage policy

1. **Statechart**: an immutable compiled transition graph plus a mutable cohort of active-state IDs. A leaf transition overrides ancestor lookup. Each event atomically selects one valid new active state. The initial supported model is **single active leaf**; parallel regions, history, guards and run-to-completion microsteps need separately defined semantics. Do not interpret it as a general concurrent Statechart engine yet.
2. **Petri net**: immutable sparse transition incidence with bounded nonnegative token markings; a transition consumes all input tokens and produces all output tokens atomically. Model nondeterministic enabled-transition choice as an abstract choice, not as an accidental array sort order. Structural P-invariants must hold across every firing. Inhibitor/read/reset arcs and colored tokens are future versions, not silently accepted.

`#symbol` identity is logical **(kind, normalized name)**, not pointer/heap identity or a publicly exposed integer. A compiled chart may assign dense private `uint32` ordinals to symbols for fast indexing. Ordinal mapping is immutable, versioned and never persisted as an authority token. Persistent/wire use requires an explicitly versioned, authorized mapping; `Symbol.stable(uuid)` is not interchangeable with `#foo`. Untrusted actors cannot import arbitrary userland atoms as `core` Symbols.

### Authoritative layout per store/epoch

| Layout | Representation | Prefer for | Tradeoffs |
| --- | --- | --- | --- |
| **AoS** | `[(entity_id, state_id, metadata), ...]` | small per-actor machines, localized whole-record reads, frequent modifications | less cache-efficient for batch state-only scans |
| **SoA** | `entity_id[]`, `active_state[]`, `markings[place][instance]` | thousands of homogeneous charts, vectorized event filtering, Petri place scans | gathers for cross-field access; potentially more allocations |
| **AoSoA** | `active_state[tile][lane]`, `markings[tile][place][lane]` | cache/SIMD-friendly batched stepping, bounded tile-local effects | padding, masking, tile-size and alignment choices |
| **ECS archetypes** | `BehaviorState`/optional `PetriMarking` columns within matching archetype chunks | many heterogeneous entities, query/filter by behavior and other components | archetype churn, sparse cohorts, structural barriers |

**Do not maintain three authoritative synchronized copies.** In the initial contract, a store owns **one** selected physical layout. A conversion allocates checked temporary target storage within the owning domain, validates every live row/marking and handle, swaps the authority once, increments a layout epoch and retires the former backing storage only after outstanding loan/reader rules permit. The transient conversion peak must be budgeted; a successful conversion is not proof of zero-copy. Keep layout selection independent of semantic model: Petri marking can be AoSoA while sparse incidence stays CSR, for example.

Store handles require generation and domain identity for real mutable ECS/region implementations. An epoch-checked index in the bounded reference model is **not a replacement** for those handles. Structural swap-removal repairs every moved index or leaves a generation-safe redirect; no stale handle silently aliases a new entity. Read/write field loans must be disjoint, nonescaping across suspension, and revocable when the owner stops or migration begins. `rt share` never turns a private mutable column into a globally writable object.

### Statechart layout design

- Immutable transition lookup: sparse `(source_ordinal,event_ordinal) -> target_ordinal` table, plus immutable parent ordinal; never per-row copied transition graphs.
- Runtime hot fields: active leaf ordinal, event sequence/version and actor/tenant-local ownership metadata. Cold debugger names/trace payloads are separate.
- Batch same-event cohorts by `event_ordinal`, but preserve per-entity event ordering. Reordering across a causally dependent event sequence is forbidden.
- Pure semantics first: compute next state and **effect descriptors**; commit authoritative state before executing side effects via a separately specified at-least-once/idempotence journal or transaction boundary. It is unsafe to promise exactly-once external effects merely because state changes were atomic.
- Future orthogonal regions use a checked set/bitset of active leaves, not one scalar. Statechart microstep closure must terminate or fail with a bounded error and a counterexample trace.

### Petri net layout design

- Use CSR/CSC-like immutable sparse arcs (`offsets[]`, `place_index[]`, `weight[]`); reject duplicate/negative/overflowing arcs and unknown places on compilation.
- Store mutable markings as `M[instance][place]` (AoS), `M[place][instance]` (SoA), or `M[tile][place][lane]` (AoSoA). Never count padding lanes as live tokens.
- Enable/fire using checked **read-all / validate / commit-all**. For parallel tasks require exclusive loans or versioned transactional CAS/retry; an unsynchronized split decrement and increment can duplicate or lose tokens.
- Conservation proof: for each invariant row `w`, verify `w·(Post_t - Pre_t)=0` over all transitions and assert `w·M` over reachable markings.
- Need fairness assumptions for liveness; deadlock freedom in a four-state toy graph cannot prove starvation freedom in deployed workloads.

### ECS and parallel Task integration

Opt-in components may look conceptually like `BehaviorState<Protocol>`, `PetriMarking<Net>`, `EventInbox`, `PendingEffects`, and `BehaviorCodeVersion`. These are **proposed API roles**, not currently supported Oreslang syntax.

- ECS systems declare exact read/write component sets and compiler-issued region/allocation provenance. `BehaviorStep`: read inbox/config/chart tables; write owned active-state, event cursor and effect descriptors. `PetriStep`: read arc tables, write owned marking. Independent systems run together only when proven component+region accesses are disjoint.
- Structural mutations (add/remove components, archetype migration, entity destroy) use deferred commands and a quiescent barrier. Do not run swap-delete during an in-flight batch that holds row or field loans.
- `BehaviorCodeVersion` changes require staged metadata schema migration, permission check, deterministic replay and rollback; generation-checked handles must not pass from old to new region without an explicit mapping.
- A small `gen_statem` actor owns a single logical behavior state; an ECS cohort may have tens of thousands of stateful entities **without** tens of thousands of actors.

## Host reference scope and how to test

`tools/formal/BehaviorLayoutModel.java` (Java 21 only) links the current **real OresSymbol class** and tests:
- identical Statechart step traces for AoS/SoA/AoSoA with layout migration, tile padding, logical symbol equality and stale migration-view rejection;
- a sparse-incidence, two-resource Petri model with checked atomic firing, conserved tokens, disabled firing and AoS/SoA/AoSoA-equivalent reachable markings;
- conservative ECS read/write conflict and unknown-provenance rejection.

```sh
bash scripts/check_behavior_layout_models.sh
```

This is a **host reference contract checker**. It does not run Oreslang guest source or exercise LLVM lowering, SIMD hardware, a real archetype world, allocator domain accounting, concurrent CAS, or a full transition/emission transaction.

## Formal/verification and performance gates to graduate this draft

1. Bounded explicit-state checks plus pinned TLA+/TLC model for atomic Petri firing, chart event-order preservation, handles, quiescent layout migrations and ECS structural barriers. Derive weak/strong fairness separately.
2. Cross-backend source vectors: real guest `#symbols`, chart steps, Petri sparse arcs, exact event trace, compiler admission and negative sandbox tests on both Java/Graal and LLVM. Compare **canonical symbolic outcomes**, not private dense ordinals.
3. Lifetime and borrow proofs: old tile pointers, cross-actor share, stale handles, cancellation during region migration and actor teardown must fail closed. Include fuzzing and adversarial quotas/overflow.
4. Benchmark suites by cohort size, event locality, transition sparsity, tile size, churn and mixed component signatures. Measure p50/p99, allocation volume, cache misses and bounded worst-case work. Choose AoS for small/local machines, SoA for column scans, AoSoA only when real vector/cache wins; do **not** assume ECS/SoA always beats AoS.
5. Merge/stack reconciliation with #1522, #1423, #1445, #1505, #215 and #1623. Reject repeated physical layout implementations or a new guest pseudo-core bypassing the already proposed library split.

**Merge status: Draft by design.** Do not equate proof of this host model with implementation-level source, safety, RT, or performance guarantees.
