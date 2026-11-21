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

The proxy's host-only `ActorHotUpgradeTaskOwnerV1` now owns nested child
scopes, cancels each admitted child before joining, and holds the generation
until `PinnedTask.physicallyExited()` completes. Regression cases cover a
nested child/grandchild whose result Futures have already been cancelled while
their carriers still execute, followed by a strict physical join and successful
source v1→v2 migration. Another case builds **12,000 nested scopes** to
exercise stack-safe quiescence observation and iterative close. The close
reconciliation must remove **OwnershipSlot** entries by their associated
`PinnedTask` identity; removing raw `PinnedTask` values from a
`Set<OwnershipSlot>` was a no-op.

A child scope now captures the authoritative **physical route epoch** at
creation. Start admission verifies that epoch under the same router lock as
the ActorRuntime generation pin; a child from v1 cannot run its captured
frame against v2 after a successful source upgrade. The trusted supervisor
root is epoch-dynamic and can mint a fresh v2 child scope. The stale-scope
regression explicitly rejects both direct children and grandchildren and
then proves a fresh v2 child executes successfully. This is only a host
structural safeguard, not compiler-issued task/code identity attestation.

Closing a scope also prunes fully physically joined descendants **bottom-up**
from their parent's strong ownership set. Unretired/failed children stay
owned and keep code-image quarantine enforceable. The deep-scope and
executing-child regressions assert that closed trees retain zero children;
a long-lived logical root must not accumulate every prior hot-reload scope.

These are JUnit **sources only**, not execution evidence until Maven/JDK25
tests run on the exact Git head. The Tasks are intentionally host frames and
do not claim source-compiled `task {}` exists.

## Known blocked production gates

- **Compiler-owned TaskScope wiring is still absent.** The temporary
  host-only `ActorHotUpgradeTaskOwnerV1` now owns descendant Task pins, but
  does not provide compiler-issued actor/frame affinity. The Task integration
  draft #1411 has `OresTaskScope.startCompilerFrame(..., onPhysicalExit)`.
  Reconcile that API with the actor lifecycle fence instead of shipping two
  competing scope protocols. Do not enable source guest Task lowering until
  captures/actor ownership, cancellation, quotas and unload receipts have
  been proven.
- **Bounded close does not bound arbitrary synchronous Future subscribers.**
  `cancelDeferredNotification()` revokes Task authority first; delivering
  host subscriber callbacks can nevertheless block before the physical join
  deadline starts. This is not permission to unload code while callbacks
  might retain a generation. A production supervisor needs bounded,
  authenticated notification-drain accounting and explicit quarantine.
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


## October 19 — canonical OresTaskScope reconciliation

The SHARED hot-upgrade proof no longer maintains a parallel Task ownership
implementation. Runtime Task primitives from draft #1411 are now present on
this branch, and `ActorHotUpgradeTaskOwnerV1` is reduced to an actor
route-epoch capability wrapper around canonical `OresTaskScope`.

The source-actor router performs one admission transaction under
`routeLock`: validate the expected route epoch, acquire the real
`ActorRuntime.HotUpgradeExternalLease`, then transfer release responsibility
to `OresTaskScope.startCompilerFrame(..., onPhysicalExit)`. A stale child
scope is rejected before pin or scheduler admission. An accepted frame keeps
the exact actor generation pinned until the scheduler's physical-quiescence
callback, never merely until its result Future settles.

The reconciliation also strengthens #1411's ownership allocation order.
`OresTaskScope` preallocates and inserts an ownership slot, physical-exit
Future, Task handle, and retirement observer **before** `OresScheduler.start`.
Allocation/HashSet growth failure invokes the unused generation-release
callback and admits no executable frame. After scheduler admission, publishing
the already-created Task handle is an allocation-free reference assignment.
The existing source-actor OOME regression now faults this canonical ownership
set rather than a separate hot-reload owner set.

This is still a trusted-host/compiler runtime seam. Source `task {}`,
`async task`, and `suspends` execution gates MUST remain closed until the
compiler can attest the lowered frame's logical actor, incarnation, route
epoch/code image, capture ownership and child-scope authority. The old
`ActorHotUpgradeTaskBridgeV1` remains only as a low-level lease/scheduler
fault-test seam and is no longer the structured owner used by the SHARED
proxy bridge.
