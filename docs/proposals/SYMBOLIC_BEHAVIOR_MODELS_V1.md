# Symbol-keyed Statecharts and Petri nets — v1 proposal

Status: **design + bounded executable reference model only** (2026-10-19).
This is NOT a shipped guest Statechart/Petri-net engine, NOT a compiler feature, and NOT a GraalVM/LLVM parity claim.

## Decision

Adopt **hierarchical deterministic Statecharts** for reactive stateful behavior and **place/transition Petri nets** for concurrent resource/workflow behavior. Use existing language-level `Symbol` values (`#ready`, `#retry`) as nominal event/state/place identifiers. Neither approach requires actors: pure code, tasks, embedded applications, or actors may drive the same state transitions.

Keep the public engines in Oreslang userland. The initial implementation path follows [the proposed core/alloc/actor/std split](../CORE_ALLOC_STD_BOUNDARY.md):

| Planned layer | Allowed responsibilities | Not allowed |
| --- | --- | --- |
| `core/behavior` (future) | Pure Symbol-keyed transition descriptors; fixed-capacity typed marking and transition-step algebra; zero-allocation validation where bounded | Heap, threads, I/O, clocks, actors, registries, host reflection |
| `alloc/behavior` (future) | Allocator-provided immutable compiled rule indexes, dynamic charts/nets, bounded state-space exploration with caller-supplied arena/quota | Implicit system allocation or OS services |
| `actor/behavior` (future) | Actor/mailbox/cancellation adapter, serialized event admission, optional supervisor/gen_statem-inspired facade | A mandatory actor per state/node, actor locks across await |
| `std/behavior` (future) | Timers, tracing, persistence, visualization, optional diagnostic tools | Changes to pure transition semantics |
| `tools/formal` (this PR) | Dependency-light bounded reference-model checks using `OresSymbol` | Claiming runtime refinement from model checks alone |

The current repository still packages `stdlib/*.ores` under a legacy `std/*` facade. Do NOT add a fake `core/*` import or silently rebrand a hosted Java utility as freestanding core. Introduce real canonical `core/alloc/actor/std` namespaces only as part of the existing library boundary migration.

## Symbol contract

1. Symbol identity is **kind plus normalized logical name**, not pointer address, interning index, JVM identity, or array offset. Recreate equal `#ready` values across independent participants and compare by value.
2. `#foo` emits a PROCESS-kind Symbol. An arbitrary userland Symbol does **not** automatically become a public cross-tenant core atom or gain authority inside an untrusted actor. Honor current `OresSymbol.publicToUntrustedCoreV1()` restrictions; model definitions crossing a trust domain require explicit approved export/capability admission.
3. Persisted/distributed protocols need versioned, canonical, explicitly authorized Symbol IDs and schema upgrades. Current `Symbol.stable(uuid)` is a distinct kind: never pretend that a raw `#foo` survives arbitrary process restart as a globally unique identifier.
4. Reject unknown states/places, malformed arcs, duplicate transition keys, cyclic hierarchies, negative token counts, integer overflow, and prohibited untrusted symbol imports *before* running a model.
5. Symbol names are descriptions/identifiers, **not** permission checks. No arbitrary string-to-authority coercion.

## Statechart v0 semantic subset

A configuration is `(active_leaf, queued_event)`, together with a finite set of declared states, an acyclic parent forest, and transitions keyed by `(source_state, event)`.

- Each accepted event produces **one atomic macrostep**. Lookup starts at the active leaf and falls back to its ancestors. The nearest matching source wins.
- A duplicate key at the same source/event is an ambiguity **error** at construction, not unstable declaration-order behavior. The initial subset has no guards, entry/exit effects, orthogonal regions, or history yet.
- Unhandled events **stutter** (state unchanged); a future strict-mode API may instead surface `Err(#unhandled)`. The choice must be explicit on the interface.
- A named target must belong to the declared state set. Cyclic parent relations are rejected.
- The proposed engine should return a pure transition outcome `(next_state, ordered_effect_descriptors)`, not execute arbitrary effects during transition selection. Mailbox adapters will apply one state commit before dispatching effects, with idempotency keys and outcome logging for retries.
- Future guarded transitions must have pure, non-suspending, deterministic guards, checked overlap/priority, and compile-time or admission-time exhaustiveness modes. Composable hierarchy and priority should not degrade into an arbitrary first-match rule list.

