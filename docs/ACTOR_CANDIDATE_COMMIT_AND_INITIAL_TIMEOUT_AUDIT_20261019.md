# SHARED actor candidate publication / startup timeout hardening — 2026-10-19

**Status: DRAFT.** This audits only Java/Graal SHARED host-supervisor boundaries.
It does not certify a globally atomic guest compiler, scheduler, persisted
checkpoint, typed wire, FFI, PRIVATE/UNTRUSTED worker, or LLVM transaction.

## Concrete publication gap fixed

Before this pass, the supervisor transaction:

1. validated the prepublished candidate and committed mailbox/route epochs,
2. set `active = next` and declared the transaction PUBLISHED,
3. then checked `retiredCandidates.remove(candidateRetirement)`, throwing
   if that staging ledger operation failed.

The last step could make the caller observe a failed upgrade after the
irreversible new physical route had already published. Collection mutation
also occurred at a point where fail-closed rollback is no longer legal.

The router now pre-validates exact candidate staging identity, still-owned
lease and physical actor **before** committing the mailbox ticket. Between
successful ticket commit and `active = next`, there is no user callback,
allocation, or throwing invariant check. After the pointer swap, it marks
`candidateRetirement.publishedToRoute = true` by allocation-free assignment.

The published candidate's staging entry is now a nonowning bookkeeping
witness. The supervisor reaper discards it using indexed removal WITHOUT
calling generationLease.close(); that lease is owned by the authoritative
active/retired Route. This avoids both false rollback and double pin debit.

## Real-source JUnit regression additions (NOT EXECUTED)

`LogicalSourceActorRetiredLeaseRecoveryTest` now injects a throw from the
candidate retirement ledger's `remove(Object)` method and verifies that a
source-compiled V1 -> V2 actor upgrade completes, route epoch advances, the
staging witness is harmlessly reaped, and the new active image retains its
generation pin.

`LogicalSourceActorInitialSpawnQuarantineTest` now also tests the ACTUAL
`LogicalSourceActorRouterV1.spawn()` readiness timeout path: it stages and
links a real SHARED source actor, temporarily withholds its physical
dispatcher execution, requests startup with a bounded readiness deadline,
then restores the dispatcher and ensures the failed initial generation pin
is ultimately released only through physical finalization/quarantine retry.
This complements the preexisting synthetic quarantine tests. The test uses
generation identity rather than fresh ActorGenerationView object identity.

These JUnit sources were committed but the full source repo Maven/JDK25/
GraalVM CI has not executed them. The new timeout test is a deterministic
dispatcher-control fixture, but its real runtime outcome has not been
verified in CI yet.

## Executed independent source-free model

`/mnt/data/oreslang_publication_handoff_audit/PublicationHandoffOracle.java`
was compiled and run with `javac --release 21 -Xlint:all -Werror`
under OpenJDK 21. Two independent runs each passed **320,000 assertions**,
including 15,000 deterministic rejected/successful publication and
physical lease retirement cases, plus 500 contested rounds with multiple
coherent route/mailbox observers.

This oracle models the ownership and epoch contract and deliberately
raises an exception for `remove(Object)`. It is NOT compiled from the
actual production router, compiler, ActorRuntime, Maven JUnit, GraalVM,
Native Image or LLVM.

## Confidentiality / release gates

Protected `main` has no approved destinations in
`.github/private-ci-approved-repositories.txt`, and private source
transfer incidents `oreslang-platform-conformance` #22 and #77 together
with protected CI bootstrap #69 / source issue #962 remain unresolved.
A funded alternate org or a temporary Git-blob mirror cannot be treated as
authorized solely because it has Actions minutes. No Oreslang private
source transfer was performed.

Before merging: run exact-head JDK25/Maven/GraalVM integration suite with
executed job steps; exercise the real dispatcher-controlled timeout test;
stress conflicting supervisor upgrades, publication fault injection,
TaskScope physical exit and exact-mail receipt ledger; verify all external
leases and native/private worker backends separately. Keep PR #1367 Draft.
