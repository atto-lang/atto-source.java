# Supervisor FSM receipt consistency / CI security audit

Date: 2026-10-22 (EST). Draft stack: #1911 -> #1924 -> #1946.

## Findings and code changes

1. **Contradictory duplicate acknowledgments (safety)**: the original pure
   reducer silently returned the previous state if it already received an
   on-shutdown hook, on-stopped hook, actor-group, or child-finished receipt.
   A same-epoch contradictory outcome (success then failure, or failure then
   success) could therefore be ignored. This revision records the original
   per-phase outcome and `failedChildren` set. Identical receipts are inert;
   conflicting outcomes throw `IllegalStateException` without modifying
   the accepted state. The CONTROL layer must treat that as a fatal protocol
   violation rather than acknowledging a clean shutdown. The sticky aggregate
   `failed` state cannot be cleared by any late acknowledgment.
2. **Frozen-child topology conflict (safety)**: a second same-epoch
   `ShutdownRequested` with different child IDs was silently accepted as
   idempotent. It now rejects the mismatch. Registration and shutdown fences
   must share the same authoritative child-incarnation snapshot.
3. **Watchdog scheduling failure (observability)**: a rejected shared timer
   could fail the deadline-bounded observer without marking the supervisor
   lifecycle as unhealthy. For a still-pending shutdown, the coordinator
   now publishes sticky infrastructure failure before notifying callers
   and requesting escalation.
4. **Success/failure disagreement (safety)**: the shutdown completion path
   previously could complete `terminated()` successfully even though the FSM
   retained an infrastructure failure. This revision does not return clean
   termination in that case; a previously recorded failure is included in
   the unsuccessful aggregate.
5. **Redundant timer after termination (race)**: the deadline watcher may be
   unavailable even though an idempotent shutdown has *already finished*.
   In that case, return the recorded actual outcome and do not poison
   a completed successful supervisor. A racing completion can still elect a
   conservative failure, but can never forge physical reclamation.

## Tests / verification gates

- The dependency-free `SupervisorLifecycleFsmOracle` now explicitly tests:
  identical duplicate receipt idempotence, contradictory duplicate hook/
  child/actor/post outcomes, conflicting frozen child sets, and the existing
  epoch/physical reclamation guards. The exhaustive BFS event alphabet now
  includes successful AND failed acknowledgments for both modeled children.
- `SupervisorLifecycleFsmIntegrationTest` covers rejected watchdog scheduling
  while an asynchronous prehook is still running; it asserts failure status
  publication before caller notification, no premature termination, and
  eventual failed (not successful) actual observer settlement. A separate case
  tests a rejected *redundant* deadline on an already-completed supervisor.
  The existing close-initiation fallback test now rejects logically
  contradictory successful completion after a sticky infrastructure failure.
- Static review of current source: `State` record has 16 constructor
  parameters; both `new State` calls have 16 arguments.
- A separate **reference-model** exploration, NOT a compilation of the exact
  Java PR source, enumerated 2,768 reachable states and 8,139 enabled edges,
  rejecting 10,768 contradictory receipt attempts, with no reference-model
  invariant failures. Do not represent this as production JDK21/JDK25 test
  evidence or TLC checking of the new protocol.
- The baseline finite TLA+ sibling model previously passed TLC in run
  39823433651, but does NOT cover these newly differentiated outcome receipts.
  The updated PR must be rechecked on its exact commit and config hashes.

## Private Actions policy (mandatory)

`main` private-CI allowlist is **empty** and GitHub reports `protected=false`
for the source `main`. Issue #962 requires independently reviewed source
provenance, immutable destination numeric identity, verified protected-main
policy, and an exact destination tree attestation. **No new private source
transfer to another GitHub organization is authorized** solely to obtain
Actions minutes. Previously mirrored blobs do not grant fresh transfer
approval; do not reuse them as authorization. Source-repo Actions run
39904037525 failed with zero executed job steps on exact PR #1946 head
`1eb963e41ed359847bf598c34083647e7663535f` before the additional
redundant-watchdog patch; check fresh exact-head runs separately.

## Outstanding proof obligations

- Execute the exact-head Java 21 BFS and JDK25 focused/full Maven suites,
  including both sibling/depth-three TLC configurations, on an authorized
  runner. Verify test SHA/tree provenance and actual executed job steps.
- Extend the *independent* formal model with explicitly differing duplicate
  receipt outcomes and partial-shutdown fencing; prove trace refinement of
  Java CONTROL transition stream into formal actions.
- Introduce authenticated child-incarnation + generation/epoch token binding
  and durable at-most-once effect admission before enabling hot reload.
- Integrate physical loan/arena/continuation release receipts before emitting
  `PhysicalRelease` or `ChildReclaimed`. STOPPED still does not imply
  RECLAIMED. Verify true runtime close, source supervisor hooks, transport
  drain, JDK25/AOT/hybrid, LLVM sanitizers and backend semantic parity.

**Release status: draft, not merge-approved.**
