# Oreslang supervisor HEFSM: deterministic control, generation fencing, formal proof gates

Status: **first Java reducer + shutdown-tree observation integration implemented
in stacked draft PR; NOT a whole-runtime formal proof and NOT source-level
`define supervisor` execution.** Keep release gates explicit.

## Decision: hierarchical, extended, deterministic FSM per supervisor

Six approaches considered: (1) enum/switch, (2) table-driven FSM,
(3) UML/SCXML hierarchical statecharts, (4) an Erlang `gen_statem` actor per
supervisor, (5) compile-time typestate, and (6) Petri nets / state-transition
systems. The runtime uses a **pure table-like reducer** for each node and
**hierarchical composition** through child acknowledgments. TLA+ models the
cross-node events independently; JVM bounded-state exploration checks the
reducer. Petri-net resource-place constraints inform the reclamation guards.
Compile-time typestate remains useful for CONTROL API authorization, not as
a replacement for asynchronous runtime lifecycle state.

This avoids one enormous `product(states_of_every_actor)` FSM. Each
supervisor owns an immutable `SupervisorLifecycleFsm.State` snapshot;
one coordinator/CONTROL lane performs transitions. `OresVM` owns CONTROL,
not a thread pool per supervisor. Queries read snapshots without executing
hooks or blocking a carrier. The reducer is side-effect free.

## Per-node phase

```
RUNNING --> RESTARTING --(old incarnation fully retired)--> RUNNING(new epoch)
    |           |
    +-----------+--(global admission fence)--> BEFORE_HOOK
                                                   |
                                         pre-hook settles
                                                   v
                                               WAIT_CHILDREN
                                                   |
                                       every frozen child settles
                                                   v
                                                WAIT_ACTORS
                                                   |
                                        owned actor DONE barrier
                                                   v
                                                AFTER_HOOK
                                                   |
                                         post-hook settles
                                                   v
                                                 STOPPED
                                                   |
                             local physical receipt AND every child's
                                     physical-release receipt
                                                   v
                                                RECLAIMED
```

`STOPPED` **only** means that pre/post hooks and child/actor logical
barriers settled; `RECLAIMED` means externally verified physical
arena/continuation/FFI-loan reclamation for the entire subtree.
A timeout or CONTROL failure is an **orthogonal sticky condition**, not an
edge that shortcuts to either terminal phase. A failed hook may lead to
`STOPPED` with `failed=true`: the failure remains observable.

The runtime's `SupervisorShutdownTree.status()` returns immutable
`TreeStatus` with each node ID, parent, name, epoch, revision, phase,
completed child counts, and fault flags. Derived tree state includes
all-logically-stopped, all-physically-reclaimed, any-failure,
any-deadline-overrun. No temporary global Cartesian state is stored.

## Transition protocol and performance

1. Snapshot immutable supervisor parentage and **incarnation** identities
   when CONTROL fences the tree. Child IDs must never be recycled within
   an outstanding shutdown; once supervisor hot restart is wired, receipts
   must carry both supervisor ID and child incarnation/epoch token.
2. Atomically check `expectedEpoch`; reject any future epoch; ignore stale
   past-epoch receipts. A duplicate receipt is inert, with no extra hook
   invocation and no second `revision` increment.
3. Guards check the current phase, frozen child set, actor DONE receipt,
   and physical-loan receipt. Invalid ordering fails closed.
4. Publish a new immutable snapshot through an atomic transition;
   **only after commit** enqueue an effect on the existing CONTROL lane.
   Effects are identified by `(supervisorId, epoch, phase, revision)` for
   deduplicated at-most-once admission. Exactly-once external execution
   requires idempotent transactional recipient-side handling and is NOT
   guaranteed by the Java reducer itself.
5. Hot path is an O(1) phase/epoch branch plus O(1) expected-child membership;
   child-set copies cost O(number of completed siblings). When integrated
   with dense arena-backed supervisor columns, use bitsets/counts plus unique
   generation receipts to approach amortized O(1) updates; guard reuse and
   avoid stale-slot ABA. Do not store three synchronized AoS/SoA/AoSoA copies.
6. No guest callbacks, waiting, user `defer/recover`, or new schedulers run
   in the reducer. A **runtime-owned** cancellation/lock release path must
   execute even if guest hooks throw/raise/panic or are cancelled.

Shutdown suppresses all ordinary restart modes (`one_for_one`,
`rest_for_one`, `one_for_all`) once the tree fence is committed.
Restart intensity and period are policy inputs to the transition decision,
not mutations made by child callbacks.

## Formal model and properties

`formal/supervision/SupervisorTree.tla` is an independently written,
finite-state **three-node** hierarchy model. `SupervisorTree.cfg` provides
root + two siblings and two epochs. TLC should enumerate reachable states
and check:

- `TypeOK`: phases, epochs, and loan counts remain valid.
- `FenceSafety`, `NoRestartAfterFence`: no post-stop admission or restart.
- `NoPostBeforeChildren`, `ShutdownSafety`: descendants and actors
  settle before parent terminal status.
- `NoPrematureReclaim`: a node reclaims only after its own physical loan
  returns and all child subtrees are reclaimed.
- Generation guards: stale DONE/loan receipts cannot advance a new epoch.

Separate `SupervisorLifecycleFsmOracle` is a dependency-free Java
bounded explicit-state explorer for the **implementation reducer**, with
adversarial orders, duplicate and stale completions, early actor retirement,
restart races, deadlines, hook failures and physical release barriers.
It checks existential completion-path reachability **conditional on the
environment eventually delivering outstanding receipts**. Neither BFS nor
TLC automatically proves correctness of real JVM/LLVM execution.

This is *model checking for fixed finite instances*, not inductive proof
over arbitrary supervisor-tree sizes. For stronger guarantees, add
TLAPS inductive proofs for safety or Apalache/SMT checks plus a refinement
mapping from the Java transition trace into TLA+ actions. Liveness requires
explicit weak/strong fairness and assumptions about eventually terminating
actors/FFI; trusted noncooperative code may legitimately prevent it.

## Must-hardening release gates

- Verify TLC actually runs against exact `.tla`/config hashes; do not
  report it as green from Java BFS alone.
- Add a production physical-loan completion source and child incarnation
  receipts before EVER emitting `PhysicalRelease` or `ChildReclaimed`.
  The current Java shutdown tree deliberately stops at `STOPPED`.
- Integrate task cancellation, owner-indexed locks, automatic lock lease
  revocation, actor groups and supervisor restart generation journal.
- Establish trace conformance / linearization point between CONTROL reducer
  events and actors' actual events; fault-inject STOP vs RESTART, stale
  callback, duplicate DONE, failed hook, nested cancellation and hot reload.
- Verify finite models for root, root+child, root+siblings, depth 3, and
  at least two generations; add property-based differential traces with
  shrinking. Bound any runtime telemetry buffers and expose O(1) count
  snapshots to avoid a costly global tree traversal on every event.
- Run full Java/Graal native (hybrid and AOT), LLVM/ASan/UBSan, JS/.NET/WASM
  parity, plus cluster restarts and crash/recovery tests before merging.

**Never equate a clock timeout, an empty managed-worker map, a cancelled
future, or an actor's logical DONE with physical quiescence.**
