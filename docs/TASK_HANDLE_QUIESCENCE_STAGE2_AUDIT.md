# Scheduler-tracked TaskHandle — stage 2 audit

Status: **draft, host API only, stacked on stage-1 PR #1402**.

## Implementation

- `OresScheduler.startTracked(Task<T>)` opts into a new `TaskHandle<T>` without altering the existing `start()` or `OresFuture<T>` ABI.
- `OresScheduler.newTaskScope()` creates an explicit host-only lifetime owner. `scope.startTask(...)` admits owned children; `scope.cancel(reason)` propagates cancellation; `scope.seal()` stops admission without cancelling; `scope.getDone()` joins physical quiescence of all admitted children. This is not actor or source-language scope integration.
- `handle.cancel(reason)` owns a runtime-only `TaskCancellation` authority (stage 1); cancellation of the handle's result Future is permitted to win before the active guest turn exits.
- `handle.getDone()` is a distinct `OresFuture<TaskOutcome<T>>`, whose cancellation admission always returns false. Neither callers nor task cancellation can forge a physical-exit witness.
- The completion barrier waits for **both** the OresScheduler `onQuiescent` callback and terminal result notification, regardless of order.
- Cancellation requested before the underlying Future is bound gets replayed after bind, and the wrapper checks cancellation before every resumed guest step.
- Cancellation outcomes preserve a first reason; external cancellation of the result Future produces a generic cancellation outcome. A guest-thrown `CancellationException` without runtime cancellation remains `Failed`, not silently relabeled `Cancelled`. Cancellation requested after a terminal result returns false.

## Tests and limits

- `src/test/java/dev/oreslang/runtime/TaskHandleTest.java` has **21 scheduler-level JUnit cases** (actual Graal/Maven CI execution still pending).
- Cases cover running guest-turn quiescence, cancellation before dispatch, suspended await, cooperative handoff, terminal success/failure, timeout observation, forged `getDone().cancel()`, cancellation races, reason preservation, admission denial on closed schedulers, repeated observation, cancellation requests after terminal outcomes, and guest-thrown CancellationException being diagnosed as failure.
- `src/test/java/dev/oreslang/runtime/TaskScopeTest.java` adds **9 more JUnit cases** for parent cancellation, multi-child physical join, sealing, admission races, invalid reasons, and scope admission on closed schedulers (30 test cases total, **not yet executed by Maven**).
- A self-contained Java 21 host model compiled successfully, and 300 running cancellation/quiescence iterations plus success and guest-exception classification passed. A separate scope model completed 250 two-child parent-cancellation/join iterations. **The model used a minimal scheduler/Future stub, not OresScheduler's real production source; it does not count as integration evidence.**
- Full source compilation, Java 21 Maven/GraalVM execution, LLVM parity, actor scope join, and source-language Task syntax are NOT verified.
- On some paths `OresFuture.cancel` invokes synchronous host notification callbacks; `TaskHandle.cancel` and `TaskScope.cancel` therefore do **not** currently guarantee bounded wall-time. Do not advertise nonblocking source cancellation until publication is decoupled and tested.
- The quiescence callback is the existing scheduler's guest-turn exit signal; it is not a guarantee about unrelated foreign native workers or asynchronous OS I/O.

## Required follow-up

1. Connect the new **explicit host** `TaskScope` to actual actor/root lifecycle ownership. Add authorized reparenting and failure-supervision policies; these are not included in this stage.
2. Harden `getDone()` and cancellation under source actor close, request-local turns, executor rejection, timed wall deadlines, repeated carrier migration, and teardown notification callbacks.
3. Expose `Task<T>` syntax only after parser/typechecker/lowering and source-level conformance; `do task` must retain supervisor authority even if its handle is dropped.
4. Validate exact PR-head Maven/JUnit and full GraalVM execution on an authorized runner. GitHub source-org Actions have been failing before executing steps.
5. **No cross-org private-source mirroring** before security issue #962's independently reviewed policy/provenance and two-private-repo preflight are enforced. Earlier exposure is recorded in issue #832.

## Merge discipline

PR #1402 is stage 1. This change is stacked on the head of #1402 and must not merge into main before stage 1, real compiler CI, and security approval. It is intentionally a host integration scaffold, **not** a claim of complete cancellable Oreslang source Tasks.


## Second race/retention hardening pass — 2026-10-18

The follow-up audit corrected three independent lifecycle risks:

