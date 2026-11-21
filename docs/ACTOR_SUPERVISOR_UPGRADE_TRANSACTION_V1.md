# Actor Supervisor Upgrade Transaction V1

Status: SHARED source-actor proof, draft integration.

This document defines the single supervisor transaction used by
`LogicalSourceActorRouterV1` to coordinate a transparent source-code route
replacement. It is intentionally narrower than the future PRIVATE /
UNTRUSTED protocol, but it removes caller-owned publication authority from the
current SHARED path.

## Authority

At most one `SupervisorUpgradeTxn` may be active under `routeLock`.

The transaction owns:

- the exact old authoritative `Route`;
- the candidate generation route lease;
- the pre-reserved unpublished-candidate retirement owner;
- the bound logical proxy mailbox identity, when present;
- the exact proxy `FreezeTicket` acquired by the router itself;
- the `SourceActorTransfer` checkpoint/state-transfer capability;
- the staged candidate physical ActorRef;
- the prepared successor `Route`;
- the publication phase and candidate-lease ownership handoff.

The proxy bridge no longer acquires or passes the publication freeze. The
production three-argument `upgradeToForProxy(candidate, timeout, mailbox)`
lets the router acquire, commit or roll back that capability. A temporary
package-level four-argument compatibility seam consumes/rolls back its legacy
ticket before candidate linking and then starts a fresh router-owned
transaction; the supplied ticket can never reach publication.

## Phases

The transaction moves monotonically through:

`PREPARING -> STAGING -> PUBLISHED -> RETIRING -> COMPLETE`

or terminal fail-closed states:

- `ABANDONED` for a prepublication failure;
- `RETIREMENT_PENDING` when the new route is authoritative but stale physical
  retirement remains retryable.

`activeUpgradeTxn != null` replaces the former independent `upgrading`
boolean. Host route sends wait while a transaction owns publication authority.
Exact message receipts reject settlement across the same boundary.

## Prepublication ownership

Candidate linking and compiler receipt construction occur before the
transaction becomes active. Once the candidate generation route lease exists,
the router allocates and registers a `CandidateRetirement` owner before any
candidate physical actor can execute.

A bound proxy freeze is then acquired by the transaction under the established
`routeLock -> mailbox lock` order. If the old dispatch watermark is not
drained, the transaction explicitly rolls back its freeze and returns without
publishing.

Candidate physical admission fills the already-existing retirement owner.
Ordinary route observations and `reapRetired()` cannot consume a staging
candidate because `retirementEligible` remains false while publication is
still possible.

The source transfer itself is the scheduler/Task/checkpoint gate. It freezes
old physical actor ingress and refuses transfer unless mailbox turns,
continuations, pending operations and `hotUpgradeExternalPins` are quiescent.
Compiler-owned Task scopes therefore keep the old code generation unavailable
for transfer through the same physical actor lifetime fence.

## Point of no return

Before publication the router prepares:

1. successor `Route`;
2. old-route retirement ownership;
3. candidate ownership/physical identity validation;
4. exact proxy freeze/epoch validation.

For a bound proxy, `FreezeTicket.commit()` advances the mailbox epoch. There
is no allocation-bearing or validation operation between that commit and
`active = next`.

Under the same `routeLock`, the router then:

- swaps the authoritative physical route;
- marks the candidate lease as transferred to the published route;
- records the published candidate bookkeeping marker;
- advances the transaction to `PUBLISHED`.

A successfully published route is never rolled back.

## Retirement and quarantine

After publication the transaction retires the old physical actor. The old
generation route lease was registered before commit and remains owned until:

1. physical finalization is independently verified; and
2. `generationLease.close()` succeeds.

If retirement, physical-finalization proof or lease debit fails, the new route
remains authoritative and the stale route stays in the retryable retirement
ledger.

For unpublished candidates, all non-transferred lease release follows one
close-before-forget rule under `routeLock`:

- if no physical candidate exists, close the generation lease before removing
  its owner;
- if a candidate physically finalized, close before removing its owner;
- if the candidate is still live or finalization is ambiguous, mark its
  pre-reserved owner retirement-eligible and transfer retry authority;
- if a lease debit itself fails, keep the owner, mark it retirement-eligible,
  and let `reapRetired()` retry the exact lease later.

No cleanup path needs to allocate a new retirement owner after physical
candidate admission.

## Regression source

`LogicalSourceActorSupervisorUpgradeTxnTest` covers:

- a bound proxy candidate whose dispatcher accepts but never executes the
  initial schedule: while staging there is one active transaction and one
  transaction-owned mailbox freeze; timeout rolls back both without changing
  route/mailbox epoch;
- successful proxy publication: route epoch, mailbox epoch and generation
  witness move coherently and no transaction/freeze remains;
- candidate generation lease-debit failure before physical spawn: the exact
  retry owner survives, a repaired `reapRetired()` closes that lease once,
  and no second reap is possible.

