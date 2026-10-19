# Gate A — Preallocated Task ownership with lock-free scheduler admission

Status: **draft** · 2026-10-19. This integration starts at guest scalar Task #1484 commit `208bc9805db1647aa1e90b38ca448a8abd64553e` and retains its newer ownership-slot/OOME fail-closed architecture.

## Linearization and cancellation

1. Under shared `treeAdmissionGate` then `scope.gate`, pre-register a Task ownership shell with its independent physical-quiescence fence before executable admission. Upstream `OwnershipSlot` and `OresTask` prepare-before-submit invariants are preserved.
2. Host `AsyncRuntime.prepareTask` allocates the exit witness and Task shell before scope admission. `PreparedTask.start` dispatches outside BOTH scope/tree monitors and binds the completion Future by reference.
3. Compiler `OresScheduler.start` likewise executes outside BOTH monitors with a pre-registered `OwnershipSlot` and independent code-generation lease. A failed scheduler registration releases the pin outside monitors before settling the shell's physical fence; failed release remains an explicit fence failure.
4. Atomic subtree cancellation may observe the unbound shell; `OresTask.cancelDeferredNotification` returns no notification until its completion is attached. The admitted producer rechecks the **volatile** cancelled bit and replays cancellation after binding, outside locks. A cancelled scope is also denied at the entry to a host or guest continuation turn.
5. `close` snapshots owned shells even when not yet bound, and waits on their preallocated physical fences. It must never equate a cancelled result with physical resource exit. Failures in owner-set cleanup are suppressed on the originating admission failure; a failed pin retirement is not successful cleanup.
6. Preserve upstream two-phase cancellation preflight, atomic tree-wide admission seal, and coherent `physicallyQuiescent()` read. Revoke all Task producers before running potentially blocking/throwing external token detachment, and defer Future subscriber notifications until all siblings have been revoked.

## Integrated security checks

- Compiler-resolved scalar Task capture requires owned value and `ThreadSafetyTraits.SEND`; no guest nominal callback can bless runtime capabilities.
- `Primitive.NULL` and `Primitive.VOID` do not inherit Send/Sync, including within aggregate or borrowed types.
- Readonly structural record views cannot acquire Sync absent a compiler-proven physically frozen backing value.
- Keep upstream `OresTaskScopePreAdmissionOwnershipTest`, `OresTaskScopeFailedSlotCleanupTest`, `OresTaskScopeCancellationOrderingTest`, and `OresTaskScopeCoherentQuiescenceTest`.
- Include 64 host submit-vs-close trials, 64 compiler frame-vs-close trials, fail-injected cancellation detachment and code-pin release, and 10,000 host-only prebound shell cancellation/bind checks.

## Required source-local exact-head execution

```sh
mvn -B -Dores.runtime.carriers=java -DskipTests test-compile
mvn -B -Dores.runtime.carriers=java \
  -Dsurefire.failIfNoSpecifiedTests=true \
  -Dtest=OresTaskScopeTest,OresTaskScopeFrameTest,OresTaskScopePreparedOwnerTest,OresTaskScopePreAdmissionOwnershipTest,OresTaskScopeFailedSlotCleanupTest,OresTaskScopeCancellationOrderingTest,OresTaskScopeCoherentQuiescenceTest,TaskTraitAdmissionHardeningTest,GuestScalarTaskAwaitTest,TaskAccessVerifierTest,ScopedTaskTypeBridgeTest \
  test
```

After this, execute the full Maven suite with GraalVM JDK25, real positive/negative `.ores` guest programs, agent-affinity and physical-shutdown concurrency tests, and any native-image gates. **The tests above are committed but are not claimed executed.** The source org's Actions runs have repeatedly failed without executing steps, so the cause is not inferred to be exhausted minutes or faulty Java.

## Remaining limitations / release blockers

- `AsyncRuntime` remains a compatibility virtual-thread carrier, not a fully compiler-proven guest M:N Task backend.
- Upstream source Task/region/actor lifetime proofs and LLVM continuation/borrow parity remain separate gates. Physical quiescence is a logical owner fence, not proof of independently isolated OS address space.
- Exact-head source-native CI, CODEOWNER review and branch protection are required before merging; the reviewed source mirror authorization ledger must remain private and fail-closed.
- A funded alternate organization does not authorize transferring the private Git tree. See [Java issue #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962) and [CI issue #963](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/963).
