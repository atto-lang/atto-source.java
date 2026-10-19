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


## Stage-1 callback integrity and blocked-rejection join race

This stacked branch carries the same fatal cancellation-dispatch fix
as parent PR #1402. A crashing trusted revoker previously left later
revokers undelivered and the cancellation observer barrier permanently
pending. The corrected batch preserves error propagation *after*
running other admitted hooks; its iterative dispatcher drains already
queued nested cancellations. The parent must be reviewed first.

Additional stage-2 JUnit case
`rejectedDispatcherRacingScopeCancellationNeverPublishesPrematureJoin`
targets the reserved child slot while scheduler dispatch blocks, then
fails. Scope cancellation cannot publish `getDone()` until that slot
is retired; any failed/Cancelled terminal outcome is acceptable, but
an unexecuted guest may never be classified as successful.

Exact Git blob `TaskScope.java` (`211b5812183e9e35f7a9d7358b1a6f2ccebd024b`)
compiled on local Java 21 with independently authored test doubles and
passed **3,500** targeted cases, including 350 cancellation-vs-rejected
dispatch races. The original exact `TaskCancellation.java` reproduced
the fatal barrier defect; patched local primitive tests passed **2,400**
cases. **None of this executes actual production OresScheduler/OresFuture
or GraalVM**, and no private source was copied to an alternate GitHub
organization. Stage-1 suite now contains **17** committed JUnit methods;
stage-2 has **40** (24 TaskHandle + 16 TaskScope); total **57**
committed methods, not yet source-CI verified.

The stage-2 Git branch is still based on the previous stage-1 head.
Review and merge the newly hardened stage-1 changes first, then rebase
stage 2 and resolve any duplicate-cherry-pick conflicts before release.


## Retired cancellation authority admission hardening

The stage-2 `TaskCancellation.retire()` primitive originally detached
existing registrations but left the retired token accepting late
`onCancel`/`onRevoke` registrations, new children, transfers into the
retired owner, and even late `cancel()` transitions. A retained late
subscription could keep a completed Task's callback captures alive.

A guarded `retired` state now closes the authority under `GRAPH_LOCK`.
Further registration returns an already-closed subscription; new
children/adoptions are rejected; cancellation after retirement is a
no-op; and retirement is idempotent. An owner holding **live child
authorities** cannot retire because that would orphan structured
cancellation. These are internal host-lifetime semantics, not guest
cancellation permissions or guaranteed scheduler quiescence.

Four newly committed stage-2 `TaskCancellationTest` JUnit methods
exercise registration after retirement, adoption/spawn rejection,
retirement-versus-registration races, and orphan-prevention for live
children. Thus the staged branch contains **61 JUnit methods**
(21 cancellation, 24 TaskHandle, 16 TaskScope). Stage-1 contributes
17; the 21 stage-2 cancellation methods include those inherited
17 and four new methods. Never double-count them as 78.

Local Java 21 testing, not GraalVM/Maven CI:
- An independently adapted runtime-primitive source harness passed
  2,400 cancellation cases and 2,000 retirement/race cases (**4,400**
  total). The checked production `TaskScope.java` Git blob passed
  3,500 checks with scheduler/Future doubles; these are not
  compiler/runtime integration tests.