Existing cross-context, proxy, TaskScope, receipt and retirement tests continue
to cover state restoration, queued immutable mail, physical Task pins, exact
message terminal receipts, precommit OOME and stale-route retirement.

## Executed independent model evidence

No private Oreslang source is exported for this evidence.

The source-free oracle in
`gha-indie-worker/gha-indie-worker-e2e` commit
`46dba8f88468654fecc8cc79c4bc055fa5c9b81a`, workflow run
`39321014736`, job `118294761469`, executed on Temurin JDK 25.0.4+1.

`javac --release 25 -Xlint:all -Werror` passed, followed by a deterministic
state-machine run reporting:

```
PASS source-free hot-upgrade oracle
route_race_rounds=2000
terminal_race_rounds=10000
candidate_owner_rounds=20000
candidate_owner_races=2000
supervisor_txn_rounds=20000
supervisor_txn_atomic_epochs=pass
private_source_bytes=0
```

This is protocol-model evidence only. It is not execution of the private
`LogicalSourceActorRouterV1`, Maven/JUnit, GraalVM or Native Image tests.

## Remaining gates

This V1 transaction still needs:

- an authorized exact-tree JDK25/Maven/Graal/Native Image execution;
- compiler-generated general immutable actor-message envelopes rather than
  primitive proof codecs;
- authenticated receipt provenance suitable for PRIVATE/UNTRUSTED IPC;
- protected worker/isolate migration;
- automatic generation leases for every timer/FFI/region/device resource;
- a backend-neutral transaction contract shared with LLVM/native targets.


## Acquired-resource publication rule

Any runtime resource acquired after the transaction begins is written into its
transaction cleanup owner **before** the next invariant/phase validation that
can throw. This applies in particular to:

- the `SourceActorTransfer` returned by the old runtime; and
- the candidate physical `ActorRef` returned by candidate spawn.

The physical ActorRef is also written into the pre-reserved
`CandidateRetirement` owner before post-spawn validation. Consequently an
injected/corrupted transaction phase cannot strand an old-ingress freeze or an
executable candidate outside rollback/quarantine ownership. Validation may
fail, but cleanup authority has already been published.

`candidatePhysicalIsOwnedBeforePostSpawnInvariantValidation()` injects phase
corruption synchronously from the candidate dispatcher's admission callback,
so corruption occurs after physical scheduling admission but before the
router's post-spawn check. The failed transaction must leave the old route
authoritative, clear publication authority, retain a candidate owner containing
the exact physical ActorRef, mark that owner retirement-eligible, and permit
exactly one later reap after physical finalization.

## Spawn admission rollback after collection mutation — 2026-10-19

A follow-up runtime audit identified a narrow partial-spawn ownership gap in
`ActorRuntime.spawnInternal`. In both inherited-ActorGroup and ordinary
parent child-admission paths, the runtime inserted the new child into
`parent.children`, and only **after** the operation returned set
`linkedToParent = true`. The rollback handler unlinked child lineage only
when that marker was true. An adversarial collection failure after a
successful mutation (for example fault-injected OOME) could therefore leave
a parent-owned child entry for an actor whose constructor/registry admission
failed and whose actor slot was being rolled back.

The runtime now arms `linkedToParent = true` **before** the potentially
throwing `parent.children.add(cell)`, in both managed-group and ordinary
child paths. Removing a child that was never inserted is harmless, but
failing to remove one that was inserted and then threw is an orphaned lineage
reference. Existing rollback still removes the exact registry identity and
group worker ownership, aborts the never-published cell, and refunds the
reserved actor slot.

A new production-source JUnit `ActorRuntimeSpawnLineageRollbackTest`
provides two fault-injection tests:
1. A real parent actor with a swapped test-only child set that inserts a
   child then throws OOME. The parent catches the injected exception; the
   test checks it has no child entry, only the original parent remains in
   the physical actor registry and actor count, and physical parent shutdown
   still completes.
2. A test-only actor registry that inserts a root actor then throws OOME.
   The runtime must roll back the registered entry, refund the actor slot,
   and admit/stop a subsequent healthy actor.

These are **committed but unexecuted** product JUnit source tests. The
separate source-free Java 21 `SpawnAdmissionFaultOracle` compiled with
`javac --release 21 -Xlint:all -Werror` and passed **201,500 assertions**
across 25,000 post-mutation root/child failure cases and 500 four-thread
fault-injection rounds. It models the relevant ownership invariant but
does not compile/execute the private Oreslang runtime, Graal/JDK25,
source compiler or Native Image. A future exact-head runner must execute
the real JUnit fixtures before treating this as production certification.

This fixes a child-lineage publication ordering hazard; it does **not**
establish that every external dispatcher/ActorRuntime registration which
throws before returning `ActorRef` has full physical retirement tracking
under arbitrary task/external-resource failure. That wider partial-spawn
ownership proof, source-supervisor transaction integration and backend
retirement validation are still release gates.

