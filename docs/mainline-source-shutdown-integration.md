# Source continuations / actor cancellation current-stack integration

This reconciliation is stacked on the current physical-shutdown + HTTP carrier-quiescence candidate (#1149) at `3dac24bfba85ec560af97036d88523bdd331df92`, itself based on the current-main shutdown barriers in #1139. It forward-ports the independently safe #928/#823 continuation slice without replacing newer shutdown, HTTP, Future-retention, or notification-drain semantics.

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
- **Scheduler-only groundwork at this point in history:** the subsequent authenticated actor STOP candidate is described below. Serial ticket/incarnation proof from #922 remains a separate acceptance gate.

## Actor STOP admission fence

- Trusted source task startup is now atomic with the actor lifecycle STOP seal; already-running actor turns cannot admit fresh user work once STOP has linearized.
- Untrusted source task admission checks STOP both before reserving its bounded logical quota and again while publishing the scheduler task. An aborted admission releases the existing quota reservation.
- The new host-STOP race regression proves rejection happens while the stopped actor still physically owns its active turn. No source guest task is allowed to start.
- These are admission protections; the subsequent authenticated post-STOP cleanup candidate is described below and still requires latest-tree CI.

## Still excluded / mandatory gates

- The older inline critical-state/handle_state fixture was excluded from active Maven tests: current main's actor-state declaration contract differs, and simply changing its spelling would weaken the sealed-state proof. It remains in #823 until its feature-level lowering is ported.
- The branch now contains an actor STOP cleanup/deadline candidate, but authenticated serial ticket/incarnation parity, force-kill race acceptance, and typed parent CONTROL propagation are **not yet fully validated**. Never claim runtime parity from the evaluator/scheduler slice.
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

### Confirmed force cancellation versus an already stopped cleanup actor

- Successful **host-authorized isolation revocation** must revoke suspended cleanup Futures immediately. Calling `terminateTree()` again is insufficient because STOP is idempotent: when the actor is already stopped, that path returns before touching outstanding cleanup tasks.
- The successful-force path now explicitly fences and cancels each snapshot descendant, then closes each stopped actor's source scheduler **outside its lifecycle monitor** before lifting the force-kill fence. No guest cleanup is permitted to run off the actor's lane or after confirmed revocation.
- A failed/false isolation revoker must **not** run this new teardown path: previously accepted lexical cleanup remains live and may finish normally. Tests cover both a successful force-kill with a permanently suspended cleanup Future and a rejected revoker while cleanup is awaiting.
- Actor state and `get_done()` remain guarded by the existing quiescence/children barriers. The force revoker is responsible for genuine atomic outer isolation revocation; the unit-test hook returning true simulates success and does not independently prove hardware/process isolation.
- Newest merge-head exact-source JDK25 and native AOT/hybrid acceptance remains **blocked** until source-org Actions capacity is available or a reviewed, private CI destination is added to `.github/private-ci-approved-repositories.txt`. Previously used `zed-pkg-test/java-app` is public and is not an approved destination; never send additional private source there.


## 2026-10-17 reconciliation status

The #928 patch was replayed against #1149. All evaluator, scheduler and test hunks applied exactly; ActorRuntime and OresFuture conflicts were merged to preserve current physical-exit/HTTP fencing, cancel-admission graph release and exactly-once notification-permit release. Source-org Actions remain zero-step/no-runner, so this document does not claim exact-head Maven, GraalVM Native Image or race-suite execution.