- A completely source-free independent lifetime model ran under
  private `opto-sync-test/go-java-validation` Actions
  [#39181767823](https://github.com/opto-sync-test/go-java-validation/actions/runs/39181767823),
  exact commit `d385c00a4ff9f9aba4e95ab7f46984e977ed2326`.
  All **ten** job steps succeeded. Logs:
  `SOURCE_FREE_CANARY_PASS cases=2250 java=21.0.12.1` and
  `SOURCE_FREE_LIFECYCLE_PASS cases=1600 java=21.0.12.1`.
  Neither model copied Oreslang source to the testing organization.

Unresolved release gate: source `main` still lacks enforced branch
protection, its reviewed private-carrier allowlist is empty, and
private-source exposure issue #22 / publisher provenance issue #962
remain open. Do NOT transfer actual source into any alternate repo
without independent, numeric-ID-checked approval and exact-tree
verification. Stage-1/2 remain stacked and draft; actual Graal/JUnit
results are unknown.


## Verified parent-stack reconciliation — 2026-10-18

The child draft #1407 had diverged from its stage-1 parent #1402 after
three independent parent hardening commits. At reconciliation:

- Parent #1402 exact commit: `d04f877788b1964d3c7038baa153c24f19ebb170`.
- Child #1407 previous head: `97413c2a1f8258d1f512d78aa28e3644b99cb552`.
- The child already carried the parent fatal-revoker code and test semantics,
  plus the four newer retirement-guard regressions.
- Resolution: build Git tree `657ee28b66f3e045e4ae9f9ea57544811d6ffbf6`
  from the complete previous child tree, updating the stage-1 audit document
  to the parent-approved blob `e55bc40c1034a90c1a48294154515d07d9352a6b`.
- Two-parent merge commit `01b9d9c68d53710526e01f72d5aac8f7266f2e9e`,
  parents (child previous head, parent latest head). The branch ref was
  advanced by guarded **non-force** update; no commit history was rewritten.
- GitHub verification: `compare #1402...#1407` reports **ahead 51,
  behind 0**, and PR #1407 reports **mergeable=true**. No parent audit changes
  or stage-2 code/test changes were discarded. Source `main` is unchanged.
- Independent check of committed stage-2 source: exactly **61** `@Test`
  annotations (21 TaskCancellation, 24 TaskHandle, 16 TaskScope).

Local verification after this merge, using available host-side fixtures and
JDK 21: 2,400 cancellation invariants, 2,000 retirement/race invariants,
and 3,500 TaskScope invariants all passed. `TaskScope.java` is the exact
production Git blob `211b5812183e9e35f7a9d7358b1a6f2ccebd024b`,
but TaskScope dependencies are test doubles. The cancellation fixture is
derived from the current primitive, not a byte-exact source comparison.
These are **not** Maven, original JUnit or GraalVM CI results.

Latest source Actions checks on reconciliation commit remain blocked:
[ci #39182640735](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39182640735)
(four sampled jobs), [fast-ci #39182640704](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39182640704)
and [manifest #39182640749](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39182640749)
all failed with **zero recorded steps**. Do not infer a compiler failure or a
successful test execution from these runs.

The private-carrier allowlist on source `main` still contains no approved
repository and enforced main branch protection is still absent. Do not
mirror the private Java Git tree across organizations until independent
approval and exact numeric-ID/tree provenance gates pass. The prior
source-free funded carrier canary does not authorize source export.


## Cooperative TaskHandle cancellation checkpoints — current audit

**Policy:** `TaskHandle.cancel(reason)` is a structured cancellation
**request**, not an asynchronous exception injection, thread interruption,
or proof that the guest has left its carrier. A queued Task is rejected before
its first resume; a suspended Task's Future/await registration is revoked;
and a Task currently executing a compiler-generated `Task.resume` is
allowed to finish that synchronous turn, including Java `finally` clauses,
before the result Future is cancelled at the **post-resume checkpoint**.
Cancellation also checks the token before every subsequent resume.
No further guest turn is admitted once a checkpoint observes cancellation.

`TaskHandle` now arbitrates cancellation and guest-entry on an internal
`resumeBoundaryLock`. The revoker defers `result.cancel(false)` while a
guest turn is active, rather than transitioning the producer Future to
cancelled in the middle of ordinary guest instructions. The cancellation
request token/reason is still published immediately and can be inspected
without waiting for the guest. Result completion and `getDone()` remain
distinct: `getDone` completes only after scheduler physical quiescence.
External `result().cancel()`, scheduler hard shutdown, wall deadline and
force cancellation remain separate immediate revocation mechanisms, not
graceful TaskHandle cancellation.

**Failure ordering:** if guest execution throws (including a guest-thrown
`CancellationException`) and is unwinding, do not overwrite that error by
cancelling its Future at the post-resume checkpoint. A genuine scheduler
`CancelledAtResume` still yields the Cancelled outcome. The first valid
cancellation reason wins for cooperative cancellation, and late cancellation
cannot rewrite an already-terminal Task result.

**Not yet guaranteed:** asynchronously suspended Oreslang `finally` /
`defer` cleanup is not currently lowered as a special cancellation cleanup
continuation. This change guarantees synchronous turn-local cleanup only;
a future compiler/runtime feature needs an explicit bounded, noncancellable
cleanup state machine to ensure async-finally runs after cancellation, with
failure suppression rules. Arbitrary CPU loops, native calls, FFI and blocked
host callbacks have no forced cooperative progress; the watchdog can request
a yield, but only compiler-generated safepoints can actually cooperate.
Cancelling an `OresFuture` directly still bypasses this structured request.

**Committed regression source:** TaskHandleTest tests running-turn deferred
cancellation, synchronous finally, one-turn cooperate cutoff, guest-error
preservation, borrowed pending Future ownership and queued task rejection.
TaskScopeTest now asserts that cancellation of two actively executing child
turns keeps their result Futures pending until their resume checkpoints.
These are committed JUnit source tests; source-org CI has not yet executed
the Maven/GraalVM suite due to zero-step runner failures.

**Follow-up compiler contract:** lower source `rt cooperate`, explicit
`await`, scheduler suspension and selected generated loop safepoints as
checkpoints; check cancellation before entering the next guest frame and
after the current turn unwinds. Add async cleanup unwinding and eventually
task-local cancellation shielding only for short, bounded cleanup scopes.


### Additional reentrancy test and local model evidence

`TaskScopeTest.guestTurnCanCancelItsOwnScopeAndFinishFinally` verifies
that a source Task can request scope cancellation reentrantly inside its
running resume turn without publishing scope completion before the turn
unwinds and its synchronous `finally` executes. Current stage-2 test
inventory is **28 TaskHandleTest + 17 TaskScopeTest** JUnit methods,
committed but not yet run by real Maven/GraalVM.

An isolated OpenJDK 21 checkpoint-ordering model compiled under
`javac -Xlint:all` and passed **3,600/3,600** iterations spanning
cancel-while-running, guest exception versus cancellation and
pre-admission cancellation. It is not the production TaskHandle source or
scheduler, so this is supportive concurrency-model evidence only.
