# Java/GraalVM source-actor lifecycle STOP fence + default init

**Status:** draft runtime/compiler integration on top of [PR #2889](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2889). This does not certify full Java guest actor lowering, five-backend parity, or native-image execution.

## Confirmed runtime gap and closure

Previously `ActorSourceLifecycleGate.close()` ran only during eventual physical retirement. A source initializer blocked while a different carrier requested STOP could still call `commitInit` against the old `INITIALIZING` state before physical retirement, despite STOP having already sealed mailbox admission. The `ActorRuntime` already checked `stopped` before publishing the ready Future; however, the separate source-owned state gate itself had no STOP-phase admission fence.

This branch introduces `sealStop(allowGracefulTeardown)`, called under the same ActorCell lifecycle monitor immediately after the winning STOP CAS. It records `STOPPING_TERMINAL` for NEW/INITIALIZING or non-graceful stop, and `STOPPING_GRACEFUL` for a fully initialized actor whose runtime-approved cooperative teardown is pending. `commitInit` and `requireReceive` only accept their previous `INITIALIZING` / `READY` phases, so no late READY or post-STOP guest receive can pass the inner gate. `beginTeardown` now requires the graceful sealed state, and remains once-only and owner-checked. `close()` still represents final physical retirement; revoking source ingress is not confused with proof of resource quiescence.

This introduces no public source method, no additional actor carrier, no background executor or callback under a lifecycle lock. It leaves mailbox accounting, task cleanup, child STOP and physical I/O retirement with the existing ActorRuntime.

## Compiler behavior

The opt-in same-unit `StatelessDeclaredActorLowerer` now treats `external init(): void { return; }` as optional, as the external-entrypoints v1 contract requires. Missing init emits a private inert `on_start` method that runs through the same actor runtime bootstrap/READY barrier. Only exactly one private `external receive(ActorMail<T>)` is required for this mailbox-driven subset. Stateful/side-effecting init remains rejected; general `declare actor` compilation, sealed attached state and arbitrary hooks remain fail-closed.

## Regression additions

- `ActorSourceLifecycleGateTest`: stop-before-start, stop-during-init, late commit rejection, no mail after stop, graceful vs abortive teardown, revocation and deterministic two-carrier init/STOP handoff.
- `StatelessDeclaredActorExecutionTest`: omitted init produces a private compiler-generated startup method; a real Graal Context guest-execution test aims to run the same mailbox source without explicit init.
- Existing tests for owner/type isolation, once-only initialization, bad state receipts and duplicate external role denial remain unchanged except the teardown test now explicitly requests graceful STOP before beginning teardown.

## Evidence and CI

All new Java tests are **authored, not executed** until exact-head JDK25/GraalVM JUnit and the compiled guest test have stepful receipts. The attached source-org workflow may fail with zero steps when Actions minutes are unavailable; that is runner admission failure, not a compiler regression. Run the existing PR #2889 exact-head workflow against this branch and capture individual test cases, source SHA, compiler version, and execution output.

The source actor implementation branch and the separate manifest-owned state witness stack have different parents and must be reconciled intentionally before any claimed all-lifecycle parity. Forge/LLVM/.NET/Wasm require independent STOP/init/READY/teardown guest paths, not a copied host model.
