# TaskScope external cancellation-link admission audit — October 19, 2026

**Status: DRAFT / real exact-head JDK25 Maven+GraalVM execution pending.**

## Confirmed problem

`OresTaskScope.cancelWhen(token)` previously performed an upstream `token.onCancellationRequested(this::cancel)` registration *before* adding that registration to its owned `externalCancellation` list. If `ArrayList.add` allocated and failed with OOME, the already-registered upstream waiter was neither owned nor detached. A later upstream cancellation could unexpectedly seal the otherwise-live scope; the upstream also retained a reference to it indefinitely. A token that was already cancelled caused `registration.close()` to run inside both the shared TaskScope admission monitor and per-scope monitor.

## Production correction

- Revalidate initial admission under the shared tree admission monitor; install the independent upstream observer **outside** TaskScope monitors because registration can invoke the callback inline on an already-cancelled token.
- Serialize final ownership with `treeAdmissionGate -> gate`, check that the scope is still live, and only report ownership once `externalCancellation.add` returns normally.
- If list growth/insertion fails, detach that exact upstream observer, preserve the original failure and suppress a different detach failure.
- If cancellation has won the race before ownership, detach the unowned observer *outside* both locks; never leave a token retaining this scope.
- No change to physical Task completion semantics, exact mail receipts, or actor code-generation lease authority.

The production `OresTaskScope.java` changes were committed identically to both:
- SHARED source hot-upgrade integration draft PR #1367
- Canonical Task/suspends draft PR #1411

Both branches have `OresTaskScopeCancelLinkAdmissionTest.java`, with host-only reflection fault injection on the link ledger and an already-cancelled upstream token regression.

## Actually executed locally

An **independent isolated Java 21 model** was compiled using
`javac --release 21 -Xlint:all -Werror` and executed successfully:

- 12,000 injected listener-list OOME cases; no orphan subscribers, no later unintended cancellation
- 12,000 already-cancelled upstream token cases; immediate child cancellation and zero retained subscriptions
- 8,000 ordinary successful subscription/cancellation/cleanup cases
- 2,000 two-platform-thread registration/cancellation races

**Result: 110,000 passing model assertions across 34,000 scenarios.**

The local model does *not* load production `OresTaskScope.java`; committed JUnit tests were authored but **not executed**. No JDK25/GraalVM/AOT or LLVM evidence is claimed.

## CI/private source

Source-owned Actions currently fail at runner/job admission (observed `test` jobs with 0 executed steps); zero-step failure is **not** an Oreslang test verdict or necessarily a budget exhaustion verdict. The source repository's `.github/private-ci-approved-repositories.txt` on protected `main` contains no approved private transfer destinations. Source-export incidents `oreslang-platform-conformance` #22 and #77, controls #69, and `oreslang-source.java` #962 remain unresolved. **No private Git tree was mirrored into another organization.**

Keep both PRs **DRAFT** pending executable exact-tree JDK25/Maven/GraalVM integration, cancellation/actor physical-exit conformance, and verified code/scheduler/heap/mailbox/route publication under one supervisor authority.
