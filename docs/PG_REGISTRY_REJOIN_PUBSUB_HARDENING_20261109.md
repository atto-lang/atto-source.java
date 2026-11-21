# AttoLang Pub/Sub / pg: registry retirement and rejoin fence (2026-11-09)

**Status:** DRAFT SOURCE ONLY — NOT_COMPILED, NOT_EXECUTED, NOT_GUEST_LOWERED, NOT_AOT_VERIFIED.

This is a semantic integration of the scoped `ProcessGroupRegistry` and
registry-monitor release technique from [historical Java #2710](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2710)
with the current Java mainline-first pub/sub stack:
[#2647](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2647)
→ [#2716](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2716)
→ [#2719](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2719)
→ [#2726](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2726).

**Reason for independent port:** `ProcessGroupRegistry` is absent from
#2726's base. The original #2710 is stacked on an October OTP/ETS feature
branch, not on the current source stack. Its code therefore cannot be assumed
to merge without reconciling runtime-owned group lifecycle and publisher-scoped
subscription teardown.

## Implemented source

- Package-local `ActorRuntime.createRuntimeOwnedActorGroup()` creates a
  runtime-owned, root-supervised ActorGroup with **no implicit creator/member**
  and the normal actor-policy/member/runtime quotas. The registry cannot turn
  cooperation group membership into actor lifecycle ownership.
- Package-local `ActorGroup.runtimeJoinCapability()`,
  `runtimeMemberCount()`, `runtimeContains()`, and `runtimeClose()` support
  registry internals. The runtime's `resolveLocalActorRef()` resolves only
  live actors. These methods do **not** expose new public guest capabilities.
- `ProcessGroupRegistry` provides local (scope,name) discovery and bounded
  name quotas, host/supervisor admission, actor-owned membership tokens,
  idempotent duplicate join, current-actor leave, membership views and
  lifecycle-neutral delete/close.
- Every name maps to an **exact Entry/ActorGroup identity**. A stale membership
  token or old group closing after a name is reused cannot retire the
  replacement. Group close callbacks never execute under the registry's
  `synchronized(lock)`.
- `withRegistryLock` collects retirements under the registry monitor and
  drains outside even when the operation throws. Final-member leave invokes
  `ActorGroup.leaveCurrent()` after releasing the registry monitor, then
  prunes the old exact entry only if still matched.
- Existing #2726 EventBus detach-then-finalize protects subscription
  callbacks from `groupMembershipLock`, topic definition/delivery locks and
  reduction locks. This patch leaves that code intact.

## Added JUnit suites and exact-head method gates

14 JUnit methods are selected and individually required by
`scripts/verify-actor-pubsub-v1.sh`:

- `ProcessGroupRegistryTest`: 5 tests for duplicate join/leave, actor lifetime,
  name cardinality, same/other runtime authorization, membership views.
- `ProcessGroupRegistryRetirementTest`: 3 tests for callback reentrancy during
  delete and final leave, and exact-entry name reuse.
- `ProcessGroupRegistryReentrantNameReuseTest`: 4 tests for
  callback-triggered re-creation of the *same name* during old group shutdown,
  stale old membership handle after re-creation, and 24 parallel delete/join
  rounds that must never resurrect or close the wrong group, plus direct/nested\n  denial of registry and membership capability transport.
- `ActorEventBusMembershipChurnTest`: 2 tests for 32 leave/rejoin cycles with
  exactly-once subscription credit reclamation, and closing a pending second
  RELIABLE channel write when subscriber membership is revoked.

Tests use real actor turns and the runtime API but are **authored, not run**;
test source inspection must not be mistaken for execution receipts. The exact
SHA, stepful job, Surefire XML methods, Graal guest results and actual runtime
logs are mandatory for a pass.

## Unresolved or deliberately deferred

1. [CI runner-admission issue #315](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/315):
   authorized billing admin must resolve payment/spending-budget admission,
   prove same-source-org no-checkout canary with executed steps, and only then
   run exact-head compiler + JUnit + Graal/Native Image checks.
2. A registry name can be detached before its old group completes physical
   shutdown, consuming **transient runtime group capacity**. If cap is reached,
   new group creation fails explicitly; retry/backpressure and maximum
   retirement slots require separately designed limits and stress tests. No
   forced bypass or secret-based cross-org runner is permitted.
3. Source-level ActorGroup host capabilities do not prove AttoLang guest
   `pg`, `GroupMonitor` or Task/Suspend lowering, compiler-negative capability
   checks, actor-affine ownership across awaits, or .NET/LLVM/Wasm/Forge parity.
4. Local `pg` discovery is **not** Erlang distributed pg, topic publish,
   subscription, or supervisor CONTROL. Tenant isolation and authorization
   of local named scopes must be reviewed before guest exposure.
5. Integration of the old #2710 branch's unrelated OTP/ETS features is
   intentionally excluded. Independent review must reconcile dependencies
   before merging any of the stacked drafts.

## Proof contract

```bash
export ORESLANG_PUBSUB_EXPECTED_SHA="$(git rev-parse HEAD)"
bash scripts/verify-actor-pubsub-v1.sh
export ATTO_INTEGRATION_EXPECTED_SHA="$(git rev-parse HEAD)"
bash scripts/verify-critical-pubsub-integration.sh
```

These exact-head gates remain **NOT_EXECUTED** until a source-org job actually
runs the commands, produces a verified raw-worktree receipt and a full set of
required named JUnit testcases. Preserve security holds and branch protection.
