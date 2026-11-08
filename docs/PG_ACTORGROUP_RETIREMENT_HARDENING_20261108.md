# AttoLang pg / ActorGroup two-phase retirement hardening (2026-11-08)

**Status: DRAFT / NOT_EXECUTED / NOT_CERTIFIED.** This change is stacked on Java draft #1700, exact parent head `76cc7752ee6ddaebbd86a5e9e17799c40ba6eb03`. It is not based on current Java main, and must not be merged directly without an explicit semantic reconciliation. No private source was moved to a different organization.

## Finding

The original `ProcessGroupRegistry` called `ActorGroup.runtimeClose()` while holding its `synchronized(lock)` monitor in delete, name pruning, empty-group cleanup after leave, and failed join. The final-member `group.leaveCurrent()` was also called under this monitor.

Runtime group teardown may settle Channel/Future reads, execute callbacks, and reenter registry observation. The previous lock ordering allowed registry-monitor retention across callback-capable teardown. That is a source-level reentrancy/lock-order risk, not a previously demonstrated deadlock.

## Production change

- Registry operations collect exact `Entry` instances to retire under the registry monitor and detach them before invoking any `runtimeClose()`. An outstanding reference to the old group can never retire a new `(scope,name)` replacement.
- `withRegistryLock` flushes all claimed retirements after the monitor is released, including when the locked operation throws. Cleanup failures are aggregated while preserving the original exception as primary. No callback-bearing `runtimeClose()` is directly invoked from any registry synchronized block.
- The final-member leave and owned `Membership.leave()` call `ActorGroup.leaveCurrent()` outside the registry monitor, then reconcile empty-name reclamation behind an exact-entry identity check.
- `delete` detaches before teardown; `close` snapshots and detaches all entries under the monitor, then retires them outside it.
- Retired names may be re-created while the old group's asynchronous cleanup is finishing; authority remains bound to the exact ActorGroup identity, not its textual name. Group membership remains independent of actor lifecycle.

## Executable tests authored

`ProcessGroupRegistryRetirementTest` adds three JUnit scenarios:

1. A manager deletes a group with a pending subscriber read. Its completion callback reenters `pg.groupCount()`, and asserts it was not invoked while the registry monitor was held. The actor remains alive.
2. The final actor leaves a group with a pending read, asserting callback completion without retaining the registry monitor. The host test thread, **not the actor turn**, waits for the callback.
3. Deleting a group and then re-creating the same `(scope,name)` produces a different group identity; neither old membership nor retirement affects the new group, and ordinary actors survive.

These cases have **not** been executed on JDK 25 or GraalVM at this exact branch head. Source-level commit/patch inspection alone is not a passing JUnit report.

## Remaining blockers; no accidental scope expansion

- Java event-bus teardown draft [#1230](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1230) still documents `groupMembershipLock -> eventBus.removeActor -> Channel.close` risks *inside* `ActorGroup.leaveId`. Removing the outer pg registry lock does not fix those lower-level locks. The dedicated source/guest tests from #1230 must be reconciled and executed; this PR does not assert that all actor-group callback chains are lock-free.
- Publication versus membership leave/rejoin and exact actor-incarnation retirement must be tested concurrently, not only with deterministic orderings.
- The registry name capacity is bounded; detaching old groups before physical close can temporarily consume runtime group capacity. Before release, verify reclaim/new-group ordering under a saturated runtime allocation quota and fail closed without leaked capacity or implicit retries.
- `pg` remains local discovery, not an implicit topic broker or supervision hierarchy. Erlang `pg` repeated joins and eventual distributed membership differ from AttoLang's idempotent affine local ActorGroup grant model.
- Forge, Wasm, LLVM, Cranelift, .NET and Java *guest compiler* PubSub parity remain independent requirements. Neither these host JUnit cases nor conformance model PR #631 establishes guest parity.

## Exact-head verification gates

1. Checkout this PR exact commit and record tree + raw worktree evidence using the approved source-local verification procedure. No cross-org private mirror without separate security authorization.
2. Run `mvn -B -ntp -Dtest=ProcessGroupRegistryRetirementTest,ProcessGroupRegistryTest,ActorEventBusTest -DfailIfNoTests=true test` on an approved Java runner. Record job ID, runner assignment, nonzero test steps, XML suite counts, runtime and compiler versions.
3. Run the teardown assertions from #1230 and concurrent join/leave/delete/shutdown tests against the integrated tree. Do not label old #1230's tests as executed merely because the source was fetched.
4. Reconcile the result with Java #2647 and the portable conformance PS001–PS026 plus independent multi-membership tests. Require compiled AttoLang guest source evidence, exact current head, and independent review before merging any production hardening or promoting release status.

If runner admission fails before executing steps, classify that attempt as **NOT_EXECUTED**, preserve the workflow/job IDs, and keep this draft.
