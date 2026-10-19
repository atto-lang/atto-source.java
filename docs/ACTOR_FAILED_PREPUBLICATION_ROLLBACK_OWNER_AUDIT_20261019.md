# SHARED actor failed prepublication rollback ownership — October 19, 2026

**Status: DRAFT.** Production JUnit/JDK25/GraalVM remain **unexecuted** on the exact source head. This audit covers a host/same-JVM SHARED actor transfer proof only.

## Confirmed failure mode

`LogicalSourceActorRouterV1.upgradeInternal()` pre-reserves ownership of an unpublished candidate's physical ActorRef and code-generation lease, but on a **failed** `ActorRuntime.SourceActorTransfer.close()` it formerly recorded a suppressed rollback exception, set `txn.transfer = null`, and completed the supervisor transaction as `ABANDONED`. If the candidate was still physically executing or unfinalized, `SourceActorTransfer` retained the old actor's ingress freeze and was itself the **only** precise capability able to reconcile/release that freeze. The router did not retain this capability or expose a retry. After transaction exit, physical route admission could be attempted without a coherent supervisor recovery owner; at minimum the old route became permanently frozen/unreconcilable.

This differs from a failed *postpublication* retirement, where the new route is already irrevocably authoritative, and which is handled by the existing retired-route quarantine.

## Code changes

- Added `pendingRollbackTransfer` as a private, route-lock-protected retry owner for exactly one failed unpublished transfer; all upgrade transactions are serialized.
- On `txn.transfer.close()` failure, store the **exact** transfer capability and set `retirementQuarantined = true` under `routeLock`, **before** clearing `activeUpgradeTxn` or waking ingress waiters. The original timeout/error remains primary, rollback errors remain suppressed.
- A retirement reaper may still independently release an unpublished candidate generation pin **after** verified candidate physical finalization, but cannot clear the route quarantine merely because `retiredRoutes` is empty while `pendingRollbackTransfer` exists. Debiting a candidate pin does NOT grant ownership of the old actor's freeze.
- Added host-only `reconcileFailedUpgradeRollback()`, serialized against `close()` under `closeLock`: retry `SourceActorTransfer.close()` without holding `routeLock` across physical stop/join. A failed retry leaves exact ownership and quarantine in place. A successful retry forgets the retained transfer only after its old freeze is released, and reaps verified retirement owners before reopening new admissions.
- `close()` refuses to retire the old physical route/generation while an unpublished candidate still owns an unreconciled transfer. This prevents code unload underneath an unknown staged candidate.
- If no unpublished rollback owner exists, `reconcileFailedUpgradeRollback()` reports true only if the **separate postpublication retirement quarantine** is also clear. It never wrongly reports success for an unreaped published old actor.
- Existing raw ActorRef send, output subscription, pinned Task starts, bounded proxy physical dispatch and new upgrade preflight already consult `requireAdmissibleRouteLocked`. These paths remain fail-closed until the rollback reconciliation is proven.

The recovery API is deliberately **not** exposed to guest actors, and it is not a cryptographic remote-worker receipt or a general transaction/FFI finalization authority.

## New JUnit regression sources (committed, not executed)

1. `LogicalSourceActorSupervisorUpgradeTxnTest.proxyTransactionOwnsFreezeAndRollsItBackAfterReadyTimeout` now verifies that a candidate readiness timeout with a deliberately withheld physical dispatch retains an exact `pendingRollbackTransfer`, quarantines new upgrades, leaves the old physical proxy epoch unchanged, and requires the post-stale-dispatch supervisor reconciliation before returning to a usable route.
2. `LogicalSourceActorRetiredLeaseRecoveryTest.unpublishedCandidateOwnsGenerationBeforePhysicalSpawnAndRetainsItAcrossTimeout` now checks the same condition for an unbound raw SHARED logical ref, including refusal of old physical `send` and `close` before candidate finalization. After exercising the captured stale dispatch and reaping the candidate generation lease exactly once, the test must explicitly reconcile the original ingress freeze and verify the old actor can send.
3. `postPublicationOldGenerationReleaseFaultRetainsRetryableRetirement` confirms the rollback-reconciliation API does **not** signal success for an independent unreaped postpublication actor-generation lease.

These committed tests must be executed on the **actual exact head** before any pass claim.

## Actually executed independent source-free Java model

`/mnt/data/oreslang_failed_rollback_audit/RollbackOwnershipOracle.java` was compiled with OpenJDK **21.0.12.1**:

```
javac --release 21 -Xlint:all -Werror RollbackOwnershipOracle.java
java RollbackOwnershipOracle
```

**PASS: 426,000 assertions** across 20,000 sequential rollback/lease/deadline scenarios plus 2,000 contested races. The model asserts no old-route admission during quarantine, no candidate lease release before physical exit, no double pin debit, no thaw through independent candidate reaping, and single authoritative reconciliation of a held transfer freeze.

This is an independent protocol model, **not** a build or execution of private Oreslang production Java classes, JUnit, JDK25, GraalVM, Native Image or LLVM.

## Source confidentiality and CI policy

The source organization's existing Actions workflows still fail during runner admission with **zero executed steps**; this is not conclusive proof of minutes exhaustion or test failure. Protected source `main` has **zero** approved cross-organization destinations in `.github/private-ci-approved-repositories.txt`. Security/CI blockers `ores-truffle-oreslang/oreslang-platform-conformance` #22, #77, #69, and source issue #962 remain unresolved. A funded test organization's Actions minute balance and token-free identical-Git-tree mirroring do **not** constitute permission to copy private source. **No new cross-organization private-source mirror was created.**

## Remaining release gates

Execute a protected exact-source JDK25 Maven+GraalVM test run once runner/budget/security approval is repaired. Inject failed prepublication candidate stop, transfer close/retry, physical-finalization latency, interrupted supervisor close, malformed ABI and concurrent admission. Verify all live ownership through complete physical exit; also test general compiler Tasks/FFI/region/device code pins and isolated worker / LLVM implementations. Leave PR #1367 Draft.
