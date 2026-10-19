# Actor Task hot-upgrade generation pins — integration checkpoint

**Status: draft / trusted-host proof.** This integrates two pieces of the *same*
SHARED actor upgrade protocol, not a new hot-reload mechanism: real
`ActorRuntime.pinExternalWorkForHotUpgrade` admission and
`OresScheduler.start(frame, onQuiescent)` physical execution retirement.
The package-private `ActorHotUpgradeTaskBridgeV1.startPinned` is a temporary
compiler/runtime seam, not an exported Oreslang Task API.

## Required happens-before edges

1. The supervisor's actor lifecycle lock linearizes new external Task pins
   with `freezeUserIngressForHotUpgrade`. A Task that wins first contributes
   to `schedulerDrained() == false`; a freeze that wins rejects new Task pins.
2. A compiler-lowered **new** `task {}` obtains its own pin **before**
   registering with the scheduler. An in-progress `suspends` callback and each
   resumed `await` continuation retain the *existing* TaskRunner pin.
3. The scheduler releases that pin from its **onQuiescent** hook after the
   entered turn/continuation has physically exited. Cancelling or completing
   the result Future is **not** a release receipt.
4. Rejected pre-registration scheduler admission closes the pin. If the
   scheduler admits the Task but initial enqueue fails, its terminal callback
   closes the pin; caller-side fallback close remains idempotent.
5. At the upgrade commit boundary, zero Task/external pins are **necessary**
   but **not sufficient**: the supervisor must also verify mailbox/turn,
   output-reader, FFI, shared-region, device and state-loan quiescence and
   couple physical route publication to the code generation authority.
6. ActorRef logical UUID/incarnation, proxy delivery sequences and epoch
   ownership must remain stable; this Task seam does not change routing.

## Tests committed

`ActorHotUpgradeTaskBridgeV1Test` exercises **actual** ActorRuntime
SHARED freeze admission against a resumable scheduler with:

- a suspended `await` that resumes after freeze with one retained pin;
- cancellation settled while a carrier is still executing;
- rejected Task launch after freeze and rejected scheduler launch;
- foreign-ActorRuntime actor reference refusal;
- two independent Tasks with two outstanding generation pins.

`LogicalSourceActorProxyBridgeV1Test.realCompiledSharedActorUpgradeWaitsForSuspendedPinnedTask`
also exercises a **real source-compiled .ores SHARED actor**: old v1 behavior
reaches 42, an independently resumable host-only Task frame acquires that
actor's generation pin, and a source v1→v2 attempt safely refuses while the
Task is suspended. The old route processes another message to 43. After
Task physical exit, an upgrade succeeds, retaining the stable logical
ActorRef and counter state; v2 behavior produces 45. A new trusted-only
router/proxy hook registers the pin under the exact route lock that guards
actor migration.

These are JUnit **sources only**, not execution evidence until Maven/JDK25
tests run on the exact Git head. The Tasks are intentionally host frames and
do not claim source-compiled `task {}` exists.

## Known blocked production gates

- **No TaskScope wiring yet.** The Task integration draft #1411 has
  `OresTaskScope.startCompilerFrame(..., onPhysicalExit)`, while this actor
  proxy draft is stacked on #1270/#1367. The final integration must have the
  actor-owned TaskScope invoke this same pin lifecycle without ever allowing
  a task to become a detached context-owned sibling. Do not enable source
  guest Task lowering until captures/actor ownership, cancellation and quotas
  have been proven.
- **Generation ownership is incomplete.** The external counter is per actor
  cell, not a cryptographic/compiler-owned code image lease. Code-loader
  unload/retire must hold both physical actor context and *exact image*
  generation until every continuation, FFI callback, shared-region loan,
  native unwind and Task frame has returned.
- **Scheduler affinity is not authenticated here.** The compiler/runtime must
  bind scheduler and frame to the code image, ActorGroup/supervisor and actor
  kind before publishing a guest-callable launch path.
- **PRIVATE/UNTRUSTED** need independently proven protected-worker or sandbox
  placement and image authenticity; host Task pins alone never establish it.
- **Cross-context** SHARED routing in #1270/#1367 currently has a serialized
  bounded proxy bridge, not general guest-visible nonblocking ActorRef routing.
- **LLVM** must produce matching scheduler+image epoch receipts under the
  single publication authority; #627's native scheduler and image commits
  are not yet atomic, and the source .ores→PIC test lacks exact-head CI.

## Exact-head validation

Run on an authorized **source-boundary** runner (no unreviewed private source
mirror to a different org):

```sh
git rev-parse HEAD
mvn -B -ntp -Dtest=ActorHotUpgradeTaskBridgeV1Test,ActorHotUpgradeExternalLeaseTest,LogicalActorProxyMailboxV1Test,LogicalSourceActorProxyBridgeV1Test test
mvn -B -ntp test
```

Then reconcile #1411's `OresTaskScope` with this ActorRuntime bridge and
exercise old-code unload quarantine. Treat zero-step GitHub Actions failures
as **no execution evidence**, not a successful or failed test assertion.
