# Gate A — provisional physical-child reservation v2

**Draft integration hardening, 2026-10-19.** Based on latest reconciled
Java scalar guest-Task PR #1484 parent `b8e8776359d3a097ebadb4c4dbb306aa83176921`.
Retains newer compiler/region verifier, cancellation preflight and coherent
quiescence changes while carrying forward #1506 null/record/Send hardening. Do not merge before
real exact-head Maven/GraalVM execution and independent review.

## What changes

- Remove the Java-erasure-invalid overload pair
  `OresTask(OresFuture<T>)` / `OresTask(OresFuture<?>)`.
  `OresTask.provisional(fence)` constructs an unbound owner shell.
- Construct a scheduler-independent physical-exit Future and owner shell;
  register a physical-retirement observer; and reserve the shell in the
  owning scope **before** scheduler admission. All of this is in the scope's
  tree admission transaction.
- Call `scheduler.start` **without** holding either `treeAdmissionGate` or
  `scope.gate`. The scheduler can invoke guest turns, callbacks or a
  foreign executor, which must never run underneath these monitors.
- If the parent cancels before `OresFuture` binding, the shell remembers
  the cancellation request. Binding atomically installs the Future and
  replays cancellation outside the shell's synchronized section. A Future
  terminal CAS prevents duplicate notifications.
- `getDone()` uses the separately reserved physical-exit fence even while
  the child is unbound. Parent close may observe that fence and must not
  return until the scheduler either rejects admission or completes the
  physical exit and trusted generation/resource-release callback.
- Rejection **before** source dispatch calls the trusted release callback
  once, outside scope locks, and fulfills or fails the provisional
  physical-exit fence. Failed generation retirement remains reported.
- Fix an ordinary Task handle regression: AsyncRuntime.submitTask previously
  called the now-ambiguous constructor, which left an already-bound shell
  instead of a provisional shell and made attachCompletion fail.
- Ordinary `OresTaskScope.submit` now preallocates an `AsyncRuntime.TaskAdmission`
  containing the real TaskExit physical fence and provisional Task owner, adds
  it to scope ownership before dispatch, and starts the executor *outside*
  scope monitors. Rejecting admission settles the unentered TaskExit; a
  parent cancellation during the gap is replayed on Future binding.
- Resolved primitive NULL and VOID are not Send/Sync owned payloads;
  structural readonly Record views are not Sync without independent frozen
  backing; the permitted scalar guest Task capture has a compiler-resolved
  Send check with a non-authorizing nominal callback.

## Required exact-head tests

```sh
mvn -B -ntp -Dores.runtime.carriers=java \
  -Dsurefire.failIfNoSpecifiedTests=true \
  -Dtest=OresTaskProvisionalAdmissionTest,OresTaskRuntimeAdmissionTest,\
OresTaskOrdinaryRetirementTest,OresTaskScopeRetirementBookkeepingTest,\
OresTaskScopePreAdmissionOwnershipTest,OresTaskScopeCoherentQuiescenceTest,\
TaskResolvedSafetyProofTest,OresTaskScopeFrameTest,TaskRegionPhysicalExitBridgeTest,\
GuestScalarTaskAwaitTest,ScopedTaskTypeBridgeTest,TaskTraitAdmissionHardeningTest test
```

Run cancellation races with an executor blocked inside enqueue, a competing
parent `cancel` and `close`, generation-release fault injection, and
physical-turn cancellation. The added regression test files exercise those host
transitions; the full tests above must execute on the same commit and
produce real steps/logs. Zero-step Actions failures are not test runs.

## Explicit remaining risks/gates

- Ordinary scope submission now uses unlocked runtime admission, but still
  requires the real exact-head suite, allocation/rejection fault injection,
  and cancellation-before-binding stress before it is release-qualified.
- Compiler-frame execution now checks parent-scope cancellation at each
  resume entry. A Task cancelled while already running may continue until
  its next legal cooperation/suspension point; no physical carrier is killed.
  When cancellation wins before Future binding but the worker check fails
  first, the result can be classified as a failure rather than a canonical
  Cancelled outcome. Unifying that outcome requires scheduler-authenticated
  cancellation markers; do not silently relabel arbitrary guest exceptions.
  **Do not expand guest Task shapes** until compiler-minted scope/region
  tickets, scoped cleanup and authenticated cancellation are integrated.
- New Task scopes do not yet inherit actor/root cancellation and untrusted
  wall/fuel/tenant quotas. `task {}` for nested, detached, mutable,
  actor-owned, untrusted, or GPU execution must remain gated.
- A final release must demonstrate actual Java21+/Graal guest execution,
  scheduler migration, native continuation parity, code-version pins,
  bounded stress and race/fault-injection tests.
- Private source cross-org mirroring is not an alternative to reviewed,
  protected branch policies. Java issue #962 and platform conformance issue
  #69 require authenticated private destinations, immutable source tree
  proof, and independent approval before transfer. No private mirror was
  created here.

## CI evidence boundary (October 19)

- Source-org `fast-ci` and `concurrency-contract` runs have repeatedly
  ended with `steps=null`, not Java compilation or JUnit results.
- The alternate private carrier's green run 39318472277 is **not an exact
  backend result**: Maven says `No sources to compile` for its main
  compiler, and only **21 synthetic canary JUnit tests** compile/execute
  under `carrier-harness-canary`. The host GraalVM/native-image steps
  verify carrier capability, not this private compiler PR's source tree.
