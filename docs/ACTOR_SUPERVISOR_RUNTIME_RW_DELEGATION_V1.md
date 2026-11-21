# AttoLang actor/supervisor state-region delegation V1 (Java runtime)

**Status: stacked DRAFT; runtime host substrate only.**
This is **not** a compiler borrow-checker proof, source-level `define supervisor`
execution, or an authorization to merge or publish private source.

Base: Java [PR #2758](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2758)
(whose base is #2296). Keep separate for review.

## Ownership and threat model

The allocating actor remains the **sole semantic owner** of every state
region. A supervisor never becomes a second owner; a scoped grant delegates
temporary read or exclusive-write **admission**. It gives neither an actor
pointer, an `ActorRef` lifecycle capability, a direct mutable data view,
nor unvalidated access to foreign actor heaps.

The new runtime-only `ActorStateSupervisorLoans` is created by
`ActorRuntime.createCurrentActorStateLoans()` during a **live physical
owner actor turn**. That factory checks:

- `currentActor` is the actor whose physical carrier turn holds an active
  ownership lease (one active turn, correct carrier, not stopped/sealing).
- The actor has a live **structured managed-worker relationship** to an
  `ActorGroup` and its directly assigned `ActorSupervisor`. Cooperation
  membership or a supervisor ID value alone cannot grant access.
- `ActorRuntime` allocates a unique region identity, derives the actor
  incarnation generation, and constructs a private, **one-use
  `StateRegionMint`**. Constructor rejects caller-invented
  `ExecutionContext.AllocationDomain` values without the matching opaque
  mint. Allocation records are names, not bearer capabilities.
- The older raw `ActorStateRwLoans` constructors are now package-private,
  preventing external callers from creating their own arbiter from an
  arbitrary owner record. The focused arbiter suite includes a regression
  verifying constructor visibility. 
- At most 32 such regions are registered per actor incarnation; the
  underlying RW arbiter bounds pending requests to 8,192 and readers to
  65,536, with configured lower per-region limits.

A grant is a private-constructor object, tracked by identity, and is issued
only inside the original owner actor's turn to its **direct** structured
supervisor. Max 64 outstanding unredeemed grants/region. The supervisor
and managed-group relationship must still hold when the host CONTROL
adapter redeems the grant. Attempted wrong-supervisor or foreign-region
use is rejected without consuming valid authority; the successful
redemption consumes the grant exactly once, even if subsequent RW
admission fails.

## Reader-writer and STOP semantics

All owner and delegated loans share **one** `ActorStateRwLoans` arbiter:

- Many immutable readers may coexist up to `maxReaders`.
- Writers are exclusive. Queued writers block later readers (FIFO).
- An owner callback is guarded by its physical actor execution segment;
  recursive same-region entry cannot impersonate a second reader by minting
  a different synthetic execution identity.
- A host owner callback `withOwnerRead` / `withOwnerWrite` runs entirely
  within a physical actor turn; it cannot park awaiting a contended region.
  The temporary loan is released with try-with-resources even if the
  callback throws. An actor is **not** permitted to return a physical loan
  and release it in a different turn.
- Host supervisor grants may be asynchronously admitted and produce
  scoped `Loan` handles **with no data reference**. Cancelling a pending
  exposed future cancels its physical waiter; a late, undeliverable
  successful loan is closed rather than retained forever.
- Actor STOP first seals descendant admission, then revokes unredeemed
  grants and closes each region's arbiter **outside `ActorCell.lifecycleLock`**
  so a synchronous, potentially blocked Future subscriber cannot delay
  descendant STOP protection or run under the actor lifecycle lock.
  `finalizeStopLocked` directly checks every registered region's
  `physicallyRetired()` before calling the existing external
  `stateLoanRetired` predicate or releasing the actor's memory slice.
  The arbiter's wakeup calls `onStateLoanRetired(actorId)` when the
  final physical loan and queued notification drains finish.
- The original actor incarnation remains bound; restart/actor ID reuse
  cannot redeem an old grant or attach an old region to a new actor.

## Test inventory and non-promotion gate

`ActorStateSupervisorLoansTest` adds **13 authored JUnit cases** covering:

1. Host and unsupervised actor cannot mint region.
2. Foreign supervisor cannot issue an owner-turn grant.
3. Wrong-supervisor/replay and cross-region grant reuse denied.
4. Concurrent readers and FIFO/exclusive writer admission.
5. Actor owner lexical read must fail fast while supervisor writer holds.
6. Owner lexical write failure still releases physical loan.
7. STOP revokes stale grants and keeps DONE pending until supervisor loan closes.
8. Cancelling queued grant does not pin reclamation.
9. Bounded 64 unredeemed tokens.
10. Forged owner allocation record without one-use runtime mint rejected.
11. Two concurrent host claimants cannot redeem the same grant twice.
12. STOP fails queued grants while retaining active physical writer pins.
13. A nested owner read cannot mint a second synthetic execution identity
    to bypass same-physical-turn non-reentrancy.

The focused exact-head workflow runs BOTH this suite and the existing
**26-case `ActorStateRwLoansTest`** (39 authored cases minimum),
requires real Surefire XML with zero failed, errored, or skipped cases,
and uploads the two XML reports. Runner assignment with **nonzero
executed steps** is required. Until that happens classify Java JUnit,
GraalVM compiled guest, and Native Image as **NOT_EXECUTED**.

## Remaining **P0** before source-language production

1. Compiler `OwnershipChecker` / borrow checker must lower typed
   region/projection and lifetime proofs to the *same* runtime arbiter,
   rejecting escaped aliases and suspended mutable loans. No public
   source-level API or FFI should expose `Grant`, `Loan`, allocation
   records, or runtime host methods.
2. Host `requireSupervisorStateHostContext` excludes actor execution and
   Mailman ambient authority; it **does not authenticate a logical
   source-level CONTROL principal**. The actual compiler-lowered
   supervisor identity must be authenticated by the scheduler instead
   of accepting arbitrary host references.
3. `Loan.close()` is a host-trusted release hook, not yet a proof of
   physical guest execution quiescence; compiler/runtime must couple
   release to lexical unwind. A buggy/malicious host may still
   prematurely close a loan it possesses. No raw actor state references
   are permitted through this host-only stage.
4. The actual protected guest-state field read/write operation has
   not been wired to this loan; a host-only toy lock is not proof that
   real state operations obey it.
5. Run exact-head Java JUnit and compiled Graal guests on an admitted
   runner, then Native Image AOT and negative mutations, followed by
   LLVM, .NET NativeAOT, Wasm, Forge parity. Require independent review.

### Private-source testing boundary

Open conformance incidents [#22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22)
and [#77](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/77)
and Java runner issue [#963](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/963)
require independent destination/revision approval **before** any private
Java source export to a separate GitHub organization, even if that
destination is private and has funded Actions. Source-free tests do
not grant authorization for a production Java mirror.

**Do not merge or certify compiler/runtime parity on authored-only tests.**
