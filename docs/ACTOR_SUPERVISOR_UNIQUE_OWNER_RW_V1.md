# AttoLang actor ↔ supervisor: unique ownership and RW access

**Status: Java runtime hardening candidate, NOT an implemented cross-actor mutable-sharing language feature.** This branch is stacked on Java #2335 and ports the independently developed RW alias/retirement fixes from Java #2363. The parent branch already has compiler/Truffle sealed actor-state read/write lowering; this branch does not change its syntax or grant supervisor state access.

## Security invariant

For every mutable allocation A at any instant:

1. There is **one semantic owner** of A, identified by compiler-derived allocation identity, its owner actor/Task or runtime region, and its generation. Multiple read borrowers are **not** owners. One exclusive write borrower is likewise not a second owner.
2. The actor's supervisor and children do **not** inherit read, write, memory-address, actor-turn, or actor-heap authority by ancestry. A supervisor can inspect immutable, explicitly published snapshots or send messages, but cannot directly borrow an actor's sealed state.
3. The ordinary route for parent/child state changes is a typed actor message. A **move** consumes the sender's owner, requires transitive Send, and switches ownership only after outstanding lexical/physical loans and readers/writers have drained; any stale sender view must fail. Sealed actor state cannot itself move, and actor-internal heap pointers must not cross.
4. Immutable deeply frozen Sync data may have multiple read leases, never a mutable alias. For **explicitly promoted mutable shared memory**, only a compiler-approved runtime-owned capability (e.g., future verified `Proxy<T>`), not a naked reference or a broad SHARED actor label, may cross actors. It must retain exactly one authoritative storage owner while borrowing access via a fair per-region RW gate.
5. Mutating that region requires an exclusive writer ticket; readers may coexist only with readers. The backing owner must also take the gate once it publishes a guarded capability. No raw-owner bypass, late-reader barging ahead of queued writers, reentrant acquisition, read→write upgrade, or alias escape through field/method/closure/FFI/serializer.
6. Tickets are scoped to the **logical execution identity, generation-fenced scheduler slot and actor incarnation**, not an OS carrier, actor name, or user-supplied ID. One logical execution cannot acquire a second loan by rematerializing a slot; one slot cannot serve two physical executions or generations simultaneously.
7. Lock admission may wait cooperatively *before* the critical region, subject to bounded queue/fairness. No blocking carrier, await/select/yield/`rt cooperate`, guest callback escape, or arbitrary foreign call while holding a non-suspendable loan. Source-level borrow checking must reject any path, including exception/finally and hidden method effects, where the lease could outlive its permitted scope.
8. Cancellation/STOP seals new access and immediately revokes logical authority. **Physical retirement is separate**: do not reclaim/reassign actor memory or return a successful cleanup receipt until active loans and queued Future terminal observers have drained. If cancellation revocation fails, quarantine the allocation and fail pending/new admission instead of falsely reclaiming it.
9. If the owner stops while a separately promoted shared capability remains live, the runtime must retain the shared region independently; it must not smuggle a pointer to a reclaimed actor-private arena. Close/revocation and generation rollover must fence old handles before successor publication.
10. Private/untrusted actors and cross-tenant placements may not exchange in-process shared-memory handles by default; use typed message copy/transfer across isolation boundaries.

## Actual implementation and evidence boundaries

- **Actor sealed state:** Java #2335 already binds `ActorStateRwLoans` to a single actor allocation incarnation and mints source state permits only in an authenticated actor turn. Supervisor callbacks and ordinary host actors are denied. This branch preserves that rule and adds `ActorSupervisorUniqueOwnerRwTest`.
- **RW ledger hardening:** Port Java #2363's exact runtime/test Git blobs into #2335. The ledger now separately fences full execution IDs, handle IDs and raw slots; retains pending-Future notification pins across cancellation/close, retries the physical retirement wakeup after the publisher ends and quarantines failed cancellation revokers.
- **Stateful fairness:** the existing RW gate admits reader cohorts and a single writer with FIFO writer-barrier semantics. This is a runtime correctness baseline, not proof of simultaneously executing Oreslang actor guest handlers or true hard-RT/zero-GC behavior.
- **Cross-actor mutable sharing:** **NOT IMPLEMENTED by this branch**. Java `rt proxy` draft #399, ownership checker, source HIR/MIR, and independent native/Wasm/.NET/Forge lowering require reconciliation. Do not reinterpret the sealed-state gate as permission for a supervisor to hold a child raw pointer.

## Exact-head acceptance

Run on the actual PR head using JDK25/GraalVM with a nonzero-step runner:

```sh
mvn -B -ntp -DfailIfNoTests=true \
  -Dtest=ActorSupervisorUniqueOwnerRwTest,ActorStateRwLoansTest,ActorStateRwLoansCarrierMatrixTest,ActorStateRwRetirementPublishRaceTest,ActorStateRuntimePermitAuthorityTest,ActorCriticalStateModeTest,ActorCriticalModeConformanceTest,ActorSealedStateAuthorityGateTest,ActorStateRegionActorStopPinTest,ActorStateSourceTaskStopQuiescenceTest \
  test
```

The updated `.github/workflows/critical-state-runtime-loans.yml` must verify the exact PR-head SHA, each material test file's Git blob, populated Surefire XML with zero failures/errors/skips and actual guest-source tests. A job with `steps=[]`, `steps=null`, no runner, or source-free host-only tests is **NOT_EXECUTED** for this safety claim.

## Follow-up gates before mutable actor↔supervisor sharing is permitted

- Wire canonical allocation/projection and CFG/NLL borrow analysis into the **real compiler**, including move-after-use, duplicate owner, stale epoch, invalid Send/Sync, raw pointer escape, lock-free owner bypass after publication and disallowed suspension under a guard. Reject unsupported paths before IR lowering, not merely in host JS.
- Define one explicit API for runtime-owned synchronized shared regions, with capability revocation, bounded FIFO waiters, per-actor/tenant allocation budgets, and physical teardown isolation. Do not make `self.state` generally shareable.
- Prove the same positive+negative `.atto` source on Java/GraalVM, LLVM, .NET NativeAOT, Wasm and Forge, with exact tree/guest receipts, sanitizers or appropriate memory checks, cancellation races and two-actor supervisor tests. Do not conflate host-only RW model execution with compiler-backed guest execution.
