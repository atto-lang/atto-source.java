# Supervisor FSM acceptance gates

The implementation remains a trusted-host shutdown substrate. The pure reducer,
bounded model, and status observer are not a refinement proof of ActorRuntime.

## Hardening in this revision

- Record close-initiation failures before fallback or exceptional completion.
  If both initiation attempts fail, enter coordinator emergency handling rather
  than letting a parent interpret an exceptional child future as retirement.
- Arbitrate deadline and actual completion once per bounded observer; publish
  deadline status before the observer invokes completion callbacks.
- Capture callback epochs at registration. Child-incarnation tokens and restart
  effect admission remain separate integration gates.
- Allow TLA+ restart reservation before retirement, as Java does; commit still
  requires actor and resource retirement. Explore CoordinatorFailed in Java BFS.
- Run focused JDK25 lifecycle/group tests alongside the Java 21 oracle and both
  finite TLC topologies in the source repository workflow.

## Evidence and limits

Local Java 17 compilation of the reducer and updated oracle passed with lint.
The oracle explored 952 states and 3,333 transitions with safety and conditional
completion-path reachability passing. This is not JDK25 runtime or TLC evidence.
The JUnit regression cases are authored; execution must be verified in CI.

The earlier mirror run 39823433651 checked the unchanged reducer but an older
oracle/model configuration. Its 620-state Java result and sibling-only TLC result
cannot validate the changed oracle/model. Mirror run 39823827855 failed stale
hash expectations before source execution; its workflow also omitted depth 3.
Do not change expected hashes without verifying the corresponding source blobs.

## Required before advancing the draft

1. Record the immutable tested commit/tree, workflow, tool versions, all formal
   blob hashes, actual executed steps, and retained logs. Both sibling and chain
   TLC configurations and the reducer oracle must pass at the current revision.
2. Execute SupervisorLifecycleFsmIntegrationTest and SupervisorShutdownTreeTest
   on JDK25, including timeout publication, initiation failure, coordinator loss,
   accepted-mail drain, and concurrent shutdown. Add full production-boundary
   injection for initiation failures; helper injection alone is insufficient.
3. Establish a refinement/trace mapping for combined Java transitions, partially
   completed group fencing, failures, and restart reservation. TLA+ still assumes
   atomic whole-tree fencing and omits failure outcomes. Java BFS is a single-node
   reducer abstraction with child receipts, not execution of child actors.
4. Prove frozen parentage and admission-time supervisor/child incarnation identity;
   verify stale, duplicate, conflicting and reordered callbacks, shutdown/restart
   races, and effect admission. Capturing a parent epoch alone is insufficient.
5. Integrate physical receipts with native loan/continuation/arena journals before
   emitting PhysicalRelease or ChildReclaimed. Neither timeout nor logical DONE
   certifies physical reclamation. Production currently stops at STOPPED.
6. Resolve stacked PR #1911 integration: root/runtime close ordering, typed CONTROL
   source lowering and checked effects, and two-phase transport teardown.
7. Pass full JDK25 Maven/JUnit, Graal AOT/hybrid, LLVM ASan/UBSan, fault injection,
   and the backend parity gates in SUPERVISOR_HEFSM_FORMAL_DESIGN.md. Unbounded
   safety needs an inductive proof; eventual progress needs explicit fairness and
   cooperative environment assumptions. TLC deadlock checking remains disabled.

Use source-repository CI first. Any private-source transfer must satisfy issue
#962's independently reviewed provenance/destination policy; an existing mirror
or a successful test result does not establish authorization for another transfer.


## Implicit child-scope hardening (2026-10-22)

`ActorRuntime.spawnInternal` now checks
`ActorGroup.requireWorkerAdmissionOpen()` under the inherited group's
authority lock before publishing a nested child. This closes the bypass
where `ActorGroup.spawnWorker` respected a shutdown fence but an actor
already inside that group could create a managed descendant after the
supervisor froze admissions. Child link, managed group registration, and
actor identity still publish atomically or roll back on admission failure.

`ActorDefaultChildScopeTest` is registered in the focused JDK25 workflow:
- Parent actor DONE must not publish while a child still executes.
- Actor STOP fences a racing local spawn without leaking an orphan.
- Default child failure is observable without implicit automatic restart.
- Supervisor admission fencing also rejects a nested spawn from an
  already-running managed worker, without altering event-bus membership.

These are authored tests, not proof of complete supervisor integration.
Actual source actor CONTROL hooks, authenticated terminal receipts,
independent supervisor reassignment and arena/loan reclamation remain gated.
Fresh JDK25 executing jobs are needed: source Actions currently fail without
recorded execution steps.


### Partial supervisor-tree admission deadline

A supervisor may be in RUNNING/RESTARTING if recursive group admission fencing
fails before its shutdown event publishes. The shared watchdog must not send
an invalid DeadlineExpired event to that unadmitted node and then throw before
settling the deadline observer. `SupervisorShutdownTree.deadlineEventFor`
maps an unadmitted node to CoordinatorFailed (never synthetic STOPPED), and
admitted nodes to DeadlineExpired. Focused
`SupervisorShutdownDeadlineFenceTest` validates the mapping in RUNNING,
RESTARTING and BEFORE_HOOK. This is authored/test-registered code; exact
JDK25 execution remains blocked by zero-step GitHub Actions failures.
