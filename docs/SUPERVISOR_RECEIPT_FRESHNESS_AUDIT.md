# Supervisor retirement receipt freshness — admission fence audit

**Status:** draft stacked on PR #1962; not release-verified at its exact head.

## Discovered safety gap

The pure `SupervisorLifecycleFsm` originally admitted
`ActorsFinished(epoch, ...)` and `PhysicalRelease(epoch)` while
`phase == RUNNING`. This is intentionally possible when an actor dies
naturally, but **RUNNING has open worker admissions**: a subsequent actor
can enter the group before a new shutdown or restart admission fence.
Earlier actor/arena retirement evidence may then refer to an older and
smaller worker snapshot.

The old `RestartRequested` and both forms of `ShutdownRequested` kept
`actorsDone` and `physicalReleased`. Therefore a valid old receipt
could satisfy a later `RestartCommitted` or allow an ultimately
`RECLAIMED` tree node **without new proof** for the currently frozen
workload. Epoch equality alone is insufficient because the worker
admission set can change *inside* one supervisor incarnation.

## Correction

The new admission transition clears `actorsDone`, `actorsFailed` and
`physicalReleased` in all three cases:

- `RestartRequested` transitions RUNNING -> RESTARTING.
- `ShutdownRequested` transitions RUNNING/RESTARTING -> BEFORE_HOOK.
- `ShutdownWithChildEpochs` transitions RUNNING/RESTARTING -> BEFORE_HOOK.

Existing receipt order *after* the admission fence is preserved, including
early actor completion while waiting for the pre-shutdown hook or child
acknowledgments. `failed` and `deadlineExpired` are sticky: the new
fence does not turn a failure into success.

A retained duplicate `ShutdownRequested` after admission must have
identical child identities and generations; it **does not** clear
new, already-fenced receipts. Restart completion still requires a *fresh*
matching actor and physical loan event. An already-dead actor may need
the runtime to revalidate and reissue retirement evidence for the
newly admitted snapshot; fail-closed nonprogress is preferable to a
false quiescence claim.

## Verification

- `SupervisorLifecycleFsmOracle` exercises adversarial early
  `ActorsFinished + PhysicalRelease`, then restart and both shutdown
  admission forms. It checks the pre-fence pair is invalidated and that
  a fresh pair is required before `RestartCommitted` or `RECLAIMED`.
- `SupervisorLifecycleTraceOracle` now records restart admission before
  its retirement events. The JUnit regression uses the same ordering.
- Independent `formal/supervision/SupervisorTree.tla` resets
  `workerDone` and `loans` on BeginShutdown and RequestRestart,
  requiring new proof events before either terminal phase.
- An independently coded local **abstraction** (not exact Java source)
  explored 45 states and 58 enabled transitions with no stale-acceptance
  invariant violations. This does not replace Java/TLC exact-head tests.

`ActorGroup.closedDone()` remains a **logical actor-retirement** barrier,
not proof that all FFI/native I/O, continuation, arena, or task-lock leases
are reclaimed. This patch **does not** add a physical receipt producer,
and the supervisor tree must continue to leave RECLAIMED unasserted until
independently verified resource-domain witnesses are integrated.

## Strict test and CI security gates

Run exact-head Java21 bounded reducer + trace oracle; run Java25 targeted
and full actor shutdown tests; run TLC sibling/depth-three plus 2/3 epoch
models. Fault inject extra managed actors added between early DONE and
shutdown fence; assert the shutdown cannot inherit prior physical release.

Source repository Actions are still failing with zero executed steps.
The private CI allowlist on `main` is empty and `main` is not protected.
Security issue #962 requires independent approval before any source can be
mirrored into another organization. No private code was transferred here.