- A narrow local Java 21 type-kernel run passes
  `TASK_RESOLVED_SAFETY_SMOKE_PASS 10000` and
  `JAVA_SEND_SYNC_NULL_CONTRACT_PASS`, but does not compile the full
  private runtime. Tests committed on this branch still require authorized
  exact-commit runner-backed execution.
- Security issue #962's destination allowlist remains empty. Do not mirror
  this PR's private tree into another GitHub organization until approved
  identity, tree pin and independent security review are proven.

## Ordinary-Task physical-exit retirement hardening (October 19)

- Runtime `TaskExit.markExited` now invokes the pre-registered trusted retirement
  hook *before* success on `quiesced`; thrown retirement errors fail that
  fence. The previous generic Future waiter was not authoritative because
  runtime callbacks intentionally swallow exceptions.
- `OresTaskScope.submit` installs a field-sensitive owner-slot retirement
  hook before executable submission and removes the old Future callback.
  Missing slots, throwing removals and pre-dispatch rejection now have a
  fail-closed physical fence rather than a false successful `getDone()`.
- `AsyncRuntime.abandonPreparedTask` retires and seals the provisional
  physical fence when scope reservation fails, including when a collection
  inserts an owner slot and then throws. No guest carrier was submitted.
- `OresTaskOrdinaryRetirementTest` adds six JUnit scenarios for failing
  and silently refused removals, rejection, release faults, and
  insert-then-throw registration. The actual private-source suite is
  still **not executed** on this exact commit.

### Authenticated evidence limits

- Alternate source-free GitHub Actions run
  https://github.com/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra/actions/runs/39342763759
  **PASSED** JDK21 strict compilation and 10,000 model
  cancellation/entry races, rejection, injected faults and delayed retirement.
  This is a newly authored stand-alone lifecycle oracle, **not any private
  Oreslang source**. No source repo Git objects were transferred for this run.
- Exact-head source-org `fast-ci` run 39343165476 and
  `concurrency-contract` run 39343165548 still failed with
  `jobs[].steps = null`. Treat both as unexecuted.
- The cross-org transfer allowlist remains empty in protected `main`;
  source-export security issue #962 remains open. Spare runner capacity
  does not override independent security approval.


## Further Gate A race audit: live-exit registration and stress (Oct 19)

- Corrected `AsyncRuntime.awaitTaskExit(completion)` live-map race:
  `taskExits.remove` now occurs **after** `TaskExit.markExited()` and
  therefore after the trusted retirement hook and physical-exit witness.
  The cancelled-before-entry worker no longer removes another thread's
  in-progress retirement registration.
- New `OresTaskOrdinaryRetirementTest.runtimeAwaitTaskExitDoesNotSkipPendingTrustedRetirement`
  blocks the release callback and asserts that the host joiner cannot
  escape while retirement is in progress.
- New `OresTaskScopeAdmissionStressTest` runs 600 source-runtime concurrent
  admission/cancellation/close rounds, checking no ownership escape and
  `getDone` success after shutdown. This class is now explicitly
  included in the `concurrency-contract` workflow's JUnit selection, but
  no source-org runner has executed it yet.
- Source-free alternate carrier run
  https://github.com/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra/actions/runs/39344662318
  PASSED actual JDK21 `-Xlint:all -Werror` compilation, 10,000
  independently coded cancellation/entry races and 725,760 bounded
  transition sequences covering both successful/failed retirement.
  **These are specification-model checks, not executed Oreslang source.**
- Review remains blocked by source-org zero-step Actions runs and security
  issue #962's still-empty approved destination allowlist. No private
  source mirror was created.

## Exact-source admission fault coverage and runner diagnosis (October 19)

- `concurrency-contract.yml` now explicitly includes
  `OresTaskScopeAdmissionStressTest` (600 scope admission/cancel races) and
  `AsyncRuntimeAdversarialAdmissionTest` (three deterministic host-executor
  admission faults), in addition to the Task retirement and TypeChecker tests.
- `AsyncRuntime.submitInternal` now makes the pre-dispatch retirement claim
  using a CAS rather than an unconditional state store; this prevents a
  concurrent runtime close and a rejected submission from both releasing the
  same Task. It also handles non-rejection `RuntimeException` and `Error`
  during executor admission, preserving the original failure.
- `AsyncRuntime(TurnExecutor, ExecutorService)` is package-private and
  exists only for deterministic tests. The public virtual-thread executor
  configuration is unchanged.
- `OresTaskOrdinaryRetirementTest` additionally exercises 200 close versus
  prepared executor-admission interleavings. The adversarial executor test
  holds `execute` during a concurrent `close` to force the rejection race.
- Current exact-head source `concurrency-contract` and `fast-ci` runs
  still show `jobs[].steps == null` and failure within seconds of queueing.
  They did NOT execute the Java compiler or JUnit. Do not claim an Actions
  billing issue without independently observing the repository/org
  allocation policy. Do not copy private source to another organization
  while security issue #962 is open and the source-approved allowlist is empty.
- Independent alternate carrier model run
  https://github.com/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra/actions/runs/39344662318
  passed JDK21 strict compilation, 10,000 races and 725,760 bounded schedules.
  This is useful adversarial design evidence but NOT exact-source execution.