1. **Completed-child retention:** `TaskCancellation.retire()` now atomically unlinks a physically completed Task token from its previous owner. `TaskHandle.maybePublish()` calls it after observing both the final result and scheduler quiescence, before publishing the final done witness. Thus long-lived `TaskScope` instances no longer retain all successful short-lived child tokens. `TaskScope.liveCancellationChildren()` exposes a package-private diagnostic count for regression tests. The global GRAPH_LOCK remains a scalability limitation, not a production throughput claim.
2. **CancellationException outcome race:** only a cancelled result Future or a runtime-private `CancelledAtResume` exception can produce a Cancelled outcome. An arbitrary guest-thrown `CancellationException` remains Failed even if a cancellation request subsequently arrives. A parent already cancelled before the Task's result Future is bound still produces Cancelled, without executing guest code.
3. **Misleading nonparking guarantee:** `TaskScope.close()` documentation now correctly says it requests cancellation without joining, but host subscribers/revokers can still execute synchronously and delay return.

**Committed regression inventory:** 23 `TaskHandleTest` and 12 `TaskScopeTest` JUnit methods in this stacked PR (35 total); stage 1 #1402 contains 15 cancellation-tree JUnit methods. The new scope tests exercise 500 completed Tasks, completed-child cancellation after scope shutdown, and 200 cancellation-versus-physical-exit races with zero retained owned nodes. The new handle test repeats 100 parent-cancel-before-admission cases and verifies that guest execution never starts.

**Reproducible isolated local evidence:** OpenJDK 21 simplified Java host scheduler/Future harness compiled and passed 2,750 iterations across five model exercises: 300 running cancellation/quiescence, 250 two-child scope shutdown/join, 1,500 completed-child unlinking, 500 cancellation-versus-exit races, and 200 cancelled-parent-before-bind admissions. The harness also checked guest-thrown cancellation exception classification. These are not actual repository Maven/JUnit/GraalVM executions and do not prove production scheduler correctness. The only compiler warning was Java's deprecation-for-removal notice for `ThreadDeath` in the existing stage-1 cancellation code.

**CI and security:** Exact-head source CI still needs a runner that executes steps. A cross-organization source mirror is NOT authorized while private-source publishing issue #962 remains open. Never transfer this private tree to a public, internal, or unapproved private repository; resetting temporary refs does not erase historical Git objects (#832).

**Remaining release blockers:** Real exact-head Java/GraalVM source tests; shutdown-vs-scope-admission; actor/root-owned scope installation and child-failure supervision; authorized cross-scope adoption; true bounded cancellation callback latency if source semantics require it; LLVM/source-conformance parity. Do not merge before verifying these gates.

### Final retention-registration check
Retiring a completed token now also detaches (without invoking) any remaining cancellation/revocation registrations, dropping callbacks retained by external subscription objects. An additional committed JUnit regression checks parent unlinks, no observer delivery after retirement, and safe repeated Subscription.close(). The Java 21 isolated host harness separately passed this case. Full Maven/GraalVM execution remains outstanding.

### Exact-source CI status note
The latest inspected source-org Actions run batch for the audited PR continued reporting failure without job execution. Four sampled jobs (fast-ci/test, Target ABI x86-64/ARM64, native PGO preflight) each reported **0 executed steps**. An Actions workflow conclusion of failure is therefore not evidence of a compiler or regression-test failure here.


## Admission-lock callback hardening — October 18, 2026

The original `TaskScope.startTask()` registered `TaskHandle.getDone()`'s
subscriber **inside** `admissionLock`. Because an already-terminal
`OresFuture.whenCompleteRuntime` can invoke the callback synchronously,
`childDone()` could ultimately complete the scope's externally observable
`done` future while the caller still held `admissionLock`. Arbitrary
observer code could then run under the admission monitor and block other
scope operations, compromising the nonparking scheduler contract.

Correction: reserve `active++` and admit the child under `admissionLock`,
release the monitor, **then** install its `getDone()` subscriber.
A concurrent `seal()` or `cancel()` sees the reservation, so it cannot
prematurely publish the aggregate quiescence witness. If the task already
completed, callback registration runs immediately but outside the monitor.

Added a deterministic scheduler-level JUnit regression
`alreadyTerminalChildNeverPublishesScopeDoneUnderAdmissionLock`. It uses
the existing runtime-owned inline executor (`Runnable::run`) so the task
seals the scope and completes before its terminal subscriber is registered.
The observer checks `Thread.holdsLock(admissionLock)` and demands false.
This test was **committed, not executed under real source CI**.

Exact-head observations from GitHub Actions after the fix:
- fast-ci run 39176109871, job 117818999011: failure, `steps=[]`.
- declaration-integrity run 39176109900, job 117818999083: failure, `steps=[]`.

These are runner/admission failures without executable test steps; the
connector does not establish the underlying billing/policy/runner cause.
The source-org Actions administrator must fix or provide an approved
source-local runner. Never mirror this private source to an unapproved
cross-organization CI repository. Keep this PR stacked on #1402 and draft.