The protected source `main` allowlist still has **no approved private
CI destinations**, and source transfer security issue #962 plus branch
protection #904 are unresolved. No private source was copied to another
organization. Keep PR #1367 draft and production hot reload disabled.

## Independent spawn rollback after collection cleanup faults

The runtime's post-admission failure path previously wrapped physical
registry removal, parent-child unlinking, managed ActorGroup membership
removal, and unregistered cell disposal in one exception handler.
A registry removal that performed its mutation and then threw (including
fault-injected out-of-memory errors) prevented every subsequent cleanup
step from executing, even though the reserved actor slot was refunded.
This could strand parent lineage and group ownership for an actor whose
spawn did not return a reference.

The post-spawn failure path now runs each teardown operation under an
independent guarded attempt, preserving the original admission failure and
continuing remaining cleanup when a prior attempt throws. The existing
actor-count refund stays in the outer finally. This change intentionally
does not treat a failed collection operation as proof that its mutation
completed; a hypothetical removal that throws before removing a live
registry entry remains an unproven failure mode requiring stronger
registration ownership/reconciliation.

Real-runtime JUnit regression
`ActorRuntimeSpawnLineageRollbackTest.failedRegistryRemovalAfterMutationStillUnlinksParentAndRefundsSlot`
uses a faulted actor registry whose child `put` inserts then throws and
whose child `remove` deletes then throws. It asserts the original parent
is still registered, the child lineage is removed and the reserved
actor count is refunded. The JUnit source has been committed but is
**not executed by the source repository's JDK25/Graal Maven workflows**.

An independent Java 21 fault model compiled with
`javac --release 21 -Xlint:all -Werror` and passed
**300,044 checks**, including 60,000 concurrent fault rounds across
four workers. The negative control for the previous one-try ordering
reported seven unsafe injected-fault configurations. This verifies a
standalone model, **not the Oreslang ActorRuntime implementation**.

The private CI destination policy on source main still has no approved
cross-organization mirror, security issue #962 remains open, and
source-owned workflows have failed without job execution steps.
No private source was transferred or copied to another organization.
Production hot reload and PR merge remain blocked.

## Pre-return physical actor registration ownership — unresolved integration gate

A source-runtime admission audit found that if an ActorCell registry
insertion succeeds but throws, and the subsequent rollback removal throws
**before** deleting that exact entry, a logical supervisor previously had
no returned ActorRef. It could treat the candidate as never physically
admitted and prematurely close the code-generation route lease.

`ActorRuntime.spawnInternal` now distinguishes *verified absence* from
an unresolved registration. A remaining exact ActorCell retains its actor
slot and physical-finalization marker remains false; only absence and
successful unregistered-cell teardown permit a pre-return finalization
marker. Trusted source-actor callers can use a new package-level
`onAllocated` callback to receive the physical ActorRef before
restoration/registry admission, and the source transfer API exposes a
matching package-level handoff parameter.

Commit `4f6e880edc7027c974a224dbac0d0f6815a3db56` makes the
runtime-side change. New real-source JUnit
`ActorRuntimePreReturnAdmissionQuarantineTest`, commit
`a139e68da3114ae4bb447ee476d6903f2f9e2f11`, exercises a
source-compiled actor whose registry inserts then throws, while removal
throws before changing the collection. It expects the exact preallocated
ActorRef to remain observable, the original slot to remain counted,
no premature physical finalization, and eventual exact stop/reap.

**Superseding integration update (October 20):** A later commit in
this draft branch connected the internal `onAllocated` callback in both
production call sites. Initial `LogicalSourceActorRouterV1.spawn()` stores
the exact physical ref in its pre-reserved `FailedInitialSpawn` owner;
the supervisor's candidate-transfer call stores it in both
`SupervisorUpgradeTxn.replacement` and
`CandidateRetirement.physical` before restoration or registration can
throw. This resolves the previously blocked *wiring* gap, but not its
outstanding end-to-end certification.

The original source-free Java 21 ownership model initially reported
323,000 assertions but subsequently failed under repetition. See the
following section for the corrected atomic-retirement model and
successful source-free JDK25 CI. The real source JUnit has not run.

Until the newly wired router handoffs are verified by actual exact-head
JDK25/Maven/Graal CI, keep PR #1367 draft and production hot reload
disabled. Do not substitute source-free models for production tests.

## Admission lease race regression and revised model evidence

The earlier isolated `AdmissionProtocolOracle.java` was rerun eight times
under OpenJDK 21 with `javac --release 21 -Xlint:all -Werror`.
It succeeded four times and failed on its **fifth run** due to a
non-atomic `pins.get() > 0` followed by `pins.decrementAndGet()`
under concurrent retirement. The earlier one-run "323,000 PASS" must
therefore **not** be considered a stable race proof.

