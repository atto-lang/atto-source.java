# Gate A — consolidated Task lifetime / Send-Sync verification

Status: **draft**, October 19, 2026. Canonical candidate PR #1552 supersedes overlapping draft approaches #1533 and #1537; the former's latest upstream source code is included through a two-parent merge commit rather than replaced by a competing Task architecture.

## Preserved invariants

- A provisional `OresTask<T>` and independent physical-exit `OresFuture<Void>` are reserved and owned before either `AsyncRuntime` or `OresScheduler` is permitted to execute guest code. Host/guest executor admission is **outside** both `treeAdmissionGate` and `scope.gate`.
- The scope cancellation walk preflights its complete descendant tree under one admission gate and atomically seals every scope before revoking producers. It **revokes all Task producers before** executing potentially blocking/throwing external token unregister callbacks, and performs token detach outside locks.
- A prebound shell records cancellation and the actual completion Future replays it on binding. Logical cancellation does NOT prove physical exit.
- `TaskExit` claims physical retirement exactly once even if terminal callbacks race. Physical fences are settled only after host ownership bookkeeping and/or compiler code-generation lease retirement has been accounted for. Retirement errors fail `getDone()`; they cannot be hidden by normal Future listeners.
- Failed reservations (including an adversarial Set that inserts an owner slot and then throws) abort the unstarted Task and settle or fail its physical-retirement witness before propagating the primary exception. Secondary cleanup failure is suppressed.
- Compiler-resolved scalar captures require `Send`; `NULL`/`VOID` do not acquire worker auto-traits, and a readonly structural projection alone does not prove `Sync`.

## Integration and tests committed

- From #1533: `OresTaskProvisionalAdmissionTest`, `OresTaskRuntimeAdmissionTest`, `OresTaskOrdinaryRetirementTest`, `OresTaskScopeRetirementBookkeepingTest`, and `TaskResolvedSafetyProofTest`, including the delayed-retirement, post-insert rejection and partial-ownership rollback cases.
- From #1537: 64 compiler admission/close trials, 64 host admission/close trials, physical code-pin release tests, nested Send/Sync negatives and scope cancellation failure injection.
- New convergence tests: `OresTaskConvergedAdmissionStressTest` adds 10,000 unbound cancellation/binding/physical-exit checks, 128 double executor-admission races and 128 independently competing physical-retirement callbacks. `OresTaskScopeUnlinkOrderingTest` asserts revocation precedes external-token detachment and no admission monitor is held during detachment.
- `.github/workflows/concurrency-contract.yml` explicitly selects the added JUnit classes along with the preexisting source/guest/runtime proof suite.

## Required acceptance

```sh
mvn -B -ntp -DskipTests -Dores.runtime.carriers=java test-compile
mvn -B -ntp -Dores.runtime.carriers=java \
  -Dsurefire.failIfNoSpecifiedTests=true \
  -Dtest=OresTaskConvergedAdmissionStressTest,OresTaskScopeUnlinkOrderingTest,OresTaskProvisionalAdmissionTest,OresTaskRuntimeAdmissionTest,OresTaskOrdinaryRetirementTest,OresTaskScopeTest,OresTaskScopeFrameTest,OresTaskScopeRetirementBookkeepingTest,OresTaskScopePreAdmissionOwnershipTest,OresTaskScopeFailedSlotCleanupTest,OresTaskScopeCoherentQuiescenceTest,TaskResolvedSafetyProofTest,TaskTraitAdmissionHardeningTest,GuestScalarTaskAwaitTest,ScopedTaskTypeBridgeTest \
  test
```

Then run the complete Maven suite, real positive/negative `.ores` guest programs, GraalVM JDK25, AOT/hybrid native images, relevant LLVM parity, and independent security review against the **identical pinned PR head**. CI must have a real runner, recorded nonzero steps and inspectable test logs.

### Evidence limits and security hold

- Reviewed structural invariants in current GitHub code and verified the alternate organization's [source-free 10,000-case oracle run](https://github.com/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra/actions/runs/39342763759) actually executed and succeeded. **That oracle does not compile this private repo.**
- The exact-head source-org GitHub Actions runs fail with `jobs[].steps=null`, so **none of the new JUnit cases, full Maven suite or native-image tests are verified as passing**.
- Cross-org private-tree mirroring is prohibited until #962's independent source and destination numeric IDs, private visibility, reviewed protected policy, immutable commit/tree proofs and source-write authorization pass. The destination allowlist on source main is empty. Runner capacity is not authorization.
- Source `main` remains reported as `protected:false`. Require protected review/status enforcement before merge.
- Prior PRs #1533/#1537 must not be independently merged on top of this consolidated candidate.

**Do not mark PR #1552 ready or merge it until the missing runner-backed evidence exists.**
