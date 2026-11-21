# AttoLang Java ActorGroup monitors: atomic snapshots + callback-safe EventBus teardown

**2026-11-09 — DRAFT SOURCE ONLY. No Java compiler, JUnit, GraalVM guest, or AOT execution evidence yet.**

This branch is stacked on [Java #2719](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2719), which depends on #2716 and #2647. It carries a semantic adaptation of the detach/close technique in [Java #1230](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1230), rather than transplanting that older, differently based patch wholesale.

## Atomic monitor registration

`ActorGroup.registerMembershipMonitor(capacity)` returns a single immutable
`MembershipRegistration(monitor, snapshot)` value under
`ActorRuntime.groupMembershipLock`. The snapshot carries a canonical decimal
group revision and sorted actor-incarnation IDs. If a join races registration,
**either** the join appears in the baseline with its revision, **or** the
subsequent monitor stream contains its next revision. It may never disappear
between a post-registration, separately obtained snapshot and a watch.

The old `monitorMembership(capacity)` remains source compatible and delegates
to the atomic registration method, returning only the monitor. New consumers
that require a zero-gap baseline **must** use the registration receipt, not
independent `membershipSnapshot()` calls. No new language keywords or guest
compiler lowering have been added.

## Two-phase subscription teardown

Actual actor leave runs two phases:

1. Under `groupMembershipLock`, revoke actor membership and its monitor
   grants, commit a single group revision, remove subscriber AND
   publisher-scoped subscription edges from each `topic.deliveryLock` map,
   and atomically mark the old subscription objects closed. This step is the
   rejoin/old-subscription fence and does not invoke channel callbacks.
2. After unlocking membership, call the detached cleanup exactly once per
   subscription: close Channel, drain retained deliveries, release reliable
   reservations, and reclaim the global subscription credit. Attempt every
   detached close, accumulate suppressed failures, and preserve columnar
   epoch failure notification even if cleanup throws.

`ActorEventBus.closeFromGroup()` similarly detaches under its definition/topic
locks, closes outside. Explicit `Subscription.close()` removes and marks
under the topic lock, then finishes outside. `publishFrozen()` prunes stale
subscriber/publisher edges under the topic lock and finishes their closes
after leaving that lock. `reduceDouble()` now defers its stale-subscriber
closes until after its **reduction** lock, while retaining atomic reduction
state, event sequence, and fanout admission. The original four-argument
`publishFrozen` overload is retained for existing reflective JUnit tests.

`subscribeInternal()` still has a narrow `closeChannelOnly()` rollback
for **new, unpublished subscription candidates** under topic admission;
these candidates have not escaped and have no registered user read observers.
This is not a general permission to close exposed subscriptions under locks.

## Adversarial JUnit regression gate

`ActorGroupAtomicMonitorRegistrationTest` (2 methods):
- 24 iterations of current-actor registration racing another host thread's
  membership join. With either ordering, the initial snapshot plus strictly
  next queued revision must equal the final membership view; quotas are
  reclaimed after each monitor close.
- Invalid capacities are fail-closed with zero registered monitors; a valid
  receipt agrees with a fresh snapshot when no mutation occurs.

`ActorEventBusDetachedTeardownTest` (4 methods):
- Actor leave with outstanding `readAsync` callback, proving the callback
  does not execute under `groupMembershipLock` or `topic.deliveryLock`.
- Group close with outstanding read, proving callbacks are outside
  `definitionLock` and delivery lock and do not stop unrelated actors.
- Publisher-scoped subscription revoked on publisher leave; same-actor
  rejoin obtains a fresh distinct subscription, never revives old channel.
- Narrow white-box stale-subscriber fault injection into a MIN reduction;
  callback must not run while either reduction or delivery lock is held,
  reduction commits once, and the old subscription is closed.

`scripts/verify-actor-pubsub-v1.sh` now requires all **six named methods**
in the resulting exact-head Surefire XML, plus the parent suites. Its
invocation is already chained by `verify-critical-pubsub-integration.sh`.
Compilation and runtime receipts are **NOT_EXECUTED** until the source
organization's Actions job admission is restored.

## Known remaining integration and release blockers

1. The existing [CI billing/payment-or-budget admission issue #315](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/315) is administrator-owned. Require a stepful source-org no-checkout canary, then exact SHA/tree, runner and job attempt, executed test counts and named Surefire results. Failed zero-step workflows **do not** test this code.
2. [Java #2710](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2710) separately protects outer `ProcessGroupRegistry` retirement and name reuse; semantic reconciliation and test execution remain required. The source-only change here does not claim all nested runtime callbacks have been proven lock-free.
3. Actor-bound, non-Sendable `GroupMonitor` guest type, parser/type checker, borrow checker across suspensions and Graal compilation remain **UNIMPLEMENTED**. No backend parity or Native Image verification can be inferred from host Java source.
4. Unlimited `BigInteger` monitor revision in the Java prototype must be replaced/contracted to a portable bounded epoch/rollover policy before distribution across .NET/LLVM/Wasm/Forge.
5. An old member may rejoin before its previously detached **physical** channel cleanup finishes. Its old subscription is already logically closed, preventing stale authority; possible transient subscription-credit pressure requires separate stress testing and a well-defined retry path.
6. Private-source cross-organization copy/mirroring remains blocked by the active incident hold. Do not bypass source-org billing, protected branches, independent approvals, or release checks.

### Required exact-head commands after admin remediation

```bash
export ORESLANG_PUBSUB_EXPECTED_SHA="$(git rev-parse HEAD)"
bash scripts/verify-actor-pubsub-v1.sh
export ATTO_INTEGRATION_EXPECTED_SHA="$(git rev-parse HEAD)"
bash scripts/verify-critical-pubsub-integration.sh
```