The new **current source count** is 38 JUnit methods (24 in `TaskHandleTest`,
14 in `TaskScopeTest`), plus the stage-1 cancellation tree tests.
Earlier counts in the original audit are historical snapshots, not an
up-to-date passing-test assertion. The added test is committed but not yet
executed by the exact-source JUnit workflow.


### Expanded lock scope correction: scheduling outside admission monitor

Audit found a second path: even after moving terminal-subscriber
registration, `TaskHandle.start(scheduler, ...)` could dispatch the first
guest turn inline under `admissionLock`. The corrected implementation
now reserves one `active` slot atomically with the sealed-state check,
**releases the lock**, then starts the scheduler task and registers
`getDone()` outside it. A rejected scheduler start rolls back the
reserved slot under the same lock and publishes scope completion (when
sealed and empty) only after releasing the monitor.

The inline-scheduler regression now checks **both** guest-turn dispatch
and terminal observer delivery for `Thread.holdsLock(admissionLock) == false`.
A second, controlled executor-blocking regression exercises
`cancel()` during the slot-reserved-but-not-yet-started interval:
cancellation must not wait on a blocked scheduler dispatcher; its child
token is either linked before cancellation or inherits the already
cancelled parent, and the scoped join barrier waits for real quiescence.

Current exact source has 38 JUnit methods in the two stage-2 test files.
Both new regressions are **committed but unexecuted** by source Actions.
This does not establish source-language Task lowering or bounded wall
latency for arbitrary cancellation callbacks.


## Funded alternate Actions runner qualification (source-free only)

An approved *execution-capacity experiment*, not a private-source export,
was performed on 2026-10-18 in private repository
`opto-sync-test/go-java-validation` (GitHub repository ID 1323598177).

- Test branch: `audit/sourcefree-taskscope-canary-20261018`
- Exact branch commit: `c06c67734bd1f5b9f29b4a5cb872b99828e632a5`
- Actions run: https://github.com/opto-sync-test/go-java-validation/actions/runs/39177621239
- Result: **success**. All eight recorded job steps succeeded, including
  checkout, setup-java 21, compilation, and execution.
- Guest-code fidelity: **none**. The 1,750 race cases ran an independently
  written Java model, not Oreslang compiler/runtime source or a complete
  Git snapshot; no private source, Git blobs, tokens, or workflows were
  copied from the Oreslang repository.
- Separate local model: OpenJDK 21 compiled/executed an independently
  reconstructed TaskScope admission model with 3,000 successful cases:
  1,000 inline callback/guest monitor cases, 500 cancellation-vs-dispatch,
  500 rejection/rollback, and 1,000 concurrent seal/start races.
  This is **not actual production JUnit/Maven/GraalVM coverage**.

**Fail-closed export status:** GitHub issue
https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962
still requires protected source provenance, independently approved exact
numeric destination identity and main policy. Conformance security incident
https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22
remains open. Source main has no approved cross-org destination in
`.github/private-ci-approved-repositories.txt`; therefore transferring any
actual private source tree, even into this privately verified carrier, was
**not authorized or performed**. The historical successful five-step
canary and this fresh eight-step canary demonstrate availability only.

To run actual exact-source PR #1407 tests later: clear independent
source/destination approvals, attest protected source main and complete Git
tree, build in approved private destination, obtain exact-head Maven/JUnit
and Graal evidence, then review cleanup of ephemeral refs/artifacts.
Do not equate a canary success with a merge gate.


## Counter-overflow refusal and funded canary v2

The scope's signed `int active` join-slot count is guarded before
increment. At `Integer.MAX_VALUE`, `startTask()` throws
`RejectedExecutionException` **without mutating the count, dispatching
the task, or forging `getDone()`**. This is an overflow-safety invariant,
not a substitute for per-tenant admission quotas.

The new exact-source JUnit test
`TaskScopeTest.saturatedJoinCounterRejectsBeforeSignedOverflow`
injects the boundary reflectively, verifies refusal/no wraparound,
restores its test-only counter, and seals the scope. The stage-2 test
inventory now comprises **39 JUnit methods** (24 TaskHandle, 15 TaskScope),
still **not executed under source-repo Maven CI**.

The independent local Java 21 scope-admission model passed **3,500**
cases including 500 saturation-boundary refusals. The *source-free*
private alternate org runner
[Actions run #39178017326](https://github.com/opto-sync-test/go-java-validation/actions/runs/39178017326)
passed all eight recorded steps under Java 21, with job log
`SOURCE_FREE_CANARY_PASS cases=2250 java=21.0.12.1`.
That build contained **no private Oreslang source code or full tree**;
it tested an independently written concurrency canary and the alternate
organization's funded Actions capability.

Actual source tests are still blocked by existing empty destination
allowlist, source branch protection approval, CODEOWNER/security
independence, and open CI source-exposure incident. Do not treat any
source-free pass as Java/Graal runtime parity or merge evidence.
