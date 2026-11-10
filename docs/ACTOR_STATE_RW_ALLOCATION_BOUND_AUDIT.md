# AttoLang actor-state RW allocation-bound admission (Java/GraalVM draft)

Status: **draft production Java/runtime substrate, not compiler-backed guest functionality**.
Stacked on Java PR #2296 (`feat/local-mutable-state-contract-20261030`).
The base has no claimed full Graal guest or Native Image execution.

## Confirmed audit defect

The original `ActorStateRwLoans` latched `boundAllocation` from the **first
syntactically valid actor execution calling acquireReadAsync/acquireWriteAsync**.
A foreign actor could therefore obtain ownership of a newly constructed,
as-yet-unclaimed arbiter before the intended actor. This is an implicit
first-caller ownership assignment, not a unique-owner proof.

## Changes

- Remove the public unbound constructors. Caller supplies
  `ExecutionContext.AllocationDomain` **at arbiter construction**; it must
  be a bound `ACTOR` domain. An otherwise-valid execution from another
  owner/incarnation is rejected *even as the very first request*.
- In production, this allocation-domain identity must come from a trusted,
  authenticated actor allocation registry, never a guest-supplied record.
  `ExecutionContext.AllocationDomain` itself is a value type and not an
  unforgeable capability. The constructor change **does not independently
  authorize source-language sharing with a supervisor**.
- Add explicit `maxReaders` (1..65,536) separate from `maxPending`
  (now strictly 0..8,192 to prevent an effectively unbounded queued-Future
  retention budget). At the reader cap, new readers queue; `promoteLocked`
  admits a FIFO prefix up to capacity. An older writer remains a barrier to
  later readers, including across cancellation.
- `physicallyRetiredLocked()` also requires the `reserved` authority
  ledger to be empty; no reclaimed actor domain may have a leftover
  reservation even if its other queues look empty.
- **New P0 physical-retirement race:** removing a queued `OresFuture` from
  `pending` on STOP or cancellation does not imply its synchronous runtime
  notifications have returned. A host notification can still be blocked after
  the Future terminal CAS. Track every queued Future through
  `whenNotificationsDrainedRuntime`, including already-cancelled requests,
  and require the `pendingNotificationDrains` counter to reach zero before
  `physicallyRetired()` or the supervisor retirement wakeup succeeds.
  The ledger is registered before any future escapes the monitor.
  These are host-notification receipts, **not** guest compiler proofs.
- A publication callback failure cannot bypass the retirement wakeup check
  after a physical loan's `close()`: use a `try/finally` boundary.
- Reserve the remaining monotonic grant-epoch range for already-queued
  requests, rejecting new acquisitions near `Long.MAX_VALUE`. An overflow
  must not dequeue and strand a pending Future after it was admitted.
- Expose an **observation-only**, generation/region-bound
  `noOutstandingLoanFor(execution)` predicate for tests and scheduler
  diagnostics. It is not a guest capability, a replacement for checked
  state access, or sufficient to authorize suspension on its own.

## JUnit regression cases added

1. Foreign actor cannot claim a free region first; stale incarnation rejected.
2. One-reader cap queues a second reader and retains FIFO writer ordering.
3. Pending queue overflow doesn't leak a reservation.
4. Cancelling a queued reader frees capacity without blocking a writer.
5. Invalid owner kind / unbound region / reader-count bounds fail closed.
6. Released-loan observations cannot authorize a reacquired execution.
7. A publisher already delivering a completion must notify physical retirement
   after the last loan releases and the completion loop drains.
8. STOP cannot reclaim while a failed queued Future's runtime listener is blocked.
9. A cancelled queued Future's callback must drain before STOP finalizes even
   after the cancellation hook removed it from the pending queue.
10. Extremely large pending queue budgets (8,193 / MAX_VALUE) reject; zero
    pending capacity remains valid for no-wait configurations.
11. Revoked STOP actors cannot receive a positive "no outstanding loan"
    observation as a surrogate execution permission.
12. A throwing host runtime subscriber cannot suppress the queued Future's
    physical notification-drain receipt or actor retirement notification.
13. A near-exhausted `long` epoch ledger rejects additional queued requests
    before they can strand a FIFO head at grant time; the final already-queued
    request must still receive epoch `Long.MAX_VALUE` without wrapping.

The 12 original `ActorStateRwLoansTest` cases were updated to construct
their arbiter with a predefined actor allocation. The resulting file has
**25 authored JUnit cases, not yet executed** on the exact Java head.

## Remaining proof obligations and stop conditions

- Integrate constructor with the **actual** actor-owned state allocator;
  the current class is still a standalone runtime substrate, not language
  syntax or real supervisor state access.
- Authenticated, one-use supervisor delegation (scope, principal, region,
  incarnation, execution, no raw pointer) and per-place/projection alias
  proof in the compiler.
- Couple read/write tickets to protected data operations, not a
  `heldBy()` check followed by an unrelated raw access.
- Bind terminal Future notification receipts to actor STOP and cancellation;
  the standalone ledger now covers `Pending` futures only. Full supervisor
  resource/continuation accounting and production `ActorRuntime` integration
  remain outstanding.
- Bound release to physical guest-turn unwind; a foreign
  `StateLoan.close()` may not prematurely unlock another execution's
  state. Prove STOP/quiescence and all cleanup, cancellation, and failure
  paths, including wakeup exception handling.
- Prove fairness and absence of missed wakeups under real Java carrier
  contention; run JUnit, Graal compiled guests and Native Image AOT
  against the exact source HEAD, then LLVM/.NET/Wasm/Forge parity.
- Independently review private-source export authorization and exact
  destination repository ID before any Java source mirror; active
  conformance incident issues #22 and #77 and Java issue #963 prohibit
  unapproved private-source transfer. A private test org with functioning
  runners **is not by itself an authorization**.

## Evidence classifications

- Java/Graal JUnit for this head: **NOT_EXECUTED** until nonzero job steps.
  The dedicated `.github/workflows/actor-state-rw-ownership.yml` checks out
  the immutable pull-request head, asserts its SHA and Git tree, runs
  `-Dtest=ActorStateRwLoansTest -DfailIfNoTests=true`, requires a real
  Surefire XML receipt with >=25 tests and zero failures/errors/skips, and
  uploads its report for independent review. An absent runner / `steps=null`
  remains **NOT_EXECUTED**, regardless of workflow conclusion.
- Java compiled guest / Native Image: **NOT_EXECUTED**.
- Local Node `actor-supervisor-region-v1` oracle (separate PR #656):
  44/44 locally executed. This is **source-free** and does not certify the
  Java runtime changes.
- No production lock, exact-head compiler parity or merge authorization
  is claimed. Keep the PR draft.


## Actual safety guarantee and limits

`noOutstandingLoanFor()` now also returns false once the arbiter is closed:
even an empty ledger must not appear to authorize resuming a revoked actor.
This is **observational only**; it does not replace authenticated scheduler
admission or bind access to protected memory. The active cancellation/STOP
callback ledger applies to `Pending` futures; full actor runtime guest-turn
quiescence must be tracked separately, and no raw supervisor pointer is granted.

**Security authorization:** never mirror this private production Java source
to another organization solely because it has available Actions minutes.
An approved destination repository ID, exact source head/tree, and reviewed
provenance authorization are prerequisites under open incidents #22/#77;
source-free checks may run independently but cannot certify Java guest code.
