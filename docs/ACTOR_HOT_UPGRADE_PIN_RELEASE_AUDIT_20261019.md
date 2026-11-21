# Actor hot-upgrade Task/lease audit — October 19, 2026

Status: **DRAFT**. These changes are part of ONE SHARED/PRIVATE/UNTRUSTED supervisor upgrade protocol, not a new hot-reload subsystem.

## Defect fixed: external lease lost on rejected debit

A previous `ActorRuntime.HotUpgradeExternalLease.close()` invoked
`released.compareAndSet(false,true)` before testing whether
`hotUpgradeExternalPins > 0`. A corrupted counter caused the release to
throw **after** irrevocably consuming the lease. That violates the contract
that a failed code-generation debit must be retryable after accounting repair,
and can strand a generation or incorrectly claim retirement under corruption.

The lifecycle monitor now serializes the following order:

1. Return if this lease was already successfully released.
2. Validate that the actor's external pin count is strictly positive.
3. Decrement exactly one pin.
4. Mark the lease released.
5. Notify supervisor drain observers.

A failed validation leaves the same lease live for retry. This is an actor
cell-local accounting fix, **not yet** authenticated per-code-image lease
accounting, compiler-bound TaskScope ownership, or safe native unload.

## Host-only Task admission hardened

`ActorHotUpgradeTaskBridgeV1.startPinned` rejects invocation from active
actor turns, active OresScheduler Task turns, scheduler carrier threads, and
AsyncRuntime guest carriers **before reserving any actor pin**. This blocks an
unscoped guest turn from constructing a detached context-owned Task through
the temporary host proof API. The eventual compiler-owned child Task path
must use actor-bound TaskScope and its explicit authorization, not loosen
these host-only checks.

The existing route/actor lifecycle-lock handshake remains authoritative:
the router holds its route monitor while obtaining the actor pin, the actor
lifecycle monitor linearizes with ingress freeze, and the scheduler releases
the pin on **physical quiescence**, not on result Future settlement.

## Regression sources

- `ActorHotUpgradeExternalLeaseTest.accountingFailureLeavesExternalLeaseRetryable`: existing regression now has the correct implementation behavior.
- `ActorHotUpgradeExternalLeaseRaceTest`: deliberately corrupt accounting, verify a failed close is retryable, race 16 releasers of that same lease, preserve a second independent pin until its owner exits, and verify freeze drain.
- `ActorHotUpgradeTaskBridgeV1Test.executingSchedulerTurnCannotLaunchUnscopedActorTask`: executing scheduler Task cannot obtain a new host-only actor lease; zero leaked pins.
- `LogicalSourceActorProxyBridgeV1Test.realCompiledSharedActorUpgradeWaitsForSuspendedPinnedTask`: real `.ores` SHARED actor refuses source-image publication while its Task generation is suspended, then upgrades with stable logical identity and persistent state after physical exit.

**These JUnit tests are committed but NOT executed** on a full source checkout.
Full JDK25/GraalVM, Maven, guest Task lowering, Native Image, and LLVM
code-image unload tests are still required.

## Executed local isolated JDK21 evidence

Reconstructed exactly the GitHub source blob for the patched
`ActorHotUpgradeTaskBridgeV1.java`, verified
`git hash-object == 85d02503b8cc789443f7223249d2fbfa24fde9a8`,
compiled with `javac --release 21 -Xlint:all -Werror`, and ran:

- **60,000** bridge lease/admission/cancellation probes (stub ActorRuntime and scheduler);
- **15,000** fault-injection/concurrent lease checks over 1,000 rounds
  (equivalent method body in an isolated stand-in; NOT exact entire
  `ActorRuntime.java` runtime execution);
- **50,000** host guard rejection checks against stubbed actor/scheduler/
  async execution markers, each with a zero-leak assertion.

All **125,000 isolated checks PASS**. These are not a GraalVM runtime or
JUnit receipt, and the branch must remain DRAFT.

## Source CI and private export

At exact branch head `5dfea187070e83261ec43dbabd935040091d5321`
(before this document), `fast-ci` run 39288942548 concluded FAILURE,
but its inspected test job 118184207689 recorded **zero executed steps**.
That is **NOT_EXECUTED**, not a failing JUnit assertion or passing build.

P0 incidents `oreslang-platform-conformance#22` and `#77` are OPEN.
Bootstrap `#69` states the approved private source destination and
source-snapshot ledgers remain empty and that protected-branch controls are
not yet complete. A working GitHub Actions runner in another organization
**does not** authorize export. No private source trees, blobs, or dependency
closures were copied to a foreign organization during this audit.

To unlock executable exact-head integration CI, restore a source-owned
runner OR obtain separately reviewed, protected authorization for an exact
source commit+root tree and an exact numeric-ID verified PRIVATE destination.
Do not bypass policy checks with copied Git trees, GitHub connectors, PATs,
or alternate GITHUB_TOKENs.

## Required release work

Wire the single supervisor freeze/commit authority to compiler TaskScope
and per-image continuation/FFI/shared-state leases; add a root-code-generation
lease to every suspended frame. Atomically couple proxy route epoch, scheduler
image publication, checkpoint ABI receipt and loader selection. Only after
physical quiescence may old executable pages be quarantined/unloaded.
PRIVATE worker address spaces and UNTRUSTED sandbox constraints remain
independent per-kind physical admission requirements.
