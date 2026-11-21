# ActorRuntime constructor publication and failed-spawn rollback audit — 2026-10-19

**Status: draft / exact-head JDK25 Maven/GraalVM JUnit not executed.**
This is a narrow Java ActorRuntime lifecycle fix. It does not authorize
PRIVATE/UNTRUSTED worker migration, live source-code publication, or native
executable image retirement.

## Concrete failure boundary

`ActorRuntime.spawnInternal` reserves the actor-count slot before constructing
the new `ActorCell`. Until the constructor returns, the local `cell`
variable is null; its ordinary `abortUnregisteredConstruction` path cannot
recover resources whose constructor itself threw.

Before this audit `ActorCell` created its output stream, assigned it to
the ActorRef and inserted it into the runtime-wide `actorOutputStreams`
registry **before** constructing its remaining `ActorMemorySlice`. If that
later allocation failed, or if registry insertion partially succeeded and
then raised a runtime exception or `Error`, the constructor threw without
an ActorCell returned to `spawnInternal`. The output stream could remain
registered and no supervisor-owned ActorRef existed to retire it.

Independently, the outer spawn rollback caught exceptions from
`abortUnregisteredConstruction` only when they were
`RuntimeException`, not `Error`. A fatal cleanup error could hide the
original admission failure and bypass its actor-slot decrement.

## Fix implemented

- Construct the per-actor memory-slice object before publishing
  `outputStream` in the runtime-wide registry.
- Surround the stream registration with `catch (RuntimeException | Error)`.
  On a failure **including add-then-throw**, close/drain the stream and
  remove it from the registry. Attempt to close a created memory slice
  separately, suppressing cleanup errors on the original failure.
- Preserve the exact primary spawn-admission error even if registry/actor
  cleanup also throws. Use an outer `finally` to refund the reserved
  `actorCount` slot on failed admission.
- No executable actor is admitted by this fix. Successful ordinary spawns
  retain their existing output stream, physical ref and lifecycle behavior.

This closes one allocation/registry publication hole, not a general
out-of-memory recovery guarantee: catastrophic JVM failure or an
unrecoverable registry remove fault can still prevent process-level
reclamation. Such cases must not be mistaken for physical termination
or validated code-image unload.

## Committed real JUnit source — NOT EXECUTED

`ActorRuntimeSpawnConstructionAtomicityTest` uses test-only reflection to
replace the runtime's private stream-registry Set with a forwarding Set
whose `add` first inserts the real output stream, then throws an exact
`OutOfMemoryError`. It asserts:
- the same original Error is rethrown;
- no output stream remains in the runtime registry;
- the reserved actor slot returns to zero;
- when `remove` also throws **after removal**, its Error is suppressed on
  the original failure rather than replacing it;
- a normal subsequent SHARED actor spawns, becomes ready and physically
  terminates without actor-count leakage.

The test fixture uses an actual ActorRuntime in JUnit source, but it
has not executed on source-owned Java/JUnit CI.

## Supplemental Java 21 independent model (not production code)

A standalone, independently authored model at
`/mnt/data/oreslang_spawn_atomicity_audit/SpawnAtomicityOracle.java`
compiled with `javac --release 21 -Xlint:all -Werror` and passed
**170,003 / 170,003 checks** in **two consecutive executions**.
Each run covered:
- 20,000 add-then-OOME failures with zero orphan streams and slots;
- 20,000 successful recoveries/retirements;
- 10,000 prepublication failures with no registry publication;
- 5,000 errors during cleanup after registry removal, preserving primary
  failure and refunding admission count;
- 24,000 healthy parallel spawns/retirements across eight threads.

This is *model* evidence only, not production ActorRuntime class compilation,
actual product JUnit, JDK25 Maven, GraalVM, Native Image, or LLVM tests.

## CI/privacy and release gates

Source-owned GitHub Actions are failing with zero executed job steps,
not providing JVM assertion or Native Image pass/fail evidence.
The private mirror destination allowlist remains empty and repository
security issue #962 plus independent merge-review issue #904 remain
unresolved. Merely locating another organization with unused Actions
minutes does not authorize copying the private Git tree. No cross-org
source transfer occurred during this audit.

**Next:** run the committed JUnit and existing physical-finalization,
candidate-upgrade, failed-initial-spawn, ORM2 and TaskScope integration
suites on an authorized exact-head JDK25/GraalVM carrier. Inject further
post-registration spawn faults, physical cancellation failure, and
before/after-publication faults. Keep hot-reload PR #1367 draft until
full-stack evidence exists.