Illustrative **future API sketch, not currently compilable source**:

    let chart = Statechart.builder(initial: #idle)
        .parent(#idle, #running)
        .parent(#busy, #running)
        .on(#idle, #start, #busy)
        .on(#running, #reset, #idle)
        .build_checked();
    let step = chart.step(current: #busy, event: #reset);
    // step.next == #idle; no actor or timer required

An actor-facing adapter should eventually provide `gen_statem`-like semantics under `actor/otp` or `std/otp`, not force `gen_statem` behavior into every actor.

## Petri net v0 semantic subset

A marking `M` is a vector of bounded nonnegative integer tokens, indexed by a unique finite set of Symbol places. A rule has an ID Symbol and nonnegative integer `Pre`/`Post` arc-weight vectors.

- `t` is enabled iff `M[p] >= Pre[t,p]` for **all** places.
- Firing is atomic: `M'[p] = M[p] - Pre[t,p] + Post[t,p]`. Disabled transitions do not consume tokens. Overflow rejects the firing.
- Multiple transitions may be enabled. The transition choice is explicitly nondeterministic at the abstract level; concurrency adapters may choose an order, but a linearizable atomic commit is required.
- A P-invariant vector `w` is admissible only if `w · (Post[t] - Pre[t]) == 0` for **every** transition. The checker then exhaustively confirms `w · M` for every reachable marking in its finite test graph.
- v0 excludes inhibitor/reset/read arcs, clocks, unbounded integer places, distributed atomic firing, and implicit dynamic allocations. Such features require separate semantics and proofs.
- Disjoint enabled transitions should commute. Contended transitions must not duplicate tokens. An actor is optional; user tasks may perform firing via a safe synchronized owner/region abstraction.

Illustrative **future API sketch, not currently compilable source**:

    let net = PetriNet.builder(places: arr[#free, #held])
        .transition(#acquire, consume: {#free: 1}, produce: {#held: 1})
        .transition(#release, consume: {#held: 1}, produce: {#free: 1})
        .invariant({#free: 1, #held: 1})
        .build_checked();
    // A pure fire operation returns a new marking or #disabled.

Do not conflate colored Petri net tokens with actor-owned pointers. A token may encode an opaque capability handle only if it preserves the owner/borrow/provenance rules and cannot forge a cross-domain grant.

## Formal claims and counterexamples

The first executable bounded model is `tools/formal/SymbolicBehaviorModelCheck.java`. It compiles against the actual `dev.oreslang.runtime.OresSymbol` without pulling in a host runtime. It checks:

- Statecharts: logical Symbol equality, ancestor fallback, event stutter, invalid hierarchy/duplicate transition admission, deterministic single-step results, declared-state closure, and all 78,120 transitions in all event traces up to length six for the fixture.
- Petri nets: two independently acquired/released one-token resources; four reachable markings, eight edges, both place invariants, atomic disabled transitions, absence of reachable deadlock in the fixture, and commuting disjoint actions.
- Reachability to an all-free marking is **existential**, not a fairness or eventual-progress guarantee. Bounded checks neither prove unbounded liveness nor absence of races in an actual compiled guest engine.

Counterexamples should fail closed and include the shortest discovered trace when the explorer is generalized. Suggested follow-on formal gates:

1. Add a pinned TLA+/TLC or Apalache specification for transition atomicity, hierarchical resolution, token nonduplication, cancellation, and fairness assumptions.
2. Add **guest Oreslang execution refinement**: run identical symbol-keyed inputs on GraalVM and LLVM; compare normalized `(state, marking, emitted_effects)` traces to the abstract model, including negative admission tests.
3. Verify interference/races between Tasks, ActorGroup, and shared-region/Arc ownership. Express conservation as a constraint over physical *storage* owners, not only numerical tokens.
4. Admit user-defined guards only when purity, termination bounds, borrow capture, and deterministic evaluation are mechanically enforced; test hot-reload schema migrations and safe rollback.
5. Track state-space explosion with explicit max states, max transitions, max depth, max tokens, and deterministic cancellation/resource quotas.

## Running the current executable model

Run from repository root with JDK 21+:

    bash scripts/check_symbolic_behavior_models.sh

CI must report the actual compile and execution step, the checked source commit, and the model summary. A passing standalone reference checker is **not** evidence that Java/LLVM backends already implement these APIs.

## Acceptance criteria before promoting from draft

- Agree on Symbol visibility and the core/alloc/actor boundary with the in-flight Symbol and native-library PRs.
- Land an Oreslang-owned pure transition implementation plus real guest parser/typechecker/runtime tests.
- Pin and run JVM + native LLVM shared conformance vectors and formal model refinement; no unvalidated CI-minute mirror transfers of private source.
- Add nested/parallel Statechart semantics only with an explicit microstep/priority specification and conflict/termination tests.
