# SHARED actor terminal-settlement and blocking-carrier audit — 2026-10-19

**Status: DRAFT / not production-certified.** Exact-head JDK25, GraalVM,
Maven/JUnit, Native Image and authenticated cross-process execution remain
unverified. This audit changes Java/Graal actor-runtime lifecycle code; it
does **not** implement a general source `ActorMail<T>` serializer or
PRIVATE/UNTRUSTED/LLVM migration.

## Finding A — physical finalization could precede receipt teardown

The previous physical-finalization hardening correctly put a durable terminal
marker and latch on `ActorRef`, independent of `actors.get(id)`. A later
audit found a separate ordering gap in `ActorCell.terminateTree()`:
under `lifecycleLock`, it called `finalizeStopLocked()` **before** failing
the exact physical-message receipts, cumulative drain observers and pending
operations snapped for teardown. If the actor had no live turn/children,
`finalizeStopLocked` could mark the ref physically finalized and let the
hot-reload supervisor debit its old code-generation lease even while
termination callbacks and cancellation observers still ran.

### Fix

- Introduce a lifecycle-owned `terminalSettlementPending` reservation after
  all potentially allocating teardown ledger snapshots succeed and before
  stopped/sealed publication.
- A second/reentrant `terminateTree()` cannot take another empty snapshot
  and certify retirement while the first settlement owner still runs.
- `finalizeStopLocked()` refuses physical finalization while the settlement
  reservation is active. Exact physical `STOPPED`/cancellation receipts,
  drain waiters, pending operations and structured child cancellation settle
  only after releasing `lifecycleLock`.
- When all settlement steps return, the original stop owner removes the
  reservation under the lifecycle monitor and attempts full finalization.
  If settlement throws, the reservation stays active: code cannot be
  **falsely certified** retired. Such a failure may require explicit
  supervisor diagnosis/recovery; it is not an automatic retry or guarantee
  of eventual liveness.
- The ref also records the **current settlement thread** so a synchronous
  exact-receipt callback cannot wait on its own physical-finalization latch
  and deadlock. This thread marker is cleared in a nested `finally` even
  if the finalizer itself fails.
- The existing `ActorRef` terminal latch still publishes true only after
  the finalizer's synchronous observer/registry cleanup completes.
  A successful logical route publication is not permission to force-retire
  an unquiescent physical actor.

## Finding B — blocking host authority omitted carrier-only contexts

`ActorRuntime.requireBlockingHostContext` checked active actor turns,
ActorGroup Mailman CONTROL and the active scheduler Task, but did not
reject actor-only carrier, scheduler-only carrier or async-only carrier
markers after their corresponding CURRENT execution contexts were cleared.

Blocking `ActorRef.awaitTermination` on any of those workers risks
deadlocking/starving the very physical runtime that must settle. The
guard now rejects **all six** trusted-host violations, matching the
logical router's carrier boundaries.

`ActorHotUpgradeTaskOwnerV1.requireHost` had the same actor-carrier and
Mailman CONTROL omissions. Closing its canonical `OresTaskScope` can
join physically executing children, so these callbacks must not borrow
host-only TaskScope authority. Its guard now also checks actor carrier
and Mailman CONTROL, in addition to scheduler and async task/carriers.

The changes deliberately preserve ordinary external supervisor calls.
They do not grant caller authority merely because a ThreadLocal marker
was cleared.

## New committed product regression sources (NOT EXECUTED)

- `ActorPhysicalFinalizationPublicationTest`:
  `physicalRetirementCannotPrecedeExactReceiptFailureCallbacks` injects
  one runtime-style pending exact receipt into a ready, idle physical actor.
  During stop, it deliberately blocks the receipt's failure observer,
  verifies the synchronous callback cannot self-join, and asserts an
  external host cannot observe physical retirement or pass the terminal
  latch until callback completion. It verifies `STOPPED` disposition.
  `orphanedCarrierMarkersCannotBlockOnActorFinalization` injects each
  actor/scheduler/async carrier-only ThreadLocal and requires host wait
  rejection; `realSchedulerTurnCannotJoinPhysicalActorFinalization`
  exercises the actual scheduler carrier.
- `ActorHotUpgradeHostCarrierIsolationTest`:
  `taskScopeOwnerCannotJoinChildrenFromActorOrMailmanCarrier` tests
  all three carrier-only markers plus ActorGroup CONTROL against the
  canonical TaskScope supervisor authority.

These tests instantiate actual production classes and APIs, but have
NOT executed on an authorized JDK25/GraalVM CI runner. The receipt
reservation is test-only fault injection, not an accepted user message.

