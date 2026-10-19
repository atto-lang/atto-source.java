# Gate A — provisional physical-child reservation v2

**Draft integration hardening, 2026-10-19.** This branch begins at Java scalar
guest-Task PR #1484 head `7efaa81785fcd9861b9e7f69c6aca46bcfe4e99e`.
It restacks the essential null/record/Send fixes of Java #1506 on that
head without reverting newer #1484 region-verifier work. Do not merge before
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
- Resolved primitive NULL and VOID are not Send/Sync owned payloads;
  structural readonly Record views are not Sync without independent frozen
  backing; the permitted scalar guest Task capture has a compiler-resolved
  Send check with a non-authorizing nominal callback.

## Required exact-head tests

```sh
mvn -B -ntp -Dores.runtime.carriers=java \
  -Dsurefire.failIfNoSpecifiedTests=true \
  -Dtest=OresTaskProvisionalAdmissionTest,TaskResolvedSafetyProofTest,\
OresTaskScopeFrameTest,TaskRegionPhysicalExitBridgeTest,\
GuestScalarTaskAwaitTest,ScopedTaskTypeBridgeTest,TaskTraitAdmissionHardeningTest test
```

Run cancellation races with an executor blocked inside enqueue, a competing
parent `cancel` and `close`, generation-release fault injection, and
physical-turn cancellation. The two added test files exercise those host
transitions; the full tests above must execute on the same commit and
produce real steps/logs. Zero-step Actions failures are not test runs.

## Explicit remaining risks/gates

- `OresTaskScope.submit(AsyncRuntime.Task)` still uses its older
  lock-held runtime dispatch and needs the analogous reserved owner/seam.
- A scheduled scalar Task may begin running before an in-flight parent
  cancellation has been replayed at Future binding. The currently admitted
  scalar guest path owns no borrowed region or actor state; **do not expand
  guest Task shapes** until pre-entry cancellation checks, compiler-minted
  scope/region tickets and safe-point cleanup are fully integrated.
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
