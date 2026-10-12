# Actor finalization receipt v1 (proposed observational contract)

The JSON Schema and TypeSpec files define the *shape* of a host-owned
actor-finalization receipt. An untrusted guest must not be able to forge this
record. Neither backend is claimed to emit this schema yet.

## Semantic obligations

1. **Identity**: each receipt is bound to one logical actor, physical
   incarnation, and stable unique receipt ID. Reused actor names cannot
   authorize a receipt for a different incarnation.
2. **Commit claim**: one finalization claim per incarnation. A claim must
   follow STOP admission, carrier/TurnExecutor exit, all source cursor and
   ordinary owned-Future callbacks, and all child physical DONE barriers.
3. **Post-lock delivery**: exit hooks, ready/DONE Future waiter notifications,
   and parent notifications must not run while holding actor lifecycle
   monitors or the channel COORDINATOR.
4. **Physical DONE**: is reported only after exit hook/resource cleanup and
   required callback barriers. Parent notification follows the child's
   completion callbacks, with no child->parent nested monitor edge.
5. **Exactly once**: repeat STOP, duplicate completion, and cancellation
   races must not publish a second physical claim, exit hook, or DONE receipt.
6. **Error handling**: host cleanup failures are attached to a failed receipt;
   a previous cancellation must not be silently reclassified as success.

JSON Schema validates structure, not causal/temporal constraints. Enforce
these obligations with the bounded model in
`FormalActorFinalizationOrderingModelCheckTest`, live monitor tests in
`ActorFinalizationNotificationLockOrderTest`, and eventual JVM/LLVM
backend-neutral trace comparison.

This is additive to the existing actor trace contract under
`contracts/v1/semantics/traces`. Do not use it to mark production parity
or release conformance before both runtimes emit authenticated receipts.
