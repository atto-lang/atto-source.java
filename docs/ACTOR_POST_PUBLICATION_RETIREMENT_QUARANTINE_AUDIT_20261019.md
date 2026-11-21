# SHARED actor post-publication retirement quarantine — October 19, 2026

**Status: DRAFT. Not an exact-head Java/GraalVM runtime test verdict.**

## Concrete audited gap

`LogicalSourceActorRouterV1` correctly pre-reserved the old route's generation-lease owner before irreversible mailbox/route publication. However, after a post-publication failure in `retireSourceAfterPublication`, `awaitTermination`, `physicallyFinalizedForHotUpgrade` or `generationLease.close()`, the transaction finalizer could clear `activeUpgradeTxn` and wake waiting sends while the old actor still had not been independently finalized. For the raw logical `ActorRef` path, nothing else quarantined the new physical route. New ingress or code-pinned Task admission could race incomplete old execution/retirement. The bound proxy bridge's own poison seal did not cover unbound router admission.

The opportunistic `reapRetiredLocked()` also iterated already-registered prior routes while their active supervisor upgrade transaction still owned the physical retirement/lease debit. Although a lease may itself be idempotent, allowing a reaper to consume that owner before the transaction finishes does not establish one authoritative debit.

## Production hardening

- Added `retirementQuarantined`, protected by the existing `routeLock`.
- `finishUpgradeLocked(txn, RETIREMENT_PENDING)` sets the quarantine **before** clearing `activeUpgradeTxn` or notifying waiters.
- `requireUpgradeAuthorityLocked` rejects a new upgrade while quarantine exists; no candidate is linked/pinned for a forbidden request.
- Raw `LogicalActorRef.send`, output subscription registration, proxy physical submission and both host/compiler Task admission seams enforce `requireAdmissibleRouteLocked` **after** physical retired-route reaping and **before** any new physical admission.
- Read-only epoch/image/publication observations remain accessible for supervisor diagnosis.
- Opportunistic `reapRetiredLocked()` does **not** release `activeUpgradeTxn.prior` while the transaction is active.
- Once `activeUpgradeTxn == null` and **all** retired-route owners have been physically finalized, their `generationLease.close()` succeeded, and the ledger is empty, the reaper clears the quarantine. A failed physical-finalization probe or failed lease debit cannot clear it. A successfully published route never rolls back.

The correction remains SHARED/trusted-host only. It does not authenticate remote completion, replace compiler state-schema compatibility, or extend to PRIVATE/UNTRUSTED/LLVM.

## Committed production JUnit acceptance cases — awaiting execution

`LogicalSourceActorRetiredLeaseRecoveryTest.postPublicationOldGenerationReleaseFaultRetainsRetryableRetirement` now injects a real source-compiled post-publication old-route pin-debit failure, verifies the persistent quarantine and refusal of further upgrade/physical sends, repairs the intentionally corrupted counter, reaps the original old route exactly once, and verifies the fence clears without rolling back route epoch.

`pendingRetiredPhysicalActorFencesSendsOutputsTasksAndUpgrade` injects an explicitly **test-only** live retired physical owner and quarantine state in a real source actor. It verifies reaping cannot claim a live actor and that physical sends, output readers, compiler-owned pinned Task starts and upgrade attempts are all rejected. The fixture restores its synthetic ledger before ordinary router close.

**No assertion in these source JUnit tests is called a pass until the exact source tree is executed.** Reflection fault injection does not itself prove all production races.

## Actually executed local source-free model

`/mnt/data/oreslang_retirement_quarantine_audit/RetirementQuarantineOracle.java` was compiled using **OpenJDK 21.0.12**, `javac --release 21 -Xlint:all -Werror`, and executed successfully.

- 20,000 sequential publish / postcommit-fault / still-live-actor / lease-debit-fault / reconciled-retirement cases;
- 1,500 two-platform-thread races between physical submission, the reaper and active transaction ownership;
- **269,000 assertions passed.**

This Java oracle is independently authored and does **not** compile the actual `LogicalSourceActorRouterV1`, Oreslang compiler, Maven JUnit, GraalVM or LLVM backend.

## CI provenance and source confidentiality

Source-owned Actions `fast-ci` jobs have failed before any test steps were executed. A zero-step failure provides **no** successful or failed Maven/JUnit verdict and by itself does **not** establish Actions-minutes exhaustion.

Protected source `main` currently includes zero approved repositories in `.github/private-ci-approved-repositories.txt`. Private-source exposure incidents `ores-truffle-oreslang/oreslang-platform-conformance` #22 and #77, CI governance #69 and source #962 remain open. A source-bearing mirror into another organization's repository is unauthorized until the exact source/destination repository ID, authenticated PRIVATE visibility, protected policy approval and incident signoffs are independently satisfied. **No private Oreslang source bytes were mirrored to another org.**

## Remaining acceptance gates

1. Execute exact-head JDK25 + Maven/JUnit + GraalVM guest and Native Image on a source-owned runner or explicitly authorized private mirror with verified Git tree SHA and commit provenance.
2. Test postcommit retirement timeout and injected `retireSourceAfterPublication` errors while concurrent senders, outputs and Task starts contend.
3. Prove prepublication rollback quarantine when a candidate physical stop/transfer rollback itself fails, and maintain ownership until physical exit.
4. Extend one supervisor commit to all compiler-issued code/task/state/checkpoint/FFI/region leases and authenticated terminal receipts.
5. Keep PR #1367 Draft pending all gates.
