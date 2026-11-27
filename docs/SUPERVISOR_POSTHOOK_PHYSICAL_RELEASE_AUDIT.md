# Supervisor physical-release ordering: post-hook safety audit

Status: **draft; production-source Java/JUnit and TLA+ checks not yet executed.**

## P0 discovery: ActorGroup retirement can precede `onStopped`

The previous `SupervisorLifecycleFsm.reduce` accepted `PhysicalRelease(epoch)`
whenever `actorsDone=true`, even if the supervisor was in
`BEFORE_HOOK`, `WAIT_CHILDREN`, `WAIT_ACTORS` or `AFTER_HOOK`.

The runtime intentionally invokes `onStopped` **after** all managed actors
retire. That hook may hold, extend or create native I/O / FFI / arena
loans. A physical release receipt observed before the hook settles cannot
prove that the hook has relinquished all of its resources. If the reducer
preserves that early receipt, it can transition directly to `RECLAIMED`
when the hook subsequently finishes without any new resource audit.

This bug is distinct from pre-admission stale receipt reuse fixed in
draft PR #1964.

## Fail-closed correction

The pure FSM accepts a new `PhysicalRelease` only if:

- its epoch matches the current supervisor incarnation;
- `actorsDone` is true; and
- the node is in `STOPPED` (ordinary shutdown: post-hook settled) or
  `RESTARTING` (restart retirement path, with no shutdown post-hook).

`RECLAIMED` duplicates are idempotent, because the release is already
recorded. The canonical immutable `State` constructor also rejects an
externally constructed `physicalReleased=true` state in a phase other
than `RESTARTING`, `STOPPED` or `RECLAIMED`.

The parent still does not transition to `RECLAIMED` until both its local
physical release and the physical release acknowledgments for **all**
frozen children are present. The bit does not prove any real native loan
was returned: no physical receipt producer or authenticated loan ledger
is implemented by this patch.

## Test coverage

`SupervisorLifecycleFsmOracle` exercises:

- reject physical-release attempts during `BEFORE_HOOK`, after early
  actor retirement, and specifically during `AFTER_HOOK` while
  `onStopped` has not settled;
- accept release after `AfterHookFinished` and transition
  `STOPPED -> RECLAIMED` when children are also reclaimed;
- allow the separate restart `RESTARTING` physical retirement barrier;
- reject pre-fence `RUNNING` physical receipts instead of merely
  resetting them on subsequent shutdown admission.

`SupervisorLifecycleFsmIntegrationTest` additionally creates a real
`ActorRuntime`, holds an asynchronous `onStopped` hook pending,
and asserts the FSM stays in `AFTER_HOOK` with `actorsDone=true`,
`physicalReleased=false`, and an unsettled termination Future.
After completing the hook, the node must reach logical `STOPPED`
without falsely claiming `RECLAIMED`. The bounded CONTROL trace
is replayed through the production reducer.

The independent `SupervisorTree.tla` action `ReturnLoan` now requires
a node in `Restarting` or `Stopped` and the new TLC invariant
`NoEarlyPhysicalRelease` rejects zero outstanding local loans during
`Running`, `Pre`, `Children`, `Workers` or `Post`.

A separately implemented local bounded reference model visited **19
states / 26 enabled transitions**, rejecting six early physical-release
attempts with no invariant failures. This is not an exact Java compiler
pass or executed TLC run.

## Security and CI gate

The source repo's `main` is not protected and the approved private CI
destination list contains no approved repositories. Security issue #962
requires independent CODEOWNER/security review, immutable numeric
destination identity and source provenance before any cross-org private
source transfer, even if another org has free Actions minutes. No
private source was mirrored for this audit.

Source GitHub Actions have repeatedly failed with zero job steps
(#963). Require actual exact-head Java 21 FSM oracle, JDK 25
ActorRuntime/JUnit, the sibling and depth-three TLC configurations,
and JVM/GraalVM/LLVM parity before merging. Real runtime physical-loan,
continuation, task-owned lock and arena release receipts remain separate
release gates. Never equate `ActorGroup.closedDone()` with physical
quiescence.