The corrected source-free `AdmissionProtocolOracleHardened.java`
uses a per-reference atomic compare-and-set `leaseReleased` owner
before decrementing the code-generation pin. It passed **five
consecutive** Java 21 runs, with 420,001 assertions and 5,000
four-worker races per run. Its synchronized negative control forces
four workers to observe one unsafe pin before decrement and proves
that the previous check-then-decrement protocol can underflow to -3.

A *separately authored public oracle*, `AdmissionLeaseOracle.java`,
was committed to `gha-indie-worker/gha-indie-worker-e2e`
at `tmp/oreslang-hot-upgrade-oracle/AdmissionLeaseOracle.java`.
The branch commit `7c298172a687ba6e6651c7433912f9a498e92f7d`
extends the existing public JDK25 workflow to compile the oracle
using `javac --release 25 -Xlint:all -Werror` and exercise
its atomic release/race and negative-control cases.
The public workflow run is
https://github.com/gha-indie-worker/gha-indie-worker-e2e/actions/runs/39345492773.
**Executed JDK25 evidence:** run `39345492773`, job
`118376956031` finished **SUCCESS** on Temurin 25.0.4+1.
The full job log confirms `javac --release 25 -Xlint:all -Werror`,
followed by:
```
PASS source-free admission lease oracle assertions=260001
race_rounds=5000 workers=4
unsafe_check_then_debit_negative_control=detected private_source_bytes=0
```
The existing hot-upgrade lifecycle oracle compiled and passed in the
same job. This is **source-free protocol coverage only**.
Only independently authored model source was sent to the test
organization, with **no Oreslang private source bytes**.

The real `ActorRuntimePreReturnAdmissionQuarantineTest` has also been
extended (commit `367d699cafa1a9d2450c3cf2c3a60075e1c1e40a`)
with eight concurrent physical stop attempts of the *same*
pre-registration ActorRef. The test requires a single physical-finalization
marker, one actor-slot refund, and no ghost registry entry.
That production JUnit is still unexecuted by the private repo's
JDK25/Graal CI.

**Updated ownership status:** Both source-router callback call sites
have now landed (see the superseding integration note above).
`LogicalSourceActorPreReturnHandoffTest`, committed at
`b08f65674b38bd2be967fd0e7e28ea5b61d0d7c4`, adds two
production source-compiled JUnit cases for initial registration and
candidate migration where a registry insertion throws after publication
and the first rollback removal throws before mutation. They check
eventual cleanup of the exact runtime slot and generation pin, the
old route's authority after candidate failure, and successful retry.
**These JUnit tests have not executed.** Neither the passing source-free
model nor public CI execution authorizes private-tree copying, satisfies
source security #962/#904, or certifies code/Task/checkpoint publication.

## Registered child survives failed cleanup before registry mutation

The next rollback audit found that `ActorRuntime.spawnInternal` already
kept `actorCount` reserved if the exact ActorCell remained in its runtime
registry, but *unconditionally* removed that child's parent and managed
ActorGroup lineage. A registry-removal error **before mutation** therefore
left a still-registered physical child with no parent lifecycle link;
the parent might certify physical exit and release its old code generation
while the child still existed.

Production commit `9ea8b9f34f8f813f9f427f618835c9b7bfb3c2de`
makes parent child-unlink and inherited managed worker-unlink contingent
on proving the exact ActorCell is **absent** from `actors`. If registry
removal was not proven, the runtime retains actor slot, parent lineage
and group membership until that child physically finalizes.
An ordinary successful rollback still removes both links, disposes
the never-published cell, and refunds the actor slot.

Real-product JUnit regression in
`ActorRuntimeSpawnLineageRollbackTest`, commit
`e8c991f3d3fa377f072c29c96773e171e00ef9e1`, injects
child registry insertion followed by a throw and cleanup registry
removal throwing *before* mutation; it checks that the child remains
registered, has a parent lifecycle link and retained actor slot, then
stops the exact surviving child and verifies a single terminal slot
debit and parent unlink. This JUnit is **committed but not executed**.

An **independently authored, source-free** JVM lifecycle model was run
on funded Temurin 25.0.4+1 at
https://github.com/gha-indie-worker/gha-indie-worker-e2e/actions/runs/39348429779
(job `118386578808`, source-free branch commit
`bc0f1b283a0eebeebecb36c1353bc58534d4735c`).
The successful log reports:
```
PASS source-free ghost child oracle checks=126001
four_worker_races=2000 pre_mutation_failure=verified
old_unlink_negative_control=detected private_source_bytes=0
```
The independent admission-lease and existing hot-upgrade lifecycle
oracles also passed in that public job. Those tests are **not** a
private Oreslang source/JUnit/Graal/Native Image execution receipt.

This guard prevents premature *parent* completion when an already-linked
child remains registered. Remaining unverified failure modes include
registry admission that occurs even though a prior parent-link insertion
itself threw before mutation, external native/FFI lease ownership,
unrecoverable host policy refusal, and global CONTROL transaction
publication across scheduler checkpoints. Exact-head source CI still
reports failures with no executed steps; the private-source mirror
destination in protected main remains a comment-only proposal, **not**
an authorization. Source security #962 and merge protection #904
remain open. Keep PR #1367 draft and production hot reload disabled.

