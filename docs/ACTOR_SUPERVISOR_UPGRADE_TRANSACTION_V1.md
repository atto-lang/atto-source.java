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
