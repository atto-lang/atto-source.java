# Actor-subtree I/O quota integration — Java/GraalVM

**Status:** DRAFT, UNEXECUTED full-project tests, not a compiled guest or sandbox certification.
Base: `security/physical-io-loan-revocation-gates-20261029` (PR #2292).
Shared source-free reference: [platform conformance PR #246](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/246).

## Actual Java runtime boundaries

- `ActorPermissionScope` has a per-root serialization lock, per-scope maximum,
  aggregate in-flight counter and opaque `IoReservation` ticket.
- A trusted actor turn can call
  `delegatePermissions(RuntimePermissions restrictions, int maxSubtreeIo)`,
  then use the one-shot delegated token to spawn a child. Ordinary delegation
  inherits its parent's cap. Caps are positive integers <= 65,536.
- Every successfully admitted `AsyncNativeIo` operation from an actor turn
  (including nested I/O submitted by its I/O worker) reserves a slot on the
  capturing permission scope and **each** ancestor. The trusted host's
  existing context capacity is still checked separately.
- Both normal worker exit and never-started cancellation release the quota
  **after** work and terminal Future callbacks; executor admission rejection
  rolls it back immediately because no worker was ever accepted.
- Logical completion, cancellation, and actor permission revocation do not
  release an admitted operation's quota. Revocation and reservation serialize
  on the root lock, and the queued worker checks revocation before calling
  guest-requested native I/O.
- Scope counters cannot be affected by actor-supplied numbers or forged
  receipts; only runtime-held references manage the reservation.
- An invalid retirement receipt is recorded as incomplete and quarantines the
  I/O context from subsequent admission.
- Host operations with no actor authority retain the existing host admission
  policy; this patch does not grant any actor host authority.

## Important limitations

1. **Not an OS sandbox.** FFI, native libraries, invalid host imports,
   process isolation and host resource ceilings require independent enforcement.
2. A scope inherited without a separate delegation is shared with its parent,
   so it participates in the parent's aggregate budget. Per-child limits
   require an explicit narrowed delegation.
3. The default 65,536 is a non-widenable upper bound; it is **not** a
   recommended real-time resource allowance. Actual I/O context capacity may
   be much smaller. Host-configured hard limits for every root actor, task,
   mailbox, descriptor and retained buffer still need integration.
4. This counts **admitted native I/O operations**. A physical resource loan
   (`AsyncNativeIo.borrow`) is tracked by the existing actor physical-loan
   gate, but not by a separate dedicated per-subtree loan quota. Resource
   descriptors/bytes require independent limits.
5. Permission revocation alone does not cancel in-progress OS syscalls. This
   version preserves physical ownership through work and callback exit but
   is not a global syscall linearization fence.
6. Actor termination and actor-incarnation liveness must remain enforced
   separately. A permission scope alone is not a complete actor-liveness proof.
7. One per-root monitor can contend under heavy multithreaded admission.
   Measure this before claiming hard-real-time compatibility; later use
   proven bounded shard accounting rather than silently weakening invariants.
8. This stacked draft depends on PR #2292 and its unmerged predecessors.
   Never cherry-pick these files onto `main` without dependency reconciliation.

## Verification order (do not skip)

1. Source/commit readback for this exact head and its stacked base; no mixed
   refs or stale source-tree evidence.
2. Maven JDK 25 targeted tests:
   `-Dtest=ActorSubtreeIoQuotaTest,ActorPermissionRevocationTest,PhysicalIoLoanRevocationTest`.
3. Existing `AsyncNativeIo` shutdown, never-started cancellation, callback
   failure and quiescence tests: preserve original actor-operation accounting
   and close/quarantine behavior.
4. GraalVM 25 Native Image AOT and hybrid integrations on the exact candidate.
5. Compiled Oreslang guest actor permissions and native I/O negative tests:
   delegated 1-slot child, sibling capacity, revocation during execution,
   queued cancellation, post-shutdown denial, stale-incarnation rejection.
6. Cross-backend: LLVM, Wasm, .NET and Forge each enforce the same observable
   quota/retirement model at their actual host-I/O boundary. Merely importing
   JSON or passing a Node model is NOT parity.
7. Independent security review, protected `main`, authorized runner and
   exact-source executed steps before merge or security certification.

Current source-org Actions may fail with zero steps. Such failures are
**admission failures**, not executed regression outcomes. Do not mirror
private source to other organizations without destination-specific approval.
