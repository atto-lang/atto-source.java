# SHARED hot reload — typed Task/image acceptance and physical retirement barrier
**Audit date:** October 19, 2026  
**Status:** DRAFT, exact-source JDK25/Maven/GraalVM JUnit not yet executed.

## P0 actor physical finalization defect and source correction

`ActorRuntime.ActorCell.finalizeStopLocked()` previously calls
`unregisterActor(this)` while the final `getDone()` Future subscribers and
internal `finalizedFuture` observer callbacks have not necessarily finished.
Both `ActorRef.awaitTermination()` and
`ActorRuntime.physicallyFinalizedForHotUpgrade()` treated a missing actor
registry entry as proof of completed physical retirement. A supervisor
observing the gap could release an old-generation code/image pin early.

An internal `ActorCell.finalized = true` bit also begins the
non-reentrant teardown transition before all external notifications. It
must not be conflated with externally certified finalization.

The source now uses an **ActorRef-owned, non-cancellable physical-finalization
marker**. It stays false across runtime registry removal, parent and group
notification, user `getDone()` observers, and internal
`finalizedFuture` observers. It changes to true **only after all of these
synchronous teardown steps return**. The actor reference outlives its
registry entry, so no `actors.get(id)==null` shortcut remains on
`awaitTermination` or `physicallyFinalizedForHotUpgrade`.

`ActorCell.finalized()` and `ActorCell.awaitFinalized()` observe this
final reference marker, while the earlier internal `finalized` bit
continues to fence duplicate shutdown and guest execution.
A synchronous `getDone()` callback executes on the finalizing thread;
calling `awaitTermination()` for its **own** actor would wait on the
callback's own return. The finalizer thread is therefore recorded on its
`ActorRef`, and same-thread physical self-join attempts fail immediately
with `IllegalStateException`. Other host observers remain eligible to
join once the final marker is published. If shutdown
or a cleanup allocation throws before the final marker is set, code
retirement is **not certified**. Such a failure still requires supervisor
diagnosis/repair, not an unconditional unload. Existing ActorGroup
CONTROL/parent notification ordering stays unchanged.

## Committed real-product tests — NOT EXECUTED

`ActorPhysicalFinalizationPublicationTest`:
- Block an actual ActorRuntime `getDone()` callback during teardown,
  then race `physicallyFinalizedForHotUpgrade` and an
  `ActorRef.awaitTermination` waiter. Neither can certify completion
  before the blocked observer exits.
- Verify that a synchronous terminal callback cannot await the finalization
  of the exact actor it is currently finalizing (no same-thread deadlock).
- Temporarily hide an initialized actor's registry record (test-only
  reflection) and prove registry absence alone cannot certify physical
  retirement. Restore the record before stopping the actor and require
  eventual finalization.

`SourceActorTypedTaskImageUpgradeAcceptanceTest`:
- Compile actual SHARED V1/V2 `Counter` actors with the linked compiler's
  `ActorMail<int>` ABI; admit immutable ORM2 frames.
- Process a V1 message to establish persistent actor-owned state.
- Suspend a canonical TaskScope child Task bound to V1, queue two ORM2
  messages, and require hot upgrade to refuse without publishing V2.
- Complete the Task Future, separately wait for physical Task quiescence,
  retry the upgrade, and check one coherent supervisor witness across
  logical identity/incarnation, physical route and mailbox epoch, code
  generation, code hash, state schema hash and message ABI.
- Deliver queued immutable frames under V2; assert that V1 state persists,
  new code semantics execute, and exact physical processing refunds all
  proxy credits.
- Retire V1 generation only after the physical old actor and Task leases
  exit. Verify V2 still accepts and processes new typed mail.

These are authored **JUnit test sources** targeting the real linked
compiler, scheduler, actor runtime and proxy, but no green Maven/JDK25/
GraalVM run has been verified.

## Executed, explicitly independent model evidence

A standalone Java 21 model
`/mnt/data/oreslang_physical_finalization_audit/PhysicalFinalizationOracle.java`
compiled with `javac --release 21 -Xlint:all -Werror` and passed
**315,900/315,900 checks**. It exercises missing registry entries,
post-unregister teardown faults, terminal marker publication and 300
eight-reader concurrent delayed-observer races. It is *not* the
production `ActorRuntime` code and must not be used as its CI receipt.

## Execution and release gates

Source-owned GitHub Actions currently reports failed jobs with **zero
executed steps**; this is NOT a Java/JUnit failure or a pass. An alternate
organization's minutes cannot authorize private source transfer while
`.github/private-ci-approved-repositories.txt` is empty and source
security issue #962 plus required independent review #904 are open.

Next run, on an authorized exact-Git-tree JDK25/GraalVM carrier:

1. `ActorPhysicalFinalizationPublicationTest` with blocked callback,
   slow output/Group cleanup and cleanup-allocation failures.
2. `SourceActorTypedTaskImageUpgradeAcceptanceTest`, existing ORM2 codec
   JUnit, canonical TaskScope/termination race suites, and actual
   Native Image AOT/hybrid.
3. Forced candidate-startup failure, precommit retirement-record OOME,
   postcommit old-actor stop/lease fault, delayed continuation, cancellation
   and physical retirement. Test SHARED first.
4. Introduce the **global** supervisor image/checkpoint/scheduler/Task/
   mailbox/route commit authority; current coherent route/mailbox image
   observation is not full transaction publication.
5. Only after SHARED passes, extend to authenticated protected PRIVATE /
   UNTRUSTED physical workers and LLVM/native image parity.

Keep hot reload PR #1367 **DRAFT** until those gates are satisfied.
