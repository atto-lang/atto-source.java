# Source continuations / actor cancellation mainline integration

This branch is intentionally based on Java `main` at `97e28489ece594d6e6649321067b5e449db6494e`, **not** on the older canonical-`using` branch that PR #823 targets.

## Implemented integration slice

- Reconciles six expression-continuation catch/delivery boundaries without replacing newer evaluator code.
- Prevents a downstream continuation failure from being treated as a second invocation failure in `completeSourceInvocation`. Maintains mainline's fast path for already-completed Futures and MutexGuard suspension policy.
- Imports guest-source single-delivery and inline critical-state handoff regressions from PR #823.

## Still excluded / mandatory gates

- Cleanup-aware SourceTask cancellation and actor STOP/deadline/force revocation are **not yet ported**. Never claim runtime parity from this evaluator slice.
- Do not overwrite mainline's wall-clock deadline admission, physical execution lease, blocking subscriber notification lifetime, or Future channel/select arbitration.
- Reconcile PR #906's typed Future select only after this branch passes JDK25 tests on an exact-tree mirror.
- Require actor parent/child CONTROL + wrapped fatal regression, STOP-vs-force-kill deterministic races, full Maven verify, Native Image AOT/hybrid real guest execution before merge.
- LLVM parity is a separate verification target.

This document records deliberate incremental integration, not a claim that PR #823 is ready to merge.
