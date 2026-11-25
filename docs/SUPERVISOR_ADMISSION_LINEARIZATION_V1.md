# Supervisor DATA admission/fence linearization v1 (stacked draft)

**Status: EXPERIMENTAL / DRAFT / NOT MERGED / NOT CERTIFIED.**
Stacked on [Java #2779](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2779) → #2773 → #2765 → supervisor FSM lineage #1965. Independent legacy terminal CONTROL PRs #1222 / #1320 still need canonical reconciliation before default enablement.

## Concrete race and commit semantics

Before this patch, `ActorRuntime.send()` checked a bound actor's escrow fence under the mailbox target's `cell.lifecycleLock`, then called `cell.mailbox.tryWrite`. A failing **different** supervisor used *its own* `cell.lifecycleLock` to invoke `ledger.fail`. The checks were individually synchronized but had **no single ordering point** against the ancestor fence. An actor could therefore pass the check before failure but enqueue afterward without an auditable notion of admission.

After this patch:

1. `SupervisorFailureEscrowV1.beginDataAdmission(node, generation)` checks the runtime-minted actor generation and `alive && !fenced` under **the SAME ledger monitor as** `fail` and `completeNormally`. It grants one identity-bound, ledger-local `DataAdmission` permit. **Successful permit issuance is the logical admission linearization point.**
2. `ActorRuntime.send()` acquires the permit at its final mailbox-commit path, **after** ordinary validation, data copying, quotas, and the late target lifecycle/sealing checks but **before** `mailbox.tryWrite()`. The sender always closes the permit in a `finally` block with `committed = admitted`. Successes and rollbacks are counted separately.
3. A supervisor crash atomically fences all registered descendants under the same ledger monitor. A racing `beginDataAdmission` either happens **before** that fence and returns a valid pre-fence permit, or happens **after** and rejects. There is no unaccounted third state. The exact mailbox write can happen after the ancestor fence if the ticket was already issued; **this is a pre-fence logically admitted operation**, not post-fence admission. Runtime shutdown must drain or cancel it, not reinterpret it as a new admission.
4. `dataInFlightInSubtree` counts every ticket that was granted but not committed/aborted. `SupervisorEscrowSubtreeRecoveryV1.restartBlocker` rejects restart with `DATA_ADMISSIONS_IN_FLIGHT` before physical-release assessment. A real physical-release verifier is STILL required; Java `done()` does not certify FFI/native release.
5. Permits are **private constructor, identity-checked and single-use**. Foreign/replayed permits are rejected; bounded 65,536 global concurrent permits fail closed on exhaustion. Runtime telemetry counts `inFlight, committed, aborted`, not actual message processing completion.

**Lock order:** runtimeLifecycleLock → target cell.lifecycleLock → escrow ledger. Terminal failure acquires failing cell.lifecycleLock → escrow ledger. Never call actor runtime cancellation, guest callbacks, external policy effect handlers, or an arbitrary mailbox callback **from inside the ledger monitor**. CONTROL coordinator snapshots release the ledger monitor before calling `runtime.cancel` on descendants.

## Nested source actor admission — deliberate fail closed

A previously admitted, supervised guest actor could create a *new* runtime child without atomically binding that child to the supervisor escrow. That child could escape a supervisor crash's **policy** subtree registry. For this draft, `spawnInternal` rejects nested spawn if its parent is opt-in escrow-supervised, rather than allow an untracked actor. **This temporarily restricts supervised nested spawn** until a subsequent transactional `reserve terminal slot → publish runtime actor identity + CONTROL owner → rollback on any failure` implementation is proven.

Host-only `recovery.attach` before first activation continues to work and is used for current bounded tree tests. Other, non-escrow actor spawn paths remain unchanged.

## Test gates and evidence

- **Dependency-free Java 21 race harness**, `SupervisorEscrowDataAdmissionRaceStandalone`, executes 16 rounds × 8 workers × 512 admission attempts = **65,536 attempted racing permits**, plus a deterministic pre-fence outstanding permit, ancestor failure, unrelated sibling, foreign/replayed token denial, and committed-vs-aborted counters. Must run on exact Git source blobs with `javac -Xlint:all -Werror`.
- Existing standalone `SupervisorFailureEscrowV1Standalone` contains **557 assertions**; the previous revision of the escrow passed those under JDK21. **The modified escrow blob and new 65,536-attempt harness are NOT_YET_EXECUTED**, so do not reuse the old result as a pass for this commit.
- New real runtime JUnit `SupervisorEscrowAdmissionLinearizationIntegrationTest` adds 3 authored tests: concurrent host sends vs real actor supervisor crash and committed-accounting equality; synthetic physical oracle cannot ignore pre-fence outstanding permits; supervised nested spawn denied until atomic child registration. Total dedicated real runtime tests now **10 (3 + 4 + 3)**, all **NOT_EXECUTED** unless a stepful exact-head hosted run becomes available.
- Source-org JDK25 Maven workflow requires *both* standalone passes and exact Surefire XML 10 non-skipped successful tests, backed by SHA-locked source/test files. The earlier org workflows returned **steps=null**; a zero-step failure is not semantic test evidence.

## Remaining P0 gates

1. Run real full-source `mvn test` and the 65,536-attempt exact-source Java race with actual stepful CI; fix any compiler/test errors. Verify no group or continuation bypasses the one `mailbox.tryWrite` DATA path.
2. Prove real guest scheduling and in-flight DATA processing/physical cancellation, including an already-issued permit whose actual mailbox write occurs *after* the fence. A bounded ticket check does not prove immediate guest quiescence.
3. Replace fail-closed supervised nested spawn with **atomic parent-child escrow reservation/publish/rollback**, without introducing an orphan window or acquiring locks out of order.
4. Integrate authoritative terminal CONTROL ledger and `SupervisorProcess` journal and physical `SupervisorShutdownTree` receipts. Do not create a second receipt authority.
5. Add actual native FFI/arena/borrow-frame release receipts, policy restart budget/escalation, compiled `define supervisor` guest tests, TLC and five-backend differential execution.
6. For complete JVM/process crash, an in-memory guardian is insufficient: external process supervisor + crash-consistent journal + process-generation fencing are separate release gates.

**No private cross-organization mirroring without security-approved destination. Keep the stacked PR draft.**
