# Exact-incarnation native-I/O admission fence

**Status:** implementation candidate on a stacked Java/GraalVM draft PR,
not executed compiler tests, not an OS-level security sandbox.

Depends on Java actor-subtree quota PR #2323, which itself stacks over
physical native-loan revocation PR #2292 and its permission-hardening ancestors.

## Security finding

An actor's resource-permission scope may be shared by children. Revoking that
scope on child STOP is incorrect: the parent or a live sibling could lose its
legitimate authority. Conversely, leaving the scope active allowed a stale
native-I/O worker to keep the resource permission identity of a terminated
actor and attempt new admission.

## Runtime change

`ActorRuntime.NativeIoAuthority` captures an exact `ActorCell` object
identity together with actor ID, runtime and permission scope. It never looks
up an unrelated replacement actor incarnation by a user-supplied ID.

- `reserveIo(operation)` checks runtime closed state and actor
  `stopped`, `sealing`, `finalized`, `forceKillFenced`, and registry
  identity under the captured actor's `lifecycleLock`, then reserves
  the ancestor-permission-scope quota while that lock is held.
- STOP sets `stopped` under the same lifecycle lock, preventing a
  new reservation from linearizing after STOP.
- `requireActive(operation)`, used before a queued worker starts its
  callable and by `borrow`/`register`, also checks exact actor liveness.
- Named-resource checks, network-listener authority, and actor-turn
  physical resource admission independently reject stopped incarnations.
  A still-live sibling is not revoked merely because one actor stopped.
- The lock sequence for new admission is
  `AsyncNativeIo monitor -> ActorCell.lifecycleLock -> scope quota lock`.
  No native-worker continuation should invoke arbitrary callbacks with
  both lifecycle and quota monitors held.
- STOP does not close a previously admitted quota ticket. The existing
  worker/callback retirement receipts are responsible for that release;
  logical cancellation is not physical quiescence.
- This does not revoke the shared permission scope of a sibling.

## Verification

Targeted exact-head JDK25 tests:
`ActorNativeIoIncarnationFenceTest,ActorSubtreeIoQuotaTest,ActorPermissionRevocationTest,PhysicalIoLoanRevocationTest`.

Required adversarial cases:

1. An actor's captured authority works before STOP and fails after STOP.
2. Stopping one actor must not deny unrelated live sibling authority.
3. Self-STOP fences new submissions within the same physical actor turn.
4. Runtime shutdown invalidates retained actor-scoped authority.
5. Previously admitted real operations can retire without quota underflow,
   once-only, even when the actor has logically stopped.
6. Queued I/O that begins after actor STOP must reject before `work.call()`.
7. Test STOP/admit races under dozens of physical worker schedules, with
   completed+cancelled receipts and bounded finalization.
8. Verify no lock cycle with `ActorCell.finalizeStopLocked`,
   `AsyncNativeIo` close, callbacks and the physical-loan retirement gate.
9. Run GraalVM Native Image AOT and hybrid; then compiled Oreslang guest
   negatives and equivalent behavior on LLVM, Wasm, .NET NativeAOT and Forge.

Four JUnit methods now cover cases 1–5 and a real already-running worker
attempting nested I/O after actor STOP (part of case 6). These tests are
checked in but **NOT_EXECUTED**. Broader randomized race scheduling, complete
callback/lock-order validation, GraalVM AOT/hybrid and compiled guest/backend
proof remain release blockers.

**Evidence discipline:** zero-step Actions are pre-runner/admission
failures, not test failures or passes. No private source may leave its
organization for an alternate runner without destination-specific reviewed
authorization. Host Java source checks alone do not establish sandboxing.
