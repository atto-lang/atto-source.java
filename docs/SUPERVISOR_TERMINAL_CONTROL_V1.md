# Supervisor terminal CONTROL v1

Status: Java/Graal runtime foundation on the actor reliability branch. This is not yet a guest Oreslang supervisor behavior or LLVM parity claim.

## Contract

A separate bounded supervisor mailbox is still lossy if it can be full exactly when another child terminates. Oreslang uses a stronger rule: terminal failure capacity is prepaid before a managed actor incarnation is admitted.

For every ActorGroup-managed worker:

1. Reserve one SupervisorTerminalLedger slot.
2. While holding the actor lifecycle lock, acquire group lifecycle ownership.
3. Only then admit the worker as managed.
4. Completion converts RESERVED to READY.
5. The oldest READY receipt remains replayable until explicit acknowledgement.
6. Acknowledgement returns exactly one slot to the admission pool.

If all receipt slots are in use, child or restart admission fails synchronously. User-mail capacity is never borrowed and terminal events are not silently dropped.

## Receipt surface

Receipts contain ActorId, actor incarnation, and one bounded terminal kind: GRACEFUL, CANCELLED, STARTUP_FAILED, FAILURE, or QUOTA_EXCEEDED. They contain no guest message, exception message, stack trace, actor state, carrier thread, pointer, reply capability, or serialized CONTROL authority.

There is no global receipt sequence required for correctness. Publication order comes from the fixed ready ring; stale authority is fenced by slot epoch plus actor incarnation. A previously prepaid child cannot lose publication because a diagnostic sequence counter exhausted.

## Replay and policy failure

peekTerminalReceipt is non-consuming. Repeated reads return the same oldest receipt until acknowledgeTerminalReceipt commits processing. A policy crash before acknowledgement therefore leaves the same receipt available to replacement policy.

The ledger runs no guest callback and owns no thread. Guest actors cannot inspect or acknowledge this host CONTROL surface. An internal ledger invariant failure marks the group CONTROL path unhealthy, records the fixed numeric supervisor.control.failed event, and blocks new managed-worker admission.

## Lock order

Management and transfer use actor lifecycle -> ActorGroup authority -> terminal ledger. Actor finalization already enters ActorGroup cleanup while holding actor lifecycle, so no reverse order is introduced. Group shutdown snapshots ownership under group authority and invokes cancellation only after releasing it.

## Backpressure semantics

After a worker terminates it no longer counts as live, but its terminal receipt credit remains consumed until acknowledgement. If supervisor policy stops draining failures, new managed-child and restart admission eventually stops. This is bounded backpressure, not a growing dead-letter queue.

## Current gates

The branch includes ledger saturation/replay tests, stale/foreign capability tests, ordered exactly-once acknowledgement, a 64-way reservation race against capacity 8, ActorGroup failure/startup/cancel/transfer integration tests, actor-access denial, and a source-free finite model.

Full Java execution remains blocked while source-org Actions jobs terminate with zero recorded steps. Private CI destination approval is tracked separately in PR #1224; no cross-repository source export is allowed before protected review approval.
