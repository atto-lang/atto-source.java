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

## Actor STOP admission fence

- Trusted source task startup is now atomic with the actor lifecycle STOP seal; already-running actor turns cannot admit fresh user work once STOP has linearized.
- Untrusted source task admission checks STOP both before reserving its bounded logical quota and again while publishing the scheduler task. An aborted admission releases the existing quota reservation.
- The new host-STOP race regression proves rejection happens while the stopped actor still physically owns its active turn. No source guest task is allowed to start.
- These are **admission protections only**. Authenticated post-STOP lexical cleanup continuations remain a separate integration gate.

## Still excluded / mandatory gates

- The older inline critical-state/handle_state fixture was excluded from active Maven tests: current main's actor-state declaration contract differs, and simply changing its spelling would weaken the sealed-state proof. It remains in #823 until its feature-level lowering is ported.
- Actor STOP authorization, bounded actor cleanup deadline, force-revocation and typed supervisor propagation are **not yet ported**. Never claim runtime parity from this evaluator slice.
- Do not overwrite mainline's wall-clock deadline admission, physical execution lease, blocking subscriber notification lifetime, or Future channel/select arbitration.
- Reconcile PR #906's typed Future select only after this branch passes JDK25 tests on an exact-tree mirror.
- Require actor parent/child CONTROL + wrapped fatal regression, STOP-vs-force-kill deterministic races, full Maven verify, Native Image AOT/hybrid real guest execution before merge.
- LLVM parity is a separate verification target.

This document records deliberate incremental integration, not a claim that PR #823 is ready to merge.

## Authenticated actor STOP cleanup integration candidate

- A scheduler-created `CleanupTurn` carries an immutable owning `OresScheduler` reference, a monotonic guest-source-entered bit and task-local deferred-cancellation authorization. Guest code cannot mint the envelope.
- Actor lifecycle STOP seals new task admission, atomically classifies queued CONTROL mail and retains only same-scheduler entered lexical frames. New user messages and ordinary continuations are rejected.
- Active cleanup tasks hold the actor state/finalization barrier until deferred Future settlement and physical actor turn exit, with bounded 2-second deadline revocation on a host timer (no off-lane guest execution).
- Late await producer settlement and fatal `CompletionException(LinkageError)` precedence are covered by actor-owned runtime tests.
- This is a **mainline integration candidate**, not a merged behavior contract until exact-tree JVM and native tests pass. PR #922 serial execution ticket/actor-incarnation proof and force-isolate parity remain distinct acceptance gates.

### Deadline revocation hardening

- `expireSourceCleanup()` guarantees lifecycle finalization inspection even if the scheduler's close path fails, while the physical actor carrier exit remains the immutable resource-release barrier.
- Actor-level deadline regression parks cleanup on a never-completing Future, waits for bounded revocation and confirms the abandoned waiter cannot replay guest code after finalization.
