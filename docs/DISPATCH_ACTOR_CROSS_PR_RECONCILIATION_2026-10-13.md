# Dispatch, value-match and actor continuation integration gate

Status: DRAFT / **NOT verified or merge-ready**, 2026-10-13.
Source PR: [Java #709](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/709).
Current source revision must be taken from the PR head, **not from this document**.

## Contract to preserve

| Construct | Owner of completion | Required semantics |
| --- | --- | --- |
| `return match ... over ... end` | nearest typed `ValueMatchExpr` for arm returns, then the enclosing callable return | typed `ARM_RESULT` is consumed only by its owning lexical match frame; a direct `return match` may infer a result contract from its callable exactly once |
| `match<ret=T>` expression assigned to a binding | nearest typed `ValueMatchExpr` | producing a value does not exit an enclosing callable |
| blocking `do select` | surrounding callable | an arm's `ReturnStmt` produces `FUNCTION_RETURN`; it must survive both immediate and resumed channel completion |
| `do match`, `do switch` | surrounding callable | procedural dispatch must not reinterpret returns as `ARM_RESULT` |
| detached `do nb select` | detached callback | no nonlocal return, no live actor-loan escape |
| `return switch`, static `return select` | not lowered here | fail closed until typed result continuations are implemented |
| `do select` nested inside a typed match arm | not lowered here | parser knows the distinction, but the typechecker MUST reject with a targeted diagnostic until `SourceValueCont` can faithfully transport a nonlocal `FUNCTION_RETURN` through suspension, cleanup, and cancellation |

**Important:** The AST parsing regression for nested `do select` is **not evidence of runtime support**. Do not simply permit `SelectStmt` in `verifyTypedValueArm` or convert its `RETURN` to `ARM_RESULT`. The synchronous evaluator currently rejects a nonlocal `ReturnSignal` from a value arm and the resumable evaluator rejects `SourceFlowKind.RETURN`; implementing this feature requires an end-to-end flow representation and all cleanup/cancellation exits.

## Cross-PR reconciliation queue (source-head references as of 2026-10-13)

1. **[#823](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/823)** — head `c3c29390232964bff3217b70af353497ee918e47`. Six file paths overlap #709, especially `OresEvalRootNode`, `ActorRuntime`, and `OresScheduler`. #709 has selectively ported single-publication and invocation/cast/index/assignment fixes, but **not** the new terminal failure policy, cleanup-aware SourceTask integration, or all actor-runtime/future cancellation changes. #823's critical-state invocation uses explicit read/write `CriticalMode`, whereas #709 still has a different critical callback contract. Never wholesale replace #709's evaluator, typed `ARM_RESULT` frames, or the state-loan admission contract.
2. **[#886](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/886)** — head `57ab5316ccc520b9ffef0b434d68dedc55ad32b1`. Seven paths overlap #709 (parser, AST, typing, ownership, runtime). Blocking `do select` must preserve `FUNCTION_RETURN` and lexical loop control. Add the combined nested typed-result/selected-return tests only after a real flow-lowering implementation.
3. **[#892](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/892)** — head `85b6fa442e73e2444146853dca228f5a8174b402`. Ten paths overlap #709. Retain nominal Symbol patterns (never String coercion), checked visibility, inert imports, compile-time constants and their ABI invalidation along with typed `ValueMatchExpr` parsing and actor control. #892 currently rejects value-dispatch forms not lowered there; do not import that blanket rejection into #709.
4. **[#722](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/722)** — head `264ea64a69e44000e569ac6d49e7dff24d0c5df1`. Large, overlapping predecessor integration (many files); compare provenance and retain unique work, not a wholesale overwriting merge.

## Applied incremental hardening on #709

- Single completion instead of replaying a continuation after its consumer throws, including the root expression evaluator.
- Separate failure detection from downstream publication for invocations, immediate/completed awaits, channel read/write, dynamic selection, destructuring and scalar evaluations.
- Consume inferred direct-`return match` result type once; nested subject matches cannot inherit the outer callable result contract.
- New focused regressions: nested typed-match parsing and fail-closed procedural selection, completed channel read and nested awaited invocation.
- The workflow `.github/workflows/typed-dispatch-actor-gate.yml` checks exact checkout SHA, PR-head ancestry on pull-request merge-checkouts, and emits Git-tree and SHA-256 source manifests; it invokes targeted and full JVM tests. **A configured gate is not successful verification**.

## Outstanding correctness and security proof obligations

- Model `FUNCTION_RETURN`, `ARM_RESULT`, `THROW`, actor cancellation, fatal host errors, and (where necessary) terminal quota exhaustion in one explicit flow lattice. Cleanup must run exactly once, preserve authoritative failure priority, cap suppressed diagnostic allocation, and reject stale task-generation publications. Maintain the extra `matchFrameId`/`ownerTask` ownership from #709 when introducing the terminal failure rules from #823.
- Prove `do select` on an arm running inside typed value dispatch either legitimately returns from its enclosing function (after all relevant lexical defers/finally) or is statically rejected. Never accidentally convert it to arm-local completion or an ordinary catchable guest error.
- Preserve state-loan retirement before scheduler handoff, including read vs write criticality and callback lexical restrictions. Test actor stop/restart vs late/duplicate Future notifications, mailbox saturation, restart generation fencing, cancellation, and actor-private mutable data isolation.
- Reconcile `Symbol` nominal identity with typed dispatch, ownership, import visibility and the LLVM supported subset.
- Confirm the complete merged tree and original test fixtures, including file modes and source hashes. Partial CI mirrors cannot validate this.

## Required executable verification

On an authorized exact-tree GraalVM JDK25 runner:

```sh
mvn -B -ntp -DskipTests test
mvn -B -ntp -Dtest=TypedDispatchReturnIsolationTest,SerialTypedValueMatchActorExecutionTest,SourceContinuationSingleDeliveryTest,DoSelectReturnScopeTest,ActorContinuationTeardownTest,ActorSuspensionTicketAtomicityTest,TypedValueMatchCleanupPrecedenceTest,SourceActorEndCleanupExecutionTest -DfailIfNoTests=true test
mvn -B -ntp -Dores.runtime.carriers=native -Dtest=TypedDispatchReturnIsolationTest,SerialTypedValueMatchActorExecutionTest,SourceContinuationSingleDeliveryTest,DoSelectReturnScopeTest,ActorContinuationTeardownTest,ActorSuspensionTicketAtomicityTest,TypedValueMatchCleanupPrecedenceTest,SourceActorEndCleanupExecutionTest -DfailIfNoTests=true test
mvn -B -ntp verify
```

Then perform Native Image AOT/hybrid guest execution; execute the supported LLVM fixture subset on its own exact tree; run TLC safe and unsafe-mutant models; inspect executed steps, JUnit XML, tree hash, source manifests and race evidence.

**CI blocker:** [issue #589](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/589), with zero-step source-org Actions failures. There is no known exact-current-head successful run. The existing PR #709 is still draft, nonmergeable against its configured base and **must not be merged** until those conditions change.