## Candidate-retirement quarantine exit barrier — October 20, 2026

The source router's `reapRetiredLocked()` previously cleared
`retirementQuarantined` when no old routes or pending rollback transfer
remained, **without checking whether an unpublished candidate was still
tracked in `retiredCandidates`**. A candidate that survived a failed
prepublication registration/transfer can remain physically live and pin its
executable generation even after all other cleanup owners have disappeared.
Reopening physical mailbox/Task admission in that state violates the
supervisor's fail-closed generation boundary.

Commit `ca6a1eae4daac87590eddf49c41b39837560abb7` changes the sole
automatic quarantine exit gate to require
`retiredCandidates.isEmpty()` as well as no active transaction,
pending rollback transfer, or retired old routes. Successfully published
candidates' nonowning staging witnesses are removed in the preceding
candidate-reaper loop; unpublished physically live or noneligible
candidates must continue blocking quarantine clearance.

`LogicalSourceActorCandidateQuarantineBarrierTest` is new real-source
JUnit code committed at `367feebc5ce22cc79d90382b528550f49ae0dc8c`
and strengthened at `659326945d41ee63b6c8b32ecfa576a33eeb0243`.
It uses a real compiler-linked SHARED old actor and real separate
candidate physical actor, a pre-reserved candidate generation pin, and a
test-only injected candidate-retirement ledger owner. It verifies that
unpublished live candidates cannot reopen physical message admission,
that `retirementEligible` alone is not physical-finalization proof,
and that physical finalization permits one successful generation-pin
debit, retirement ledger removal and restoration of the old route's
message delivery. This test is **COMMITTED BUT NOT EXECUTED** under the
private Oreslang JUnit/Graal build.

A **separately authored source-free Java25 model** was committed to
`gha-indie-worker/gha-indie-worker-e2e` on its isolated oracle branch;
workflow source commit `838ffae421cd2d66a7dae20721cb18eabf08f29a`.
Actual GitHub Actions run `39348892604`, job `118388105501`,
completed SUCCESS on Temurin 25.0.4+1. The logs confirm both
`javac --release 25 -Xlint:all -Werror` and:
```
PASS source-free candidate quarantine assertions=185001
concurrent_rounds=5000 workers=4
negative_control=exposes_early_release private_source_bytes=0
```
The same job passed existing independent lifecycle/admission tests.
This is objective JDK25 execution of a **model**, not execution of any
private Java/Graal Oreslang production source or Native Image.

Security/CI hold: protected `main` has no *approved* private carrier;
its proposed destination appears only in commented policy lines requiring
review. Source security #962 and protected merge checks #904 remain open.
No cross-org private Git tree or source bytes were copied. Keep the PR
draft and production hot reload disabled pending exact-head source-owned
or reviewed/authorized JDK25/Graal/Native Image CI.

## Staged transfer freeze ownership before candidate registry admission — 2026-10-19

The pre-return ActorRef callbacks were successfully wired into
`LogicalSourceActorRouterV1` for initial source spawn and
`SupervisorUpgradeTxn` candidate staging. A follow-up audit found a
SECOND authority edge inside `ActorRuntime.spawnCodeActorFromTransfer`:
the runtime's `SourceActorTransfer.stagedCandidate` was still bound only
*after* `spawnInternal` returned. If a candidate ActorCell was inserted
into its registry, then that insertion threw, and the cleanup removal
also threw **before** mutation, the transfer's catch path called
`rollbackFailedRestoreLocked()` and closed the old ingress freeze as
though no executable candidate existed. A registered candidate could
therefore outlive the old-route rollback proof.

Production commit `9d2b433e735fee77d939f4f48863a4c070e67557`
changes the ordering: the runtime transfer binds the exact
`stagedCandidate` while holding `transferLock`, inside the
pre-registration physical-ref callback, **before** invoking the
source-router owner callback or attempting checkpoint restoration and
actor registry admission. If spawn fails without an allocated candidate,
normal failed-restore rollback can still close the freeze. If it fails
with a staged candidate, the transfer remains `CLAIMED`; only the
supervisor's `SourceActorTransfer.close()` rollback may release old
ingress, and only after the staged candidate's physical finalization
is verified. Any ambiguous physical retirement keeps the freeze and
registered candidate owner for retry rather than reopening old ingress.

Real source-compiled JUnit
`LogicalSourceActorPreReturnHandoffTest.preReturnCandidateKeepsOldTransferFreezeUntilPhysicalRemoval`
was added in commits `f59e4fd78201ac44933c6f6329f8ffc7fecc0ddb`
and `275b08b9ef38c8e07af8513e45190a7f9bfeccad`.
The fixture's map inserts then throws, fails the first removal
without mutation, and blocks the final physical removal under a
test-only latch. It requires exact staged-transfer identity,
`CLAIMED` rollback state, candidate code-generation pin retained at
one, old physical route identity still authoritative, and release
only after the latch allows terminal cleanup. This JUnit has
**NOT** executed on the private source-owned CI.

