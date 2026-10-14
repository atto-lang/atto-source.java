# Source continuations / actor cancellation mainline integration

This branch is intentionally based on Java `main` at `97e28489ece594d6e6649321067b5e449db6494e`, **not** on the older canonical-`using` branch that PR #823 targets.

## Implemented integration slice

- Reconciles six expression-continuation catch/delivery boundaries without replacing newer evaluator code.
- Prevents a downstream continuation failure from being treated as a second invocation failure in `completeSourceInvocation`. Maintains mainline's fast path for already-completed Futures and MutexGuard suspension policy.
- Imports and adapts guest-source single-delivery regressions from PR #823. Six recoverable failure tests now use closed-channel read instead of terminal Option.unwrap(None) panic.

## Scheduler-level lexical cleanup follow-up

- Adds trusted `OresScheduler.CleanupAwareTask<T>` source-task metadata and deferred Future cancellation, with fatal/quota precedence and bounded ordinary-failure diagnostics.
- Cancellation is delivered to an already-entered frame on its existing scheduler lane; async cleanup awaits remain live until cleanup concludes, and the external Future only publishes after physical guest turn exit.
- Reconciles current-main scheduler owner lease, wall-clock deadline admission, waiter notification drain, and `OresFuture` channel/select cancellation behavior.
- A closed scheduler revokes parked cleanup and producer waiters without running guest code off-lane.
- New race regressions cover async cleanup and late producers, wrapped-fatal precedence, scheduler-close revocation, and deferred cancel-vs-settlement arbitration.
- **This is NOT yet actor STOP authorization.** The post-STOP actor CONTROL mailbox remains sealed; actor-lifecycle cleanup authentication/timeout and serial tickets from #922 require separate integration and tests.

## Still excluded / mandatory gates

- The older inline critical-state/handle_state fixture was excluded from active Maven tests: current main's actor-state declaration contract differs, and simply changing its spelling would weaken the sealed-state proof. It remains in #823 until its feature-level lowering is ported.
- Actor STOP authorization, bounded actor cleanup deadline, force-revocation and typed supervisor propagation are **not yet ported**. Never claim runtime parity from this evaluator slice.
- Do not overwrite mainline's wall-clock deadline admission, physical execution lease, blocking subscriber notification lifetime, or Future channel/select arbitration.
- Reconcile PR #906's typed Future select only after this branch passes JDK25 tests on an exact-tree mirror.
- Require actor parent/child CONTROL + wrapped fatal regression, STOP-vs-force-kill deterministic races, full Maven verify, Native Image AOT/hybrid real guest execution before merge.
- LLVM parity is a separate verification target.

This document records deliberate incremental integration, not a claim that PR #823 is ready to merge.
