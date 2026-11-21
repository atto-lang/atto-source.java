# ProcessGroupRegistry membership-epoch hardening — November 9, 2026

**DRAFT / NOT_EXECUTED / NOT_CERTIFIED.** Stacked only on Java [#2710](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2710) head `005c40afb9ee46f9979c553d4b7ef71efc2efcfe`. No source transfer, alternate-org checkout, CI bypass or production merge.

## Observed source-level bug

`Membership.leave()` previously verified only `(scope,name,ActorGroup object,ActorId)`. If actor A joined group G and kept handle H1, invoked `pg.leaveCurrent(G)`, then rejoined G as H2 while another actor kept G alive, invoking stale H1 could call `group.leaveCurrent()` and revoke H2. The ActorId has not changed because this is the same live actor; actor incarnation fencing alone is insufficient.

A second bug: `Membership.leave()` set its consumed flag before checking current actor context, but the `orElseThrow` that rejects a host caller was outside the catch/reset block. An unauthorized host invocation could permanently consume H1 without actually leaving its membership.

## Scoped change

- Every `ProcessGroupRegistry.Entry` tracks an explicit monotonically increasing membership epoch by current runtime `ActorId`; repeated joins of an already-live membership reuse one epoch, preserving idempotent AttoLang behavior.
- A leave-handle captures the generation at join admission. Leaving through `pg.leaveCurrent` revokes the previous epoch before the callback-capable group leave. A stale H1 cannot revoke a later H2 simply because its actor ID matches.
- Host and actor join paths preflight epoch exhaustion **before** physically admitting membership. Defunct actor epoch entries are pruned on later join operations.
- All actor-identity checks, including the no-context host case, now occur inside the handle failure/reset scope. Unauthorized invocations cannot burn the handle.
- Keep membership separate from actor lifecycle ownership, supervision and PubSub subscription. No new language keywords.

## Tests authored (not executed)

`ProcessGroupRegistryMembershipEpochTest`:
1. Same actor leaves and rejoins a still-live group; old handle cannot remove new membership.
2. Duplicate join handles share one revocation epoch and duplicate leave remains idempotent.
3. Unauthorized host call cannot consume the valid actor's membership handle; actor can subsequently leave from its own turn.

`pg-security-exact-head-20261109.yml` is a source-org-only, no cross-owner, no-cache workflow. It pins checkout to GitHub's exact commit and requires the Maven JUnit tests `ProcessGroupRegistryMembershipEpochTest`, `ProcessGroupRegistryRetirementTest`, `ProcessGroupRegistryTest`, `ActorEventBusTest`. Do not infer execution from configured YAML or green workflow names without real job steps and Surefire results.

## Remaining security and concurrency holds

1. This is a **registry-level sequential epoch fence**, not a proven atomic `ActorGroup` membership-epoch operation. `group.leaveCurrent()` intentionally executes outside the registry lock because event-bus cleanup can invoke callbacks. Host-driven rejoin concurrent with a leave must be linearized at the underlying ActorGroup admission/retirement boundary. A future integrated runtime needs actor-group generation-check + detach under its own membership lock and callback-free finalize after unlocking. The lower-level [#1230](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1230) fix is a dependency.
2. `ProcessGroupRegistry.retireLocked` removes a namespace entry before `runtimeClose()`; stale cached raw group/topic handles may exist until the physical close executes. Review this window explicitly and add concurrent publication-versus-retirement tests before claiming a strict post-delete admission fence.
3. ActorGroup construction on a concurrent runtime-close path may still perform internal cleanup while the outer registry lock is held. The source-only #2710 refactor removed its *direct* `runtimeClose()` calls under the monitor but is not a proof of transitive lock freedom.
4. A detached old group can still consume runtime group capacity until physically reclaimed, even if the textual name is reusable. Verify capacity and memory accounting under churn, join/leave/delete overlap and shutdown.
5. Erlang `pg` duplicate joins are refcounted; AttoLang's duplicate join is idempotent by design. Distributed membership eventually consistent discovery must not be treated as local publication authority.

## Verification and export policy

- Source organization Actions billing/payment-or-budget admission [#315](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/315) remains blocked until administratively fixed.
- A separate, **source-free** alternate-org canary ran on `gha-indie-worker/gha-indie-worker-docs` commit `64768846fa099f910c077fbc1d54e477daf60cd3`, [run 42568035753](https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/42568035753) with four executed steps and both log markers. **No source, source Git objects, caches or artifacts were mirrored.** This verifies only canary runner admission, not source budget or guest tests.
- Active P0 private-source incident and export holds [#337](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/337) forbid this user-requested cross-org Git tree mirror until independent, provenance-bound authorization is established. Do not override the hold based on a passing canary or an owner's informal approval.
- The shared reference oracle in conformance [#631](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/631) passed 26/26 portable vectors in an isolated source-free V8 trace runner with Node Proxy detection stubbed; its added two cases also passed such a smoke. This is not Node CI, Java/JUnit, compiler-backed guest, concurrency proof or certification.

**Release gate:** Run the exact-head JDK25 JUnit suite on an approved source-local runner, reconcile with #1230 and integrated Java guest PubSub #2647, prove no stale delivery under concurrency, then obtain independent review before merge. Keep this PR DRAFT and retain explicit NOT_EXECUTED ledger entries.
