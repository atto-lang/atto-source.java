# ActorGroup membership monitor — Java runtime draft (2026-11-09)

Status: **SOURCE PATCH ONLY — NOT_EXECUTED / NOT_GUEST_LOWERED / NOT_AOT_VERIFIED**.
This draft stacks on Java pubsub isolation #2716, reconciliation #2647.
Portable reference contract: conformance #634. It does not replace
the distinct ActorGroup registry retirement #2710 or EventBus callback-safe
teardown #1230 patches.

## Design and security boundary

An AttoLang ActorGroup remains a cooperation domain, **not** a supervisor,
publisher, topic subscription, Mailman, or actor lifecycle owner. Monitoring
group membership does not subscribe to any Topic<T> and never emits ordinary
DATA or supervisor CONTROL envelopes.

New **host Java runtime** surface on ActorRuntime.ActorGroup:
- `membershipSnapshot()`: immutable snapshot of sorted actor incarnation IDs
  plus canonical decimal revision; host or currently authorized group member.
  Authority is rechecked **inside** the membership lock before collecting data.
- `monitorMembership(capacity)`: **current actor only**. Installs a revocable
  monitor using the exact current runtime actor incarnation, recording the
  monitor and member snapshot under the same `groupMembershipLock` boundary.
  Monitor registration itself does not increment group revision.
- `MembershipMonitor.poll()` or `poll(afterRevision)`: bounded, nonblocking
  read. Strict canonical decimal cursor returns status `INVALID_REVISION` or
  `REVISION_MISMATCH` without dequeuing, including large/malformed inputs.
  `EMPTY` has no message; `EVENT` includes immutable
  `MembershipChange(revision,JOIN|LEAVE,actorId)`.
- `MembershipMonitor.resync()`: atomically resets an overflowed queue and
  returns a new revision + member snapshot. `OVERFLOW` persists until resync.
- `MembershipMonitor.close()`: owner-only idempotent revocation. Group close
  or owner leave/retirement automatically retires monitors and releases quota.

All state changes and control queue operations serialize on the **existing**
`ActorRuntime.groupMembershipLock`, rather than adding a second registry
lock or a callback-capable broker. The monitor does not invoke callbacks,
resolve arbitrary event payloads, or start new schedulers.

Revisions are group-local `BigInteger` values and increment exactly once for
successful join/leave transitions. Duplicate joins, invalid joins, DATA
publications, snapshots and monitors do not advance the clock. New group
identities start at zero unless a creator was auto-added, in which case the
initial membership is revision one. No value ever crosses a signed-u64
wraparound, but BigInteger is a **Java prototype only**; portable guest ABI
must define bounded epoch/key rollover before release.

## Resource isolation

- At most 16 active monitors for a single actor incarnation **across all**
  groups in the same ActorRuntime.
- At most 1024 active monitors per group.
- Monitor backlog between 1 and 256 membership events.
- Full backlog invalidates the incremental queue; there is no silent loss,
  implicit replay, unbounded buffering, or cross-actor monitor delegation.
- All grant retirement/close paths reclaim credits. Independent ordinary
  actors remain alive when their group is closed.
- Monitor tokens are explicitly rejected (including nested containers) by
  runtime mailbox graph admission and data freeze preflight. Guest type-checker
  lowering must additionally enforce non-Sendable actor-affine capabilities.

## Executable verification definitions, NOT executed evidence

`ActorGroupMembershipMonitorTest` adds five JUnit methods:
1. Snapshot + overflow + revision-4 resync and stale token after leave/rejoin
2. Other-actor access denial and group-close auto-revocation
3. Eight live actors performing 32 balanced concurrent join/leave cycles
   (512 revisions) with one watcher and an exact final revision of 513
4. Per-actor monitor cap shared by two independent groups; close reclaims it
5. Direct and nested monitor token rejection across actor mail and shared data

`scripts/verify-actor-pubsub-v1.sh` now selects this suite and requires **all
five named Surefire methods** plus prior guest and safety suites. The existing
`scripts/verify-critical-pubsub-integration.sh` invokes that script and
also requires the separate combined GraalVM guest integration.

## Known blockers / ordered follow-ups

1. Source-org GitHub Actions billing/payment-or-budget admission #315 must be
   resolved by authorized billing admin. Obtain exact-head runner/job/attempt,
   **nonzero executed named steps**, compiler/JUnit logs, and Surefire methods.
2. Merge/reconcile EventBus callback-safe teardown #1230 and PG registry
   retirement #2710 **semantically**, maintaining all existing mainline
   publisher-scoped subscription, ack, credit and cancellation protections.
   The current leave path still calls EventBus teardown while holding
   groupMembershipLock; this patch **does not certify that lower layer**.
3. Implement **AttoLang guest** `GroupMonitor` syntax-free typed method
   lowering with borrow checker and non-Sendable admission. The Java host
   API is not evidence of a compiled `.atto` monitor program.
4. Define portable epoch/rollover, durable vs ephemeral, distributed
   consistency and cross-tenant policy separately; no Erlang distributed pg
   convergence or at-least-once/exactly-once guarantee is implied.
5. Execute same guest/negative traces and scheduler race suites across
   Java/GraalVM, LLVM, Wasm, .NET NativeAOT, Forge and any independently
   supported Cranelift target.

**Security hold:** No private-source mirroring to alternate GitHub owners,
no token/secret relay, no bypass of branch policy or unreviewed merge.
