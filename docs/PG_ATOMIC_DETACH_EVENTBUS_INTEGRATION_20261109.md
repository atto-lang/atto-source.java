# AttoLang local pg + ActorGroup Pub/Sub atomic-detach integration

**2026-11-09. DRAFT, source-level only, NOT_EXECUTED, NOT_CERTIFIED.**

## Provenance / dependency chain

This changeset is intentionally stacked on **Java PR #2717**, head `88608d38e9c0093238e3ae43114d15a21928cb36`, which is stacked on #2710 and #1700. It is **not** a rebase onto current main or a declaration that those parents are merge-safe.

The `ActorEventBus.java` changes were specifically reconciled from **event-bus teardown draft #1230**, whose file blob at `6beeca8f4f3e58b5c63991ccdab48944b529e5ef` was `d30215bef8761bb7055ff2ba4ddd1a6d64d2407a`. The integrated `ActorEventBus.java` blob matches exactly. It does **not** import all historical, divergent `ActorRuntime.java` changes or the #1219/#1229 parent stack; a separate semantic merge audit is required.

## Fixed source-level race

On the prior #2717 branch, `ProcessGroupRegistry.leaveCurrent()` / `Membership.leave()` revoked the registry epoch under `synchronized(lock)` but called `ActorGroup.leaveCurrent()` only after releasing that lock. Concurrent host `pg.join` could observe the still-live ActorGroup membership and admit/reuse a new epoch before the older leave physically removed the member. This could erase the new join. Keeping the old call under the registry lock would reintroduce the callback-under-lock hazard.

This integration uses two distinct phases:

1. **Logical, callback-free phase under the registry fence:** check the caller and epoch, acquire the ActorGroup membership lock, revoke the member and mark/detach its subscriptions, revoke the registry epoch, and reclaim the exact empty group entry if applicable. During this phase, the same `pg` registry cannot interleave a new join between epoch revocation and logical group leave. `ActorGroup.runtimeDetachCurrentMembership()` returns a deferred cleanup action.
2. **Physical phase after *both* locks:** run every deferred subscription Channel close/drain, then retire any detached empty groups. Callback failures are aggregated without skipping later cleanups. The runtime's ordinary `ActorGroup.leaveCurrent()` and actor-termination paths also use detached cleanup after releasing `groupMembershipLock`.

EventBus teardown also now:
- marks and removes subscriptions under `topic.deliveryLock` but closes Channels outside it;
- clears topic/group registries under definition/delivery locks, then physically closes detached subscriptions afterward;
- prunes stale subscribers during publish under the delivery lock but drains them outside;
- protects cleanup accounting with a try/finally and aggregates failures.

**Invariant:** no subscription Channel/Future settlement is intentionally called under the pg registry monitor, ActorGroup membership monitor or EventBus topic/definition monitor in these changed teardown paths. This is a *source-level design claim*, not yet an executed proof of callback safety or global lock freedom.

## New and reused regression fixtures

- **New** `ProcessGroupAtomicDetachRaceTest` (2 JUnit tests): actor leaves with a pending read; old close callback blocks on a latch; a concurrent host `pg.join` must complete before that callback is released, the old membership handle must not revoke the newer join, and actors must remain alive. Separately verifies explicit subscription.close callback occurs after topic delivery lock release.
- **Ported from #1230** `ActorEventBusTeardownLockTest` (2 tests): pending-read callback asserts ActorGroup groupMembershipLock and topic.deliveryLock are not held by leave, and definitionLock and topic.deliveryLock are not held by group close.
- **Inherited** `ProcessGroupRegistryMembershipEpochTest` (3 tests), `ProcessGroupRegistryRetirementTest` (3 tests), original `ProcessGroupRegistryTest`, and `ActorEventBusTest`.
- **Source-local CI gate** `.github/workflows/pg-security-exact-head-20261109.yml` includes the new tests in a no-cache JDK25 test selection; it checks source repository identity before any checkout and pins checkout to exact `github.sha`.

## Known unsolved risks / release hold

1. Underlying ActorGroup entry points can also be used without the `pg` registry, so direct host group joins and group closure must be separately stress-tested against epoch/leave races. The registry epoch is not a general ActorGroup capability token.
2. `ActorRuntime.createRuntimeOwnedActorGroup()` is still called from `entryForJoinLocked()` under the outer pg registry monitor; its uncommon runtime-shutdown collision may transitively close a group and execute callbacks before it returns. No claim of complete global lock-order safety.
3. No exact-head Java compile/JUnit or compiled AttoLang guest execution has been observed. The previous source-org workflow jobs fail with zero executed steps due to billing/payment-or-budget runner admission; incident issue [#315](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/315) is open. Do **not** report those as failing assertions.
4. A source-free alternate-org runner canary succeeded previously, but copying any private source/tree/blob into another org is still forbidden by independent private-source containment holds (see [security #337](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/337)); no mirror was performed here.
5. Reconcile parent branches #1219, #1229, #1230, #1700, #2710, #2717 against current main under independent review. No automatic merge, no bypass of security or review controls.
6. Compiler-lowered Pub/Sub guest code, full AOT, real per-actor heap isolation and cross-backend parity are not established by these host-local test sources. Verify Java, LLVM, Wasm, .NET, Cranelift, and Forge separately.

## Exact-head verification commands (when approved runner admission works)

```sh
mvn -B -ntp -Dtest=ProcessGroupAtomicDetachRaceTest,ActorEventBusTeardownLockTest,ProcessGroupRegistryMembershipEpochTest,ProcessGroupRegistryRetirementTest,ProcessGroupRegistryTest,ActorEventBusTest -DfailIfNoTests=true test
```

Capture Git commit and Git tree SHAs, workflow/job/attempt, assigned runner and actual named steps, JDK25/GraalVM details, complete Surefire XML and executed method counts, and any failure logs. Then run isolated host-join/leave/delete/shutdown stress, leak/quota checks, and actual compiled AttoLang guest fixtures. Keep **DRAFT / NOT_EXECUTED / NOT_CERTIFIED** until those observations and independent review succeed.
