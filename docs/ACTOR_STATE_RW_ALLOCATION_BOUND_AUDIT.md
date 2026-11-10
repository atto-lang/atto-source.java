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
- Add explicit `maxReaders` (1..65,536) separate from `maxPending`.
  At the cap, new readers queue; `promoteLocked` admits a FIFO prefix up
  to available reader capacity. An older writer remains a barrier to
  later readers, including across cancellation.
- `physicallyRetiredLocked()` also requires the `reserved` authority
  ledger to be empty; no reclaimed actor domain may have a leftover
  reservation even if its other queues look empty.
- A publication callback failure cannot bypass the retirement wakeup check
  after a physical loan's `close()`: use a `try/finally` boundary.
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

The 12 original `ActorStateRwLoansTest` cases were updated to construct
their arbiter with a predefined actor allocation. The resulting file has
**18 authored JUnit cases, not yet executed** on the exact Java head.

## Remaining proof obligations and stop conditions

- Integrate constructor with the **actual** actor-owned state allocator;
  the current class is still a standalone runtime substrate, not language
  syntax or real supervisor state access.
- Authenticated, one-use supervisor delegation (scope, principal, region,
  incarnation, execution, no raw pointer) and per-place/projection alias
  proof in the compiler.
- Couple read/write tickets to protected data operations, not a
  `heldBy()` check followed by an unrelated raw access.
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
- Java compiled guest / Native Image: **NOT_EXECUTED**.
- Local Node `actor-supervisor-region-v1` oracle (separate PR #656):
  44/44 locally executed. This is **source-free** and does not certify the
  Java runtime changes.
- No production lock, exact-head compiler parity or merge authorization
  is claimed. Keep the PR draft.
