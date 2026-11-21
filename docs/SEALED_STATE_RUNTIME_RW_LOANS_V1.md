# Java/Graal: sealed actor state wired to runtime-minted RW permits

**Status: executable integration candidate, unverified** (JDK25/GraalVM toolchain and source-org GitHub Actions not available for successful execution yet). This branch stacks on Java #2333, which stacks on the checked critical-state parser/typechecker/lexical runtime from #2245.

## Root cause resolved in source

Earlier `self.handle_state(critical[read]/critical[write])` opened a lexical `ActorStateLoanView` only. The independent `ActorStateRwLoans` gate (Java #2296/#2322) required `ExecutionContext.actorBound` with a generation-fenced `ExecutionHandle`, while the ordinary `OresScheduler.TaskRunner` context is a TASK allocation, not an ACTOR allocation. Using a guest-created `ExecutionContext` or repurposing the physical worker's thread identity would be unsafe.

This change **does not falsify the SourceTask's identity**. It creates a distinct scoped state-loan execution identity at the trusted ActorRuntime admission boundary:

- `ActorRuntime` owns unique positive state-runtime and per-loan execution ticket counters. The current `ActorCell.ref.generation()` is runtime minted and identifies its actor allocation incarnation.
- Only when `currentActorRuntime() == this`, `currentActor.get()` exists, this cell is a live `codeExecutor` actor, and `currentThreadOwnsActorExecutionLease()` is true can `acquireCurrentActorStatePermit(write)` mint `ExecutionContext.actorBound(runtimeDomain, ticket, slot=ticket, slotGeneration=actorGeneration, actorAllocationDomain, owner=actorGeneration, actorGeneration, ...)`. Neither guest strings nor a host/foreign producer can mint this authority.
- `ActorCell.stateRegion` is the same hardened `ActorStateRwLoans` implementation from the state-law draft #2322 (exact source blob; no new duplicate arbiter). It is owned by the actor incarnation and configured to **never queue**: zero `maxPending`, and any async result not already complete is canceled/rejected. Synchronous critical callbacks never park, while the ledger models admission and physical retirement.
- The guest's `ActorStateLoanView.open()` acquires this permit; each view access checks the original actor/turn segment plus `loan.heldBy(execution)`. Writes additionally require WRITE authority. In `finally`, `revoke()` closes the actual loan, not just the lexical view.
- On STOP, `ActorCell.terminateTree()` closes the region outside the lifecycle monitor. Actor finalization requires `stateRegion.physicallyRetired()` before owned memory can be reclaimed. The arbiter's once-only retirement callback wakes a stopped actor to retry finalization.
- Both successful grant subscribers and rejected waiting subscribers remain counted until physically drained, using the race fixes from #2322.

## Limits

The source actor remains single-carrier. A synchronous critical read cannot overlap a different actor turn; this is the safe current runtime, **not evidence of concurrent source-level readers**. Multi-carrier readers require a separately proven scheduler and source lowering. Real-time / zero-GC guarantees are *not* established; this correctness baseline allocates one Future and loan per admission.

Other state capabilities (`ActorStateLoans` legacy exclusive arbiter, structured task descendant loans, foreign buffers) must be included in the final actor arena quiescence proof. Do not infer that these changes settle Java issue #1566.

## Exact-head test gate

`java -version` must report GraalVM JDK25. Run:

```sh
mvn -B -ntp -DfailIfNoTests=true -Dtest=ActorCriticalStateModeTest,ActorCriticalModeConformanceTest,ActorSealedStateAuthorityGateTest,ActorStateRuntimePermitAuthorityTest,ActorStateRwLoansTest,ActorStateRwLoansCarrierMatrixTest,ActorStateRwRetirementPublishRaceTest test
```

`.github/workflows/critical-state-runtime-loans.yml` checks exact SHA, nine pinned source conformance fixtures, exact Git blobs, seven real Surefire suites with nonzero executed tests and zero errors/failures/skips. The direct guest test `ActorCriticalStateModeTest.criticalWriteThenCriticalReadUsesRealGuestStateLoan` and common source fixture `write_then_read` must pass to establish the bridge—not merely host-only mocked admission.

Do not transfer private source to an alternate org without independent review of source main protection, explicit destination allowlist and carrier controls. A zero-step workflow failure is admission failure, never code PASS or FAIL.

## Next verification

1. Run exact-head JDK25/GraalVM compiler+Truffle tests; review build warnings and Surefire XML.
2. Add real STOP/force-cancel during a live critical callback with a supervisor-controlled latch, assert memorySlice retirement only after the final permit.
3. Carry the same exact source fixtures and generation proof to LLVM/WASM/.NET NativeAOT/Forge, with runtime-specific execution and independent evidence.
