# Bounded actor proxy: terminal seal/admission audit — 2026-10-19

Status: **DRAFT**, SHARED source-actor host/supervisor-only prototype.

## Concrete race fixed

Previously `LogicalSourceActorProxyBridgeV1` used volatile
`poisoned = true` during a failed physical dispatch, incomplete upgrade,
or inconsistent route-epoch snapshot. A concurrently executing
`admitLong()` might have already passed the old `poisoned == false`
test while holding `admissionLock`; the unrelated volatile poison store
could become visible **before** the producer completed its admission.

The terminal failure fence now acquires `admissionLock` before publishing
`poisoned = true`, exactly like `admitLong()` uses for checking/opening
bounded mailbox admission. The lock order is `dispatchLock` then
`admissionLock` for both close and terminal poison. A concurrent
admission is therefore entirely before the terminal seal or rejected
entirely after it; the supervisor does not publish a terminal failure
while leaving a pre-checking producer free to enqueue afterward.

The `address()`, `snapshot()`, `publication()` and package-only
route-epoch diagnostic are also restricted to trusted host contexts
before taking their blocking proxy/route monitors. Existing writer
entrypoints already reject scheduler terminal carriers and
AsyncRuntime virtual-thread carriers; these previously uncovered
**read-only monitor paths** now have the same host-only check.

## Committed runtime regressions

`LogicalSourceActorProxyBridgeV1Test` includes:

- `terminalUpgradeFailureCannotPublishPoisonAheadOfAnAdmissionLockHolder`:
  hold `admissionLock`, make an invalid same-generation upgrade fail
  on a different thread, observe that poisoning is blocked until the
  admission fence releases, then verify terminal rejection and no mail.
- `schedulerTerminalCarrierCannotBlockOnProxyEpochOrAdmissionMonitors`:
  verify the scheduler `onQuiescent` callback, where current Task is
  null but carrier identity remains, cannot enter read-only or admission
  proxy operations.
- `asyncCarrierCannotBlockOnProxyReadOrWriteMonitors`: verify
  AsyncRuntime carrier cannot acquire those same proxy monitors.

These are real-runtime **test sources**, but they have **not executed in
the full GraalVM/JDK25 Maven test suite**. Do not call them passing.

## Locally executed isolated Java 21 evidence

Independently authored, source-free Java concurrency oracle:
`javac --release 21 -Xlint:all -Werror` passed, followed by

- **1,000 deterministic rounds / 7,000 assertions** holding the
  admission monitor against concurrent terminal poison and checking
  post-seal rejection;
- **500 six-contender pressure rounds**, competing one poisoner and
  five admitters and checking the same invariant.

**Total: 7,500 / 7,500 isolated checks passed.** The oracle models
the reviewed monitor protocol; it is **not** a claim that the full
production Oreslang ActorRuntime executed.

At source commit `a733364812011df90cc692635eab25f001c5977a`,
9/9 GitHub-file structural assertions were true for the exact
production blobs: source
`4fe9526463fe566bd3dafb1b9733b4df03b99145`,
JUnit
`1fa1f2db45129a6c11a920a6b6fe02c66d84a8ae`.

Latest inspected source-owned `fast-ci` run 39302415215 failed
with **zero executed steps**; status is `NOT_EXECUTED`, not a
passing/failing test verdict.

## Source export / CI hold

`oreslang-platform-conformance` security incidents #22 and #77
remain open. Bootstrap #69 still requires protected reviewed
source/destination authorization and functioning runner admission
before copying any private Oreslang Git tree to another organization.
No source-bearing cross-org mirror was created during this audit.
Runner budget alone does not constitute export authority.

## Remaining release gates

- Run the three source JUnit regressions on the exact reviewed head
  using a source-owned runner or a separately approved private carrier.
- Integrate canonical `OresTaskScope` and per-image compiler Task
  affinity; replace temporary host-only Task ownership.
- Make code-image/scheduler/checkpoint/actor route publication one
  global supervisor commit, not only a proxy-level coherent epoch.
- Verify physical executable retirement under failure, cancellation,
  watchdog and real JNI/device callback loads.
- Implement protected PRIVATE and sandboxed UNTRUSTED migration.
