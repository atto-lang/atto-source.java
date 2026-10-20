# Gate A — Post-merge Task hardening audit

2026-10-19/20 · **Draft** · canonical PR #1570

## Upstream convergence

- PR #1533 was merged into #1484 at `b36d4fb62635723c440277a02bb2af21eaa063f7`. PR #1484 was subsequently merged into parent feature branch `feat/scalar-task-frame-proof-20261019`.
- This candidate is based on current `feat/guest-scalar-task-await-20261019` at `b25b839760ede2b01722b20602ecb5fae79786e5`, preserving upstream's later `OwnershipSlot.registered` safeguard for sealed-scope rejection and insert-then-throw owner-set failures.
- Do **not** merge the obsolete pre-upstream-hardening PR #1552; it predates these upstream changes and is currently conflicting.

## Hardened invariants

1. **Tree cancellation:** Plan and seal the entire descendant tree under `treeAdmissionGate` before callbacks; request cancellation of all owned Task producers **before** any external token-unregister callback. Deferred Future subscribers remain notified only after revocation, outside ownership locks.
2. **Token cleanup:** `cancelWhen()` unregisters a token after exiting both `treeAdmissionGate` and the scope's `gate`. Token close is arbitrary host code and cannot execute underneath an owner monitor.
3. **Physical retirement:** `AsyncRuntime.TaskExit.markExited()` claims a single retirement turn using `AtomicBoolean.compareAndSet(false,true)`. Competing cancellation, pre-dispatch rejection and carrier-unwind paths cannot execute a trusted retirement hook twice. Quiescence does not publish success before trusted cleanup; failures remain visible to `getDone()`.
4. **Upstream safety:** Preserve the canonical `TaskAdmission` owner identity, exactly-once hook installation, complete physical owner slot before executable dispatch, admission allocation rollback, terminal-map retirement ordering and the sealed/unregistered-slot distinction.
5. **Send/Sync:** Keep compiler-resolved scalar capture `Send` validation and the `NULL`/`VOID` and readonly-record Sync negative proof cases.

## Newly required regression tests

- `OresTaskConvergedAdmissionStressTest` — 10,000 provisional cancellation/replay/exit checks; 128 duplicate submission races; 128 competing physical-retirement invocations.
- `OresTaskScopeUnlinkOrderingTest` — Task producer revoked before token detach; no ownership monitor held during token unregister.
- Reinstated `OresTaskScopeFrameTest` tests (compiler code pin and 64 admission-close trials), `OresTaskScopeTest` tests (host admission-close 64 trials, token failure, deferred turn entry), `TaskTraitAdmissionHardeningTest` (readonly record and null sentinel).
- Preserve new upstream `AsyncRuntimeAdversarialAdmissionTest`, `OresTaskScopeAdmissionStressTest` (600 iterations), and the `OresTaskOrdinaryRetirementTest` suite that checks sealed-scope and partial-insertion rollback.
- `.github/workflows/concurrency-contract.yml` explicitly selects restored and new cases, using `-Dsurefire.failIfNoSpecifiedTests=true`.

## Exact-head acceptance commands (authorized source runner)

```sh
mvn -B -ntp -DskipTests -Dores.runtime.carriers=java test-compile
mvn -B -ntp -Dores.runtime.carriers=java \
  -Dsurefire.failIfNoSpecifiedTests=true \
  -Dtest=OresTaskConvergedAdmissionStressTest,OresTaskScopeUnlinkOrderingTest,OresTaskScopeTest,OresTaskScopeFrameTest,OresTaskProvisionalAdmissionTest,OresTaskScopePreAdmissionOwnershipTest,OresTaskScopeFailedSlotCleanupTest,OresTaskOrdinaryRetirementTest,OresTaskScopeRetirementBookkeepingTest,OresTaskRuntimeAdmissionTest,AsyncRuntimeAdversarialAdmissionTest,OresTaskScopeAdmissionStressTest,TaskResolvedSafetyProofTest,TaskTraitAdmissionHardeningTest,GuestScalarTaskAwaitTest \
  test
```

After this focused suite, run complete JDK25/GraalVM Maven tests, source positive/negative `.ores` guest programs, AOT/hybrid native-image jobs, physical lease/unload tests and relevant LLVM parity.

## Actual execution evidence and unresolved blockers

- GitHub inspected source-level code and structural invariants for this candidate. **That is not a source compile or JUnit execution.**
- Alternate private org's [source-free Java 21 oracle run #39347366669](https://github.com/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra/actions/runs/39347366669) executed strict Java21 compilation, 10,000 standalone model races, 725,760 bounded admission-retirement schedules, and 25,000 hook/admission races successfully. This **does not include the Oreslang private Git tree**.
- Source-org exact-head Actions [concurrency #39348356706](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39348356706) failed without a runner name or recorded job steps. Do **not** claim Maven/JUnit passed or failed.
- Source `main` private export allowlist has **zero approved entries**; security issue [#962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962) remains open. No private Git-object mirror was made. Spare Actions budget elsewhere does not authorize code export. Require independent immutable source SHA/tree and destination numeric-ID/private-visibility approval from trusted policy.
- Review status, required checks and branch-protection enforcement must be independently verified prior to merge. **Keep the PR draft.**


## October 20 follow-up: unregistered compiler slot and source-local retry

- Compiler `startCompilerFrame` now sets `OwnershipSlot.registered` after
  successful insertion. On admission failure, it only attempts rollback if
  registration succeeded or the owner collection reports an insert-then-throw
  slot. Sealed-scope rejection no longer triggers false slot-removal errors;
  the acquired code-generation pin still retires exactly once outside locks.
- `OresTaskScopeFrameTest` adds
  `sealedScopeRejectsCompilerFrameWithoutUnregisteredOwnerRemoval` and
  `postInsertCompilerSlotFailureStillRetiresPinAndOwner`; both use adversarial
  owner-set behavior. These tests are **committed but have not executed**.
- A targeted retry of source-org concurrency job `118397513826` was
  accepted. GitHub [run #39351813629](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39351813629)
  attempt 2 again failed with `jobs[].steps = null` and no runner. The
  original job log download returned missing log blob. This is evidence
  of pre-run failure, NOT a JUnit failure, and it does not identify a
  particular billing or Actions policy cause.
- GitHub check-run records did indicate annotations for failed jobs, but
  the current GitHub connection cannot read the annotations URL.
  Repository administrators should inspect these in the GitHub Actions UI.
- Cross-org transfer remains blocked by #962; destination policy still has
  zero independently approved source-export destinations. Do not mirror
  any private source tree just because another organization has runners.

