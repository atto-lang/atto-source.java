# Supervisor CONTROL-failure state audit

Status: **draft; exact-head execution pending**, stacked on PR #1964 and the
previous supervisor shutdown/FSM PRs.

## Problem: two independent failure domains shared one bit

The supervisor reducer previously used a single boolean `failed` for
ordinary actor/hook shutdown errors **and** loss of runtime CONTROL
authority. The `CoordinatorFailed` event returned immediately if
`failed=true`; thus an infrastructure failure following a failed guest hook
was swallowed rather than recorded as a new fact.

Worse, `RestartCommitted` intentionally reset `failed=false` after
quiescence to allow normal actor recovery, so it could erase a real CONTROL
fault and advertise a healthy new supervisor incarnation. This is unsafe:
the host cannot re-establish scheduler authority merely by observing actor
retirement and physical-release events.

## Implemented fail-closed rule

`SupervisorLifecycleFsm.State` now has `coordinatorFailed` independently
of the aggregate `failed` bit.

- `CoordinatorFailed(epoch)` always sets both flags and increments the
  transition revision **once**, including after actor or hook failure.
- Duplicate `CoordinatorFailed` is idempotent, not a second effect.
- `RestartRequested` and `RestartCommitted` both reject an outstanding
  coordinator fault. A rejected restart cannot reset the fault.
- New normal `ShutdownRequested` / `ShutdownWithChildEpochs` admissions
  also reject lost CONTROL authority: emergency host cleanup proceeds
  outside guest-hook execution instead. Already-admitted duplicate shutdown
  observations remain idempotent.
- The `State` invariant requires `coordinatorFailed => failed`.
- `admissionsOpen()` and `restartPermitted()` are false if CONTROL
  authority is lost even when the stored phase is RUNNING.
- Ordinary actor/hook errors remain potentially restartable under
  existing policy; the code does **not** conflate them with CONTROL loss.
- The runtime shutdown coordinator's existing failed result aggregation
  still refuses to return a clean `terminated()` future after a stored
  infrastructure error. Logical STOPPED is never equivalent to physical
  RECLAIMED.

Note: disabling the reporting of admissions is **not** itself a memory
barrier that seals actor-group worker admissions. The shutdown/emergency
coordinator must continue to perform the actual runtime admission fence.

## Formal spec and tests

`formal/supervision/SupervisorEpochReceipts.tla` now prohibits both
restart reservation and commit after the model's `failed` bit (which in
that independent model specifically denotes CONTROL failure). The
ordinary guest error channel is still a separate outstanding formal
extension.

`SupervisorLifecycleFsmOracle` now covers:
- coordinator failure in RUNNING and its disabled admissions/restart;
- repeat coordinator failure idempotence;
- failed pre-shutdown hook followed by coordinator failure, preserving
  both distinct facts and incrementing revision;
- actor error during a reserved restart followed by coordinator failure:
  `RestartCommitted` is rejected despite fresh retirement receipts;
- exhaustive bounded exploration checking
  `coordinatorFailed => failed && !admissionsOpen && !restartPermitted`.

`SupervisorLifecycleFsmIntegrationTest` asserts that graceful group
close-initiation fallback, double initiation failure, and failed watchdog
registration record an actual `coordinatorFailed` infrastructure fault.

## CI / safety gates

Source Actions still has a confirmed zero-step admission failure
(#963); exact JDK21 FSM/oracle, JDK25 actor JUnit and all TLC configs
must execute at the immutable PR SHA before merge. Private source
transfer to a different organization remains blocked under #962;
no destination has independent numeric-ID/branch-protection approval.

Remaining distinct gates: full TLA+ trace refinement, physical
native-I/O/task/arena loan release receipts, verified shutdown hooks,
Java/GraalVM and LLVM backend parity, source-side CODEOWNER review.