Independent source-free Java 25 protocol proof was committed to
`gha-indie-worker/gha-indie-worker-e2e` test branch at
`eb71426f454290b44a8d07d170f7b67a5258e3c8`.
GitHub Actions run
https://github.com/gha-indie-worker/gha-indie-worker-e2e/actions/runs/39351166287,
job `118395407425`, completed **SUCCESS** on JDK25,
compiling `TransferFreezeOracle.java` with
`javac --release 25 -Xlint:all -Werror`, then running
**110,001** checks including **5,000 four-worker** retirement races
and a negative control that forces premature old-freeze release using
the former staging order. No private Oreslang source bytes were
transferred to the funded carrier. This is a separately authored
protocol model and **NOT** production Java runtime/Graal/Native Image
certification.

Exact source CI at `275b08b9ef38c8e07af8513e45190a7f9bfeccad`
reported `fast-ci` run `39351044684` and full `ci` run
`39351044700` as failures with every inspected job showing
`steps:null`. The job log endpoint returned `BlobNotFound`.
This cannot establish exhausted Actions minutes, source JVM
compilation, or a failing JUnit assertion. Source `main`
contains no **approved** cross-org private mirror destinations;
security issue #962 and independent merge controls #904 remain
open. Continue holding PR #1367 DRAFT, private source in its
authorized repository, and production hot reload disabled.

## Setup-phase proxy freeze rollback retry ownership — 2026-10-19

A supervisor audit found that the later `SupervisorUpgradeTxn` finalizer
retained an exact `pendingProxyRollbackTicket` when mailbox rollback
failed, but the **earlier setup catch** did not. After the proxy freeze
was acquired, `FreezeTicket.drained()` could fail (e.g. injected
post-freeze mailbox invariant corruption). The setup catch tried
`ticket.rollback()`, suppressed its failure, abandoned the transaction
and forgot its freeze retry authority. The mailbox stayed frozen but
no supervisor reconciliation could thaw it.

Production change `3958af29c3fe14053ee5fd001f9f14d5403c948e`
records the exact `pendingProxyRollbackTicket` and sets
`retirementQuarantined` **before** abandoning a transaction whose
setup-phase proxy ticket rollback failed. The existing finalizer's
second failed rollback remains owner-consistent, and the same
`reconcileFailedProxyRollback()` route can safely retry only after
candidate retirement ownership is discharged and the mailbox's
structural invariant is repaired. Ordinary successful setup rollback
continues releasing the ticket normally. A foreign pending ticket is
rejected rather than overwritten.

Production JUnit in
`LogicalSourceActorSupervisorUpgradeTxnTest.setupFreezeRollbackFailurePreservesExactTicketForSupervisorRepair`,
commits `11c5cc1437f4cb4106284d85da4f369c9ecec9e0`
and `02773e38e04ed57dff1e362fac4fd122ba3e6701`,
injects a test-only outstanding map that changes mailbox retained
message accounting **after** the freeze and throws from its first
watermark-drain observation. It checks that both rollback attempts
leave the *same active freeze ticket* owned, quarantine stays set,
close and reconciliation fail closed while accounting is invalid,
and explicit reconciliation succeeds without route epoch change once
the test restores structural validity.

**Source-owned test status:** JUnit is committed but has NOT executed.
Source fast/full CI on the current head continue to fail with
`steps:null`; neither Maven compilation nor GraalVM/Native Image
assertions were observed. The problem cannot yet be ascribed to
Actions minutes rather than policy/runner admission.

