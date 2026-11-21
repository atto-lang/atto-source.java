# SHARED actor hot-upgrade old-generation retirement reservation audit — 2026-10-19

**Status: DRAFT.** This is a narrow precommit and physical retirement
hardening pass. It does not complete one globally atomic scheduler, compiler,
checkpoint and routing transaction; PRIVATE/UNTRUSTED workers and LLVM image
unloading remain unverified.

## Confirmed bug

The logical router previously inserted the old Route into retiredRoutes
*only if* the old actor termination timed out or stopped with an error.
On the ordinary successful physical termination path, the code called
prior.generationLease.close() without a preexisting retirement record.

An injected or corrupted generation pin counter can make close() throw
after route and mailbox epoch publication. The irreversible publication
already succeeded, but the old generation was then absent from the reaping
registry, preventing the supervisor from retrying its code pin retirement.
Retired-route list allocation during a postcommit failure was also unsafe
under OOME.

## Implemented transaction hardening

- Under routeLock, prepare the next immutable Route and **reserve the old
  Route's retirement record before committing the proxy freeze ticket**.
  List growth or OOME happens while the old route remains authoritative.
- The route and mailbox epoch commit still use routeLock -> mailbox lock.
  If prepublication commit rejects, drop the reserved retirement record.
  No user code, allocation or throwing call occurs between a successful
  proxy ticket commit and active = next.
- After successful publication, the old retirement record remains owned
  through retirement failure, timeout and code-generation lease close failure.
  No physical/code generation is declared retired merely because the logical
  route pointer moved.
- After awaitTermination succeeds, independently verify physical
  ActorRuntime finalization, then close the generation lease under routeLock,
  and **only then** remove the retired record.
- The existing supervisor reapRetired path retries an outstanding failed
  lease release and removes the record only on success.
- A postcommit failure never silently republishes an old physical route.

## Production regression (committed; NOT EXECUTED)

LogicalSourceActorRetiredLeaseRecoveryTest includes two real-source
SHARED actor generation failure cases:

1. Inject old-generation route-pin accounting underflow after initial spawn.
   Expect a postcommit release error, then restore the counter and require
   reapRetired() to release exactly one still-owned old generation, preserve
   the published route epoch, and reject a replay.
2. Inject an OOME into retirement-list add() before committing a candidate
   route. Require the old route epoch to remain authoritative, no retirement
   record to be published, and a later upgrade to succeed after repairing the
   faulted list.

This test has not been executed by source-owned Maven/JDK25/GraalVM CI.

## Executed isolated protocol model (not production runtime)

A separately authored source-free Java 21 transaction oracle at
/mnt/data/oreslang_atomic_retirement_audit/AtomicRetirementOracle.java
compiled with javac --release 21 -Xlint:all -Werror and passed twice:

- 20,000 deterministic injected precommit OOME / rejected freeze ticket /
  postcommit pin-debit failure / retry / subsequent successful upgrade
  cases per run;
- 500 concurrent reader-versus-upgrade races per run, with all observations
  code-route/mailbox epoch coherent;
- **261,500 assertions per run**, no failures.

This independent harness exercises the modeled commit algorithm; it is not
the actual Oreslang source compiler, ActorRuntime, HotReloadManager, JUnit,
GraalVM or LLVM. Do not treat it as full-stack test certification.

## Outstanding release gates

Exact-head source-owned JDK25/Maven/GraalVM tests and bounded actor upgrade
chaos must execute, not merely schedule. Existing GitHub Actions jobs still
fail with zero steps. Private-source cross-org mirroring is not approved
while platform-conformance security issues #22/#77, runner bootstrap #69,
and source export #962 remain outstanding.

The existing SupervisorPublication observation is coherent under routeLock,
but is explicitly NOT the global authority required to publish scheduler
continuation identity, compiled code image, persisted checkpoint/state,
typed message ABI, and authenticated external-resource retirement in one
transaction. That separate implementation and exact-head tests remain P0.

Keep hot-upgrade PR #1367 Draft.


## Follow-up: unpublished candidate retirement ownership is pre-reserved

A second allocation-order bug existed on the rollback side. Once a candidate
physical actor had been spawned, a failed/ambiguous cleanup path created a new
`Route` and grew `retiredRoutes` only after `stop()` or the physical
finalization probe had already failed. If that emergency allocation itself
threw OOME, the code correctly refused to close the candidate generation pin,
but the retryable owner record could be lost. That prevents premature unload
but converts bounded quarantine into an unowned permanent pin.

The router now has a dedicated `CandidateRetirement` owner ledger. The owner
object and ledger slot are allocated under `routeLock` **before any candidate
physical actor is spawned**. The physical ActorRef is filled into that existing
owner immediately after spawn. Therefore every execution-capable unpublished
candidate is either:

1. still owned by its pre-reserved candidate-retirement record;
2. proven physically finalized, after which the owner is removed and the
   originating upgrade path releases the generation lease; or
3. successfully published, in which case lease ownership transfers to the new
   active `Route` and the unpublished-candidate owner is removed under the same
   publication monitor.

No cleanup path allocates a new retirement `Route` or grows a retirement
ledger after physical candidate admission. Finalization-probe exceptions and
stop failures simply retain the existing owner. `reapRetired()` and router
`close()` now include the candidate-retirement ledger and close the generation
lease before forgetting its owner.

Committed regression source covers:

- candidate-owner ledger OOME before physical admission: route epoch stays old,
  the candidate generation pin is released, and a repaired retry may upgrade;
- a candidate dispatcher that accepts but never executes the initial schedule:
  physical spawn occurs, ready times out, cleanup retains exactly one candidate
  owner and one generation pin, a delayed stale schedule is inert, and
  `reapRetired()` releases the pin exactly once after physical finalization.

These JUnit tests are authored source only until an approved exact-tree runner
executes them.


### Staging owners are not retirement-eligible

A follow-up race audit found that pre-reserving the owner alone was not enough.
Host observations such as `routeEpoch()` and `address()` opportunistically
call `reapRetiredLocked()`. While an upgrade is staging, a candidate physical
actor can terminate before the upgrade thread has observed readiness/failure.
If the generic reaper consumed that owner immediately, the candidate generation
lease could be closed while the upgrade thread still retained publication
authority.

`CandidateRetirement` now carries an explicit `retirementEligible` bit.
It is false from pre-reservation through the entire staging/publication window.
The reaper skips such entries even if their physical actor is already finalized.
Only rollback/failed cleanup, after publication is definitively abandoned,
marks the owner retirement-eligible. Successful publication instead transfers
the lease to the new active `Route` and removes the staging owner while holding
the same route monitor.

The black-hole candidate regression now runs upgrade on a separate thread,
waits until physical admission, proves the owner is still staging, then calls
both `routeEpoch()` (which opportunistically reaps) and `reapRetired()`.
Neither may release the candidate pin. After readiness timeout, the same owner
must become retirement-eligible, survive with exactly one pin, and be reaped
exactly once after delayed physical finalization.

A public, source-free JDK25 oracle at commit
`f4f62e5241185ccbfe5098f1eee04a7d87151b9a` executed this ownership state
machine in draft PR #15 on Temurin 25.0.4+1 with
`javac --release 25 -Xlint:all -Werror`. It passed 20,000 deterministic
candidate-owner rounds, 2,000 candidate-owner races, and the explicit
`candidate_staging_reap_gate=pass` check, alongside the existing route,
terminal-receipt, runtime-close, exact-envelope and send-vs-requeue models.
The run printed `private_source_bytes=0`. This is model evidence only, not
execution of the private production source or JUnit suite.
