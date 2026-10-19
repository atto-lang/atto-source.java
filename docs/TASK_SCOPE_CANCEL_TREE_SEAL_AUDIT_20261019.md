# TaskScope cancellation tree-sealing audit — 2026-10-19

**Status: draft.** This source audit covers the canonical TaskScope used by
Java SHARED actor hot-upgrade. It is not proof of guest task lowering,
PRIVATE/UNTRUSTED isolation, or executable code unload.

## Confirmed cancellation-order issue

The previous OresTaskScope.cancelTreeIterative() walked descendant scopes
depth-first and revoked each scope's Task Futures immediately after visiting
that scope. The root's synchronous Future cancellation hooks could therefore
run while child/grandchild scope admission was still open. A parent Task hook
could create a new descendant Task during the same shutdown, even though the
root already reported cancellation requested.

## Fix

Canonical OresTaskScope now uses a **two-phase iterative** cancellation walk:

1. Traverse the entire owned scope tree, marking every child closed to new
   admissions and capturing its Task and incoming token-registration owners.
   Scope child creation and Task starts take their scope's own admission lock.
2. After all descendant gates are closed, sever incoming token links, then
   revoke every Task producer using deferred notification.
3. Only after producer revocation has been attempted for every Task, deliver
   synchronous Future completion subscribers. A throwing link/cancel hook no
   longer prevents remaining siblings from being revoked, and already-claimed
   notification closures are still delivered.

This does **not** treat Future cancellation as physical quiescence. Scope
close() continues to join scheduler/async physical-exit witnesses before
retiring old code-generation leases. Unsafe cancellation paths remain
host-only and fail closed.

The parity change was committed on both:
- Hot-upgrade integration Java draft PR #1367, with
  OresTaskScopeCancelTreeAdmissionTest.
- Canonical Task/suspends Java draft PR #1411, with
  OresTaskScopeCancellationOrderingTest.

Both tests use package/reflection-only synthetic owned OresTask handles to
force synchronous cancel-hook reentry and check that child/grandchild
admission is already sealed. A second test verifies that a root Future
subscriber observes complete descendant producer revocation.

## Executed local tests — limited scope

An independently written source-free Java 21 model compiled under
`javac --release 21 -Xlint:all -Werror` and passed:

- 12,000 deterministic root/child/grandchild cancellation rounds,
  **60,000 assertions** validating sealed admission, one-shot cancellation,
  ordered subscriber notification and complete producer revocation;
- 2,000 contested cancellation/admission rounds, with four producers each,
  **8,000 assertions**, covering **8,000** racing admission attempts.

**Total: 68,000 isolated model assertions passed.** This harness is not
the exact OresTaskScope.java, not a full Maven/JUnit/GraalVM test run, and
does not certify runtime integration.

## CI and private source

Both source-owned GitHub Actions fast-ci jobs continue to fail before
executing test steps. Open platform-conformance security issues #22 and #77,
and runner admission/protected-source issue #69, block unauthorized private
Git tree mirroring into another organization even when it has Actions
minutes. No Oreslang private source tree was exported to a test org.

Leave both PRs Draft until exact-head full runtime CI and actor generation,
physical exit, ledger, and atomic publication acceptance tests pass.