**Independent JDK25 model evidence:** a separately authored,
source-free `SetupProxyRollbackOracle.java` runs on the funded
`gha-indie-worker/gha-indie-worker-e2e` test repository without
copying Oreslang source. Commit
`3d366e24a591e0d80f80d9824a02ffab529d0f8c`,
[workflow 39352492057](https://github.com/gha-indie-worker/gha-indie-worker-e2e/actions/runs/39352492057),
job `118399655709`, completed SUCCESS with Temurin JDK25,
`javac --release 25 -Xlint:all -Werror`, and exact log output:
```
PASS source-free setup proxy rollback checks=130001
four_worker_races=5000 lost_ticket_negative_control=detected
private_source_bytes=0
```
This proves only the independent protocol model, not the production
router/Java tests. The private CI destination allowlist under
protected source main remains comment-only; security #962 and
independent protected review #904 remain open. No private source
was transferred to another organization. Keep PR #1367 DRAFT and
production hot reload disabled pending full exact-head certification.

## Failed proxy freeze rollback — persistent ticket owner and retry (October 20)

The SHARED upgrade finalizer previously attempted
`txn.proxyTicket.rollback()` and, on an exception, only attached the
failure to the original exception. It could subsequently abandon the
supervisor transaction without retaining an explicit durable retry
handle for the mailbox freeze. A freeze-ticket rollback can fail
before thaw if the bounded proxy detects an accounting/structural
invariant error; that is NOT evidence the old physical route is safe
to reopen.

Commit `4e25209047874888871addca162111f4f6078334` adds the
exact `pendingProxyRollbackTicket` to the supervisor's
`routeLock`-protected recovery state. A failed explicit rollback:
- retains the original, unconsumed mailbox freeze ticket;
- marks `retirementQuarantined=true` **before** the active
  transaction releases publication authority;
- prevents the reaper from clearing quarantine while that ticket
  remains outstanding, even if all old routes/candidates are gone;
- prevents `close()` from abandoning the frozen mailbox; and
- exposes a host-only `reconcileFailedProxyRollback()` that retries
  the same ticket under `closeLock`/`routeLock` after pending
  source-transfer rollback, old-route retirement and candidate
  physical retirement are reconciled.

The host recovery API does **not** commit a new route epoch, reconstruct
a different ticket, or infer a thaw from a missing registry entry.
Only a successful explicit `ticket.rollback()` consumes this owner,
after which the normal reaper can clear quarantine.

New production-source JUnit
`LogicalSourceActorProxyRollbackRecoveryTest` includes
an injected corrupt-mailbox retry test and a test that refuses thaw
while a live unpublished candidate is still in its retirement
ledger. A third, end-to-end candidate-timeout test was authored but
its retry loop needs a correction: `reconcileFailedUpgradeRollback()`
may legitimately return false when the *separate* proxy rollback
ticket is still pending, and therefore its boolean result cannot
gate a later `reconcileFailedProxyRollback()` call. Attempts to
commit that specific test correction were blocked by the GitHub
connector's write safety check. **Treat this test as not yet ready for
release/merge, and do not represent it as passing JUnit.**
The real full-source Java/Graal CI has not executed any test steps.

Independent source-free JDK25 evidence:
`gha-indie-worker/gha-indie-worker-e2e` source-free branch
`tmp/oreslang-hot-upgrade-oracle-20261019`, workflow commit
`f879fbfc35acc69abe520f0f3f521d0326be61e7`, GitHub
Actions [run #39352270458](https://github.com/gha-indie-worker/gha-indie-worker-e2e/actions/runs/39352270458),
job `118398945710`, finished SUCCESS. The logged exact output is:
```
PASS source-free proxy rollback assertions=225001
races=5000 workers=4 negative_control=missing_ticket_unsafe
private_source_bytes=0
```
The independent model compiled with Temurin JDK25 under
`javac --release 25 -Xlint:all -Werror`, along with previously
established source-free hot-upgrade, admission and candidate-quarantine
oracles. These checks do NOT compile the private Oreslang compiler,
`ActorRuntime`, proxy mailbox, Graal runtime or Native Image.

Cross-org private-source mirroring remains prohibited while the source
`main` approved-destination file has no uncommented approved
repositories; the reviewed destination is only a comment and
issues #962/#904 remain open. Retain PR DRAFT and production hot
reload OFF pending test correction, authorized exact-head JDK25/Graal
Maven execution, and native parity checks.

## Host recovery for failed post-publication physical stop

The next supervisor audit distinguished two different failure modes.
A retired actor whose `stop()` request **succeeded**, but whose
physical-finalization notification is still pending, can be handled
by `reapRetired()` once its terminal barrier publishes. But a
physical `stop()` that throws **before mutating** the actor's lifecycle
leaves an executable retired actor that may never finish. Passive
finalization polling alone cannot make progress, while releasing its
old code generation or reopening new-route ingress is unsafe.

Production commit `a6ecd23a9f303c8696eb9dc236c812e342ec3df9`
adds trusted-host `LogicalSourceActorRouterV1.retryRetiredPhysicalStops()`.
It snapshots previously published retired Routes and **retirement-eligible**
unpublished Candidates under `routeLock`, explicitly excludes
the open active route and all staging candidates, and attempts their
exact `ActorRef.stop()` outside that monitor. An active publication
transaction rejects retry. Failed independent stops remain registered
for later recovery; successfully finalized actors are debited through
the existing close-before-forget retirement ledger. This method
holds `closeLock` to serialize with router shutdown and retained
rollback reconciliation. It does not re-publish any route,
advance proxy epochs, refund message credits or infer physical
completion from a stop request alone.

Real-runtime JUnit
`LogicalSourceActorRetiredLeaseRecoveryTest.postPublicationFailedStopCanBeRetriedWithoutRevertingRoute`,
commit `916e7e3bf3aed54b98a7e785a6bb304023ed6b89`,
uses a test-only registered actor map that throws a single
`OutOfMemoryError` when the old physical actor's
`ActorRuntime.stop` requests its registry entry. The test verifies
the new route has already committed epoch two, sends remain
quarantined, passive reaping cannot certify terminal completion,
an explicit physical stop retry succeeds, the old generation pin
is debited **once**, quarantine clears, and epoch two remains
authoritative. This is **committed production Java/JUnit source
that has NOT executed** because source-owned Actions has no
recorded executable steps.

Independently authored public protocol model
`RetiredStopRetryOracle.java` ran on funded Temurin JDK25,
source-free commit `c349958a21f51702ebc7fb319c0c99cea8c71948`.
GitHub Actions
https://github.com/gha-indie-worker/gha-indie-worker-e2e/actions/runs/39354517860
(job `118406128388`) completed SUCCESS with
`javac --release 25 -Xlint:all -Werror` and
```
PASS source-free retired stop retry checks=210001
four_worker_races=5000
passive_reap_negative_control=detected private_source_bytes=0
```
This model validates only the abstract recovery protocol and
does not compile, link, or run private Oreslang code.

On the exact code/test head
`916e7e3bf3aed54b98a7e785a6bb304023ed6b89`,
source fast CI `39354397572` and full CI `39354397599`
failed with `steps:null` and `logs_url:null` for every
inspected job. Metadata confirms the private repository is
not archived and the connected GitHub user has admin scope,
but the available connector does **not** expose organization
billing or workflow-runner admission controls. The job-step
endpoint supplies no steps, so there is no evidence Maven/JUnit/
GraalVM executed, and no proof of minutes exhaustion.

Protected source main still has zero uncommented approved
private-source CI destinations. The external organization
listed in policy is review-only and not approved. Security
issue #962 and merge-review issue #904 remain open;
NO cross-organization private source mirroring took place.
The source-free model is supplemental evidence only.
Keep PR #1367 draft and production hot reload disabled
until exact-head real JUnit, Graal Native Image, and
independent security/merge approvals succeed.

## Physical unregister throws after deletion — actor-slot recovery audit

The termination path `ActorRuntime.unregisterActor()` previously
ran `actors.remove(exactId, exactCell)` followed by `actorCount`
debit. A faulting registry can delete the exact actor and then throw
before returning its result. This strands the actor-count slot and
interrupts finalization without publishing the ActorRef's durable
physical-finalization marker. Once absent from the registry, a later
`ActorRef.stop()` may no longer discover that cell for a retry.
Missing registry entries are not by themselves proof of physical
retirement, but the **same finalization owner** can verify the
post-removal state and complete all remaining terminal callbacks.

Production commit `4d614a656eb24a2a44374c5c8e617e54c10ac6c4`
adds an exact per-ActorCell `physicalActorSlotDebited` ownership
claim and checks the registry following a throwing removal.
Only when that precise actor identity is absent can unregister
advance to the once-only slot debit and resume finalization;
if the cell still exists, the original error is propagated and
physical retirement is NOT certified. After a successful
remove-with-throw observation, remaining terminal notifications,
parent unlink and physical-finalization marker still execute.
This does not bypass any other failing cleanup stage.

Real-runtime JUnit
`ActorPhysicalFinalizationPublicationTest.registryRemovalAfterMutationCompletesFinalizationAndDebitsSlotOnce`
was committed in `7e8a5666e306bb1627140909f5e9b6bbb11951c1`.
It injects a trusted-test map whose exact actor removal succeeds
and then throws `OutOfMemoryError`. It checks finished physical
finalization, a removed registry entry, exactly one actor-slot
debit and a repeat stop that cannot debit again.
**The private-source JUnit has NOT executed.**

The separate, independently authored source-free Java 21
`UnregisterFaultOracle.java` compiled locally with
`javac --release 21 -Xlint:all -Werror` and passed three consecutive
runs of **245,001** checks each, including 5,000 four-worker
retirement races, a before-removal failure that retains ownership,
and a negative control that reproduces the old stranded slot.

A separate source-free copy of the model ran successfully on funded
Temurin JDK25 via
https://github.com/gha-indie-worker/gha-indie-worker-e2e/actions/runs/39360160524
(job `118424234678`, public commit
`29865f37264830c0fbe20a1ef47e4144636f386b`).
The log confirms `javac --release 25 -Xlint:all -Werror`
and:
```
PASS independent physical unregister oracle checks=245001
four_worker_races=5000 before_and_after_mutation=verified
old_failure_negative_control=detected private_source_bytes=0
```
No private source was mirrored or supplied to this carrier; this
is protocol-model evidence ONLY and does not certify product Java
classes, Maven/JUnit, GraalVM/Native Image, or LLVM parity.

The protected `main` private CI destination allowlist remains empty
and #962/#904 are unresolved. Source-owned `ci`/`fast-ci` still
fail with jobs recording no steps. Keep PR #1367 draft,
production hot reload disabled, and the exact production test
and source-owned/approved JDK25/Graal runner as release gates.