## Executed isolated evidence (NOT product CI)

`/mnt/data/oreslang_terminal_settlement_audit/TerminalSettlementOracle.java`
was independently authored and run with OpenJDK 21:
`javac --release 21 -Xlint:all -Werror`, then Java execution passed
**62,000 / 62,000** modeled ordering checks. Scenarios include 10,000
held terminal callbacks, 250 eight-waiter completion races, and 10,000
terminal-callback error paths that must not claim physical retirement.
This is a **source-free model**, not compilation/execution of the private
`ActorRuntime.java` or the new JUnit tests.

## CI and security hold

At the audited source branch, source-owned `fast-ci` run
`39324570526` and `ci` run `39324570685` failed with every inspected
job reporting `steps: null`. This is insufficient evidence to determine
whether exhausted minutes, runner admission or repository workflow policy
caused the failure. It is **not** a JVM assertion verdict.

The protected `.github/private-ci-approved-repositories.txt` contains
no approved private destination. Security issues #962 (reviewed numeric
destination and exact-tree provenance) and #904 (independent merge review
and exact-SHA checks) remain open. Do not mirror the private tree to a
different organization merely because it has spare Actions minutes.
No cross-organization source transfer occurred for this audit.

Before considering a release candidate, run the exact-tree JDK25/GraalVM/
Maven/Native Image suite covering these regression tests; fault-inject
observer OOME, child cancellation, receipt settlement and postcommit
retirement, and confirm canonical code/checkpoint/Task/route transaction
ownership. Keep PR #1367 DRAFT.


## Follow-up: retryable exact batch was defined but not wired

**Concrete defect fixed during this audit.** The previous source introduced
`TerminalSettlementBatch`, a durable owner for exact physical receipts,
drain waiters, pending operations and child cancellations, together with a
`settleTerminalBatch()` routine that tracks per-category progress.
However, `terminateTree()` still ran its original one-shot terminal loop;
it never created a batch and never called the retry function. A callback
that failed after the Future CAS could strand `terminalSettlementPending`
permanently with no retry path.

The current production source now:

- snapshots all teardown ledgers and allocates the complete
  `TerminalSettlementBatch` **before** publishing stopped/sealed;
- publishes a single batch authority under `lifecycleLock` before
  clearing the ordinary ledgers;
- on concurrent/reentrant stop, never re-snapshots or steals the active
  settlement; on a later supervisor retry, reuses the existing owner;
- invokes `settleTerminalBatch(batch)` *outside* `lifecycleLock`,
  advances each progress index only after the exact Future notification
  drain or child cancellation finishes, and rechecks an already-terminal
  Future without claiming a second terminal disposition;
- retains the batch and physical-retirement fence when any notification
  release fails; only complete batch settlement permits the ordinary
  actor finalizer and durable terminal-latch publication.

A new committed product JUnit,
`ActorPhysicalFinalizationPublicationTest.
failedReceiptNotificationResumesExactBatchWithoutDoubleRefund`, injects two
runtime-shaped physical receipts into an idle actor. The second receipt's
notification-release hook fails once after its terminal CAS. The test
requires the first stop to fail **without** physical retirement, a second
stop to reuse the original batch, exactly one terminal transition per
receipt, exactly one successful notification release per receipt, and
verified eventual finalization.

**Executed independent model:** the standalone
`RetryableSettlementOracle.java` under
`/mnt/data/oreslang_terminal_settlement_audit/` compiled with OpenJDK 21
using `javac --release 21 -Xlint:all -Werror` and passed **933,850 /
933,850** checks, including 100,000 three-receipt retry rounds, 350
eight-waiter concurrency rounds, and 10,000 held-owner failure/retry
rounds. This independently authored state-machine model does **not**
compile `ActorRuntime.java`, validate OresFuture's Graal integration,
run the new JUnit fixture or establish an exact-head JDK25/native pass.

**Limitations still open:** an exception during irreversible mailbox/channel
close *before* the batch reaches the external settlement loop remains
fail-sealed; this audit does not certify automatic recovery for arbitrary
resource-shutdown/OOME failures or distributed exactly-once effects.
The full SHARED actor hot-upgrade supervisor transaction also requires
exact-tree execution before its draft PR can merge.

The inspected source-owned `fast-ci` run `39326400643` and full
`ci` run `39326400706` failed with all inspected jobs reporting
`steps: null`. These runs provide neither successful nor failed Java
assertion evidence. The approved private source mirror destination list
is still empty and security issues #962/#904 remain open, so **no private
cross-organization source mirror was created**. An alternate organization's
GitHub Actions minutes alone cannot bypass reviewed destination approval.
