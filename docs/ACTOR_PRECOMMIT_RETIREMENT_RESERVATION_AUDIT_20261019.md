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
