# TaskScope cancellation preflight and concurrent close audit — 2026-10-19

**Status: DRAFT.** Applies to Java SHARED actor host-only hot-upgrade and
canonical TaskScope/suspends integration. This is not production guest-code
execution certification.

## Concrete issues corrected

1. The previous two-phase cancellation implementation still marked scopes
   cancelled while its depth-first traversal was allocating snapshots of
   later descendants. An OOME from ArrayDeque growth, HashSet iteration or
   Task snapshotting could leave an inconsistent partially sealed subtree.
2. A parent cancellation hook could race a child admission because the
   cancellation walker and childScope()/Task submission used different
   scope-local admission locks, with no common tree-level snapshot fence.
3. After a Future's cancellation CAS, notification-list growth could fail,
   stranding a deferred subscriber notification.
4. close() treated every cancellation exception as a post-seal hook failure.
   A preflight OOME before any seal could therefore permit a physical join
   on a tree still open to new Tasks.
5. beginClose() previously published joining=true before allocating the
   child/Task join snapshots. An allocation failure could strand concurrent
   closers on a never-counted-down join latch.

## Production hardening

- Share treeAdmissionGate across all descendant scopes. Lock order:
  treeAdmissionGate -> each scope.gate. The Task submit/compiler frame and
  child scope creation paths acquire both in this order.
- Preflight the entire cancellation tree under treeAdmissionGate, collecting
  every descendant scope, owned Task and incoming token registration WITHOUT
  changing any cancellation flag.
- Pre-reserve deferred notification capacity before committing any cancellation
  flags. A failed preflight has zero mutation to scope admission status and can
  be retried once resources are available.
- Commit the seal of every descendant under that gate with no allocation or
  callback, then release the gate before detaching links, revoking Task
  producers or delivering subscribers.
- close() refuses to treat a failed allocation preflight as a completed seal.
  beginClose() preallocates its snapshots before claiming exclusive joining.

## Regression tests added

The hot-upgrade branch extends OresTaskScopeCancelTreeAdmissionTest to check:
- Grandchild Task-set iterator OOME during cancellation preflight. Root and all
  descendants remain admitting; restoring the owner allows full retry.
- Racing grandchild admission blocks on the shared tree gate while cancellation
  preflight is deliberately paused; after seal it must reject.
- close() rejects preflight OOME without claiming physical join on open scopes.
- close-frame snapshot OOME leaves joining unclaimed and retryable.

The canonical PR #1411 carries corresponding tests in
OresTaskScopeCancellationOrderingTest, with a compatible variant for its
older Set<OresTask<?>> representation.

The production JUnit sources are COMMITTED BUT NOT EXECUTED in full JDK25,
GraalVM or Maven source tests. Do not mark them green.

## Local isolated protocol checks

An independently authored, source-free Java 21 model built with
`javac --release 21 -Xlint:all -Werror` and executed:

- 10,000 deep snapshot-failure/retry cases,
- 5,000 preflight-close / join-preparation allocation-failure cases,
- 1,500 four-way admission-versus-cancellation races (6,000 attempts).

**95,501 assertions passed.** The model is not the Oreslang source,
does not establish exact production semantics, and cannot replace the
unexecuted runtime JUnit regression suite.

## CI, security and follow-on concerns

- Open Oreslang cross-org source-export incidents
  oreslang-platform-conformance #22 and #77 plus runner bootstrap #69 mean
  alternate funded GitHub Actions minutes DO NOT independently authorize
  copying private source to another organization. No private Oreslang tree
  was mirrored for this audit.
- Canonical PR #1411 still has separate ownership-registration concerns:
  its AsyncRuntime submit path inserts the Task into its Set after admission;
  the hot-upgrade integration branch uses pre-reserved OwnershipSlot records.
  Do not assert full TaskScope parity until this is reconciled and tested.
- The outer source actor upgrade still needs one CONTROL-owned atomic
  image/state/checkpoint/scheduler/route/mailbox publication and proof of
  physical retirement across callbacks, continuations and FFI.
- Keep #1367 and #1411 Draft until exact-head JDK25/Maven/GraalVM CI executes
  and these ownership, admission and retirement invariants pass.
