# Generation-bound supervisor shutdown: formal refinement phase 2

Status: **draft and unverified at exact head**. This patch stacks on #1950,
#1946, #1924 and #1911. It does not enable guest Oreslang `define supervisor`
or certify FFI memory reclamation.

## Runtime event schema

The source-level trusted-host reducer now accepts immutable generation-aware
events:

- `ShutdownWithChildEpochs(parentEpoch, Map<childId, childEpoch>)`
- `ChildFinishedWithEpoch(parentEpoch, childId, childEpoch, failed)`
- `ChildReclaimedWithEpoch(parentEpoch, childId, childEpoch)`

These *supplement* legacy events for existing callers. Once a shutdown pins
nonempty child epochs, untagged child completion and release receipts are
**rejected**, even if the stable child ID is correct. A repeated shutdown
must match both IDs and generations; stale parent epochs remain inert,
future epochs are rejected. The new events are used by the production
`SupervisorShutdownTree` coordinator. No actor callback is trusted to
mint an arbitrary `RECLAIMED` certificate.

The parent pins its direct children to exact epochs at the shutdown freeze.
The callback carries that captured child epoch, and before recording a
`ChildFinishedWithEpoch` transition the parent verifies the child's
*current* epoch, its logically STOPPED phase, and its success/failure
outcome. A remotely authenticated incarnation token and durable restart
journal remain necessary before this can support cross-process reparenting
or supervisor hot reload.

## Two-phase freeze linearization

The original parent could publish `BEFORE_HOOK` before a deeper descendant
finished admission fencing. The coordinator now first runs
`sealAdmissionSubtree` for **all** managed groups, collecting worker snapshots,
then calls `publishSealedSubtree` to publish the frozen epoch records under
the tree registration/shutdown lock. A failure during fencing skips phase
publication entirely, enters the existing emergency cancellation path, and
does not invent child termination or physical release receipts.

This guarantees that no guest hook or externally visible coherent *tree
snapshot* is published until the full tree admission fence has returned.
It does **not** imply the underlying group locks were acquired in one
atomic multi-group transaction. Tests must still inject partial-fence,
racing-spawn, and distributed restart failures.

## Independent formal model

`formal/supervision/SupervisorEpochReceipts.tla` is a separate finite
TLA+ specification of:

- `Running -> Fencing` before any shutdown is published;
- a restart reserved before the fence and either committed before fencing
  (advances epoch) or aborted on fencing;
- immutable pinned child epoch when shutdown becomes visible;
- only generation-matching logical child STOPPED receipts can advance parent;
- arbitrary stale/early receipts are rejected without changing lifecycle;
- watchdog expiration and CONTROL loss are orthogonal sticky observations,
  never successful terminal-state receipts.

TLC configs `SupervisorEpochReceipts.cfg` and
`SupervisorEpochReceipts3.cfg` bound the child to two or three possible
epochs, including two successive child restarts, and check type, no early
child acknowledgment, no premature parent STOPPED, published-generation
identity, and no post-fence restart.

Existing sibling/depth-three `SupervisorTree.tla` configurations remain
complementary: the epoch model is deliberately small and is not an
unbounded inductive proof of all supervisor trees.

## Trace and adversarial tests

`SupervisorLifecycleTrace.verifyComplete` now recognizes tagged shutdown
snapshots, checks child generation and state at both logical and physical
parent receipts, and rejects duplicate-parent/cyclic topology. The exact
production reducer oracle includes a child restart into epoch 2, a parent
pinned to epoch 2, a successful callback from epoch 2, stale epoch-1
rejection, and legacy downgrade rejection.

`SupervisorLifecycleFsmIntegrationTest` also asserts that real runtime
CONTROL trace events for the **entire tree** show initial epoch snapshots
before any on-shutdown hook transition.

## Verification/merge gates

- Run actual Java 21 compilation and both reducer oracles and JDK 25
  ActorRuntime JUnit at the **immutable exact branch commit**.
- Run TLC on the sibling, depth-three and new generation/partial-fence
  models and publish enumerated states, no-error status, tool SHA, and logs.
- Establish trace-to-TLA refinement for **all** emitted events; in
  particular, real partial-fence failures, restart commit races and
  `CoordinatorFailed` should have a formally checked projection.
- Prove authenticated logical child-incarnation identity and physical
  arena/loan/continuation receipts before allowing `RECLAIMED`.
- Integrate `OresVM` shutdown order, guest source hooks, and verify
  Java/GraalVM versus LLVM memory-safety and lifecycle parity.
- **No cross-organization private code mirroring** unless security issue
  #962 is independently approved. Source `main` still lacks verified
  protected-branch policy and the private destination allowlist is empty.

The GitHub source Actions zero-step allocation failures are not code-test
outcomes. Do not merge this draft solely from mergeability or passing
earlier/smaller finite model runs.
