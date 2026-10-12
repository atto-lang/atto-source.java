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
