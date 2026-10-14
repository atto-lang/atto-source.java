# Ticketed actor STOP lexical cleanup integration — WIP

**Status: unverified, not mergeable.** This branch is based on Java PR #709 and deliberately isolated from its current draft head. It adapts the #823 source cleanup semantics to #709's per-task serial execution tickets. No compiled/Native Image execution has yet been demonstrated.

## Implemented on this integration branch

- `ActorRuntime.startActorTask` registers cleanup-aware source Futures and their original `SerialExecutionTicket` atomically under the actor lifecycle lock. Task termination retires its ticket only after scheduler completion and removes the source cleanup barrier.
- `enqueueContinuation` admits a post-STOP turn **only** when a scheduler-owned `CleanupTurn` is cancellation-authorized, belongs to a still-owned source task, and is accounted under its original actor/incarnation ticket. A revoked ticket cannot reserve CONTROL capacity.
- `SerialExecutionTicket.reserveCleanupControlSlotFor` and `requireCleanupRunnable` check actor ID, incarnation identity, task retirement, original serial handler lease, and external cleanup authorization. Actor STOP deliberately revokes ordinary `actorAlive`, never the narrower lexical-cleanup capability.
- STOP seals user mail and ordinary CONTROL, snapshots tracked cleanup Futures, preserves only scheduler-owned cleanup-capable envelopes while injecting terminal cancellation, and delays finalization/serial lease revocation until tasks settle or the 2-second grace deadline expires.
- Actor carrier admission permits STOP cleanup only after checking the authenticated `CleanupTurn` again. Normal guest messages, foreign/stale callbacks, and ordinary source execution remain blocked. `schedulerSafepoint` allows actor cancellation cleanup only after terminal resume is actually injected into that SourceTask.
- `SourceTask` now implements `CleanupAwareTask`, and nine actor teardown/cleanup races from #823 have been ported to `ActorTicketedStopCleanupIntegrationTest` while retaining the existing #709 serial ticket tests.

## Follow-up safety audit (2026-10-14)

**Confirmed source defects corrected on the WIP branch:**

1. **STOP dequeue dead-end:** the actor's outer mailbox loop admitted source cleanup but an inner unconditional `if (stopped.get()) break` prevented every STOP continuation. The inner gate now stops only after cleanup eligibility is exhausted; each envelope still passes its independent post-STOP authority check under `lifecycleLock`.
2. **Split classification/reservation:** `enqueueContinuation` initially classified a turn as ordinary/cleanup outside `lifecycleLock`, then reserved its CONTROL slot under a second lock and rechecked when publishing. A STOP in the gap could revoke a legitimate source continuation. Cleanup classification, ticket provenance, mailbox capacity reservation, and publication now share one actor lifecycle transaction. Foreign/revoked tickets are rejected before capacity accounting and overflow is reported after releasing the lifecycle monitor.
3. **Misclassified STOP revocation:** `SerialExecutionTicket.requireAdmissible` treated a valid same-incarnation ticket whose ordinary authority had just been revoked as a generic illegal/foreign continuation. It now distinguishes a benign local `RetiredTaskException` from truly foreign actor/incarnation attempts, so cancellation races do not poison actor supervision.
4. **Cross-carrier data races:** `sourceCleanupTasks` now uses a concurrent key set; `sourceScheduler`, `sourceCancellationPending` and `finalized` are visible across scheduler, actor and cancelling threads. STOP continues to enforce lifecycle-lock ordering for actual state transitions.

**New regressions:** STOP/carrier authority test in `SerialExecutionTicketTest`; `ActorCleanupStopRaceStressTest` races Future producer completion and actor STOP for 24 attempts, ensuring at most one cleanup invocation, actor-lane execution and no retired-source reentry. This suite is included in the JVM and native-carrier focused selectors. The nine upstream actor lifecycle scenarios are still included.

**Evidence limitation:** exact-head source-org GitHub Actions jobs, including typed-dispatch and fast CI, report failed with **zero job steps executed**. No code has compiled on that runner. The 24-attempt test is committed but **has not been run**, and no TLA+ refinement, Native Image, LLVM parity, or verified complete cross-org private mirror exists. Keep this PR draft until those gates actually execute.

## Required verification before review or merge

1. **Compile the entire exact source tree** on GraalVM JDK 25; fail hard if any source filename, generated resource, license, Maven profile, or dependency is missing.
2. Run `SerialExecutionTicketTest`, `OresSchedulerTest`, `DeferredCancellationLinearizationTest`, `ActorTicketedStopCleanupIntegrationTest`, `ActorContinuationTeardownTest`, `SourceTypedMatchTerminalFlowTest`, and the previously defined typed match/actor suites under both JVM carrier modes.
3. Stress actor STOP racing producer Future completion, queued initial turns, handler teardown, reused actor identity, serial task incarnation, full CONTROL mailbox, forced isolation, late callbacks, and deadline expiration. Confirm cleanup executes once on its original actor lane, never on the cancelling/producer thread.
4. Prove formal invariants with TLC model checks and mutant witnesses. Extend the model to represent `CleanupTurn` provenance, cancellation-injected task generation, per-task ticket ownership, serial lease retirement, and post-STOP authorization separately.
5. Perform native-image/hybrid execution and supported LLVM fixture parity. Reconcile latest #823/#886/#892 without replacing #709 `ARM_RESULT` ownership checks.

## CI mirror limitation

The source repository is **private** and ~32 MB. The connected GitHub operations can read individual files and create Git blobs/trees/branches in a second repo, but do **not provide a recursive source-tree enumeration or source archive**. Cross-org `GITHUB_TOKEN` checkout of the private source is not authorized. Reconstructing only known changed files is not the complete Git tree and cannot be called exact-tree CI.

The previously used `canonical-cloud/canonical-mcp-server.rs` runner repository is **public**. Private Oreslang source must not be copied to a public repository, even temporarily. Private repositories exist in other authorized organizations, but runner-minute availability has not been established; a private target still needs a complete authenticated Git-tree enumeration and transfer before any mirror is valid.

**Do not declare CI green, merge #709, or remove its existing fail-closed guard based only on these changes.**
