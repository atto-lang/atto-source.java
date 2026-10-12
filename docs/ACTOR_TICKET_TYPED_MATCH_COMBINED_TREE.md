# Actor ticket + typed value match: combined-tree integration

This integration branch is based on PR #656 and stages PR #661 against their
true common ancestor `4b0ff7dd4063df2906dc2fed0f6b6348b69807c6`.

## Three-way reconciliation

- 66 changed files existed only on the typed-match side and were imported by
  their original Git blob SHA. No file from the actor side was overwritten.
- Seven overlapping Java files had disjoint line hunks (AST, evaluator,
  ActorRuntime, CapabilityChecker, RuntimeGarbageCollector, OwnershipChecker,
  TypeChecker). Both sets of verified diff hunks were retained.
- Three files required manual semantic collision resolution:
  - Lexer: preserve `serial`, `critical`, `using` **and** `suspends`.
  - Token: preserve the same modifiers and typed-match `throw/raise/panic/recover`.
  - Parser: preserve independent `serial` and `suspends` modifier fields,
    duplicate checks, async/suspends exclusivity, mergeModifiers and record
    constructor shape.
- This is a reconciled Git tree, not an assertion that the implementation
  compiles or implements all cancellation/cleanup obligations.

## New executable guest gates

`SourceSerialActorTypedMatchIntegrationTest` exercises actual compiled guest
source through a serial actor receive and `match<ret=int>`:

1. Synchronous typed result: lexical defer must precede output publication.
2. Awaited producer containing `rt cooperate`: source task and actor lease
   must remain bound across suspension, with exactly one cleanup and result.
3. Distinct serial/suspends modifiers: conflicting modifiers fail closed.

The second test is intentionally an integration gate: if the parser/effect
system currently disallows `await` in actor `receive`, treat that failure
as a real integration blocker, not a reason to weaken the test.

## Remaining release-critical gates

- Run the **entire current merge tree** with Maven/JUnit on a correctly funded
  private Actions runner. No successful full-tree build is yet recorded.
- Cancel/stop while the typed match is genuinely suspended. Prove cleanup-only
  guest continuation has narrow actor/task/incarnation authorization.
- Verify a cancelled typed result cannot publish; cleanup failure is retained
  as a failure; each lexical defer/finally executes at most once; a subsequent
  message/restart cannot borrow an old ticket.
- Extend the TLC abstraction with actual runtime-refinement assertions and
  negative mutants, then execute JVM/AOT/hybrid/LLVM supported conformance.
- Keep PRs #656, #661 and #722 draft and unmerged until green evidence exists.

## Fail-stop lock ordering and terminal callback hardening (October 12)

A rejection of the suspended source receive's terminal callback formerly
acquired `cell.lifecycleLock`, cleared the suspended mailbox gate, then
called `cell.fail(...)` *under the same reentrant lock*. Although
`terminateTree` exits its own synchronized block before cancelling Futures,
the outer `synchronized` caller remained locked. Cancellation callbacks that
need a second carrier to enter `lifecycleLock` could deadlock.

The new `failIfRunning` path atomically decides whether the actor is still
running inside `terminateTree`, but invokes that transition without an
outer lifecycle monitor. It leaves `sourceReceiveSuspended` sealed until
termination drains the old receive. A concurrent already-admitted orderly
stop takes precedence over a late failed completion.

Source regressions:
- `ActorSourceCompletionStopLockTest.failStopCancelsFutureWithoutHoldingLifecycleLockAcrossCallback`
  installs a pending producer Future whose cancellation handler must
  acquire `lifecycleLock` from another Java thread.
- `ActorSourceCompletionStopLockTest.lateFailedCompletionCannotPoisonAnAlreadyStoppingActor`
  holds a real actor receive in flight while stop and a stale failure race.

Formal checks:
- `SourceCompletionFailStop.tla` and four configurations: safe model and
  three independent unsafe mutations (early user-mail admission, nested
  callback monitor ownership, late failure cause).
- [Allocated Actions #38267759724](https://github.com/zed-pkg-test/java-app/actions/runs/38267759724)
  **passed**, source Git blobs pinned before execution. TLC explored 11
  distinct states (11 generated) with no invariant violations in the safe
  model. Each unsafe mutation produced the required counterexample.

### Important limits (do not merge yet)

This pass addresses one specific avoidable caller-held lock, **not** all
callbacks during actor finalization. The existing `finalizeStopLocked()` still
calls `sourceScheduler.close()`, completes Future outcomes, and invokes
retirement hooks under the lifecycle monitor. A separate two-phase terminal
publication design and focused reentrancy tests are required before claiming
all finalization callbacks are lock-safe.

Likewise `OresScheduler.TaskRunner.cancelFromFuture()` still immediately
moves a waiting source task to TERMINAL and detaches its active wait; actor
`terminateTree` closes the CONTROL mailbox and cancels outstanding Futures.
There is **no demonstrated cleanup-only guest continuation** that guarantees
`defer/finally` for an abruptly stopped, already-suspended typed match.
The abstract TLC cleanup model is not Java implementation refinement proof.

The new host JUnit tests and combined Graal guest suites have **not been
executed** on this exact integration tree. Source-org Actions jobs still
fail before runner allocation; a safe model does not replace executable
Maven/JVM/native-image evidence.
