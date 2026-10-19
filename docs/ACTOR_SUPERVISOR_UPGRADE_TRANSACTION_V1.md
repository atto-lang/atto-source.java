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
