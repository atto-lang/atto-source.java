# Ticketed actor STOP lexical cleanup integration — WIP

**Status: unverified, not mergeable.** This branch is based on Java PR #709 and deliberately isolated from its current draft head. It adapts the #823 source cleanup semantics to #709's per-task serial execution tickets. No compiled/Native Image execution has yet been demonstrated.

## Implemented on this integration branch

- `ActorRuntime.startActorTask` registers cleanup-aware source Futures and their original `SerialExecutionTicket` atomically under the actor lifecycle lock. Task termination retires its ticket only after scheduler completion and removes the source cleanup barrier.
- `enqueueContinuation` admits a post-STOP turn **only** when a scheduler-owned `CleanupTurn` is cancellation-authorized, belongs to a still-owned source task, and is accounted under its original actor/incarnation ticket. A revoked ticket cannot reserve CONTROL capacity.
- `SerialExecutionTicket.reserveCleanupControlSlotFor` and `requireCleanupRunnable` check actor ID, incarnation identity, task retirement, original serial handler lease, and external cleanup authorization. Actor STOP deliberately revokes ordinary `actorAlive`, never the narrower lexical-cleanup capability.
- STOP seals user mail and ordinary CONTROL, snapshots tracked cleanup Futures, preserves only scheduler-owned cleanup-capable envelopes while injecting terminal cancellation, and delays finalization/serial lease revocation until tasks settle or the 2-second grace deadline expires.
- Actor carrier admission permits STOP cleanup only after checking the authenticated `CleanupTurn` again. Normal guest messages, foreign/stale callbacks, and ordinary source execution remain blocked. `schedulerSafepoint` allows actor cancellation cleanup only after terminal resume is actually injected into that SourceTask.
- `SourceTask` now implements `CleanupAwareTask`, and nine actor teardown/cleanup races from #823 have been ported to `ActorTicketedStopCleanupIntegrationTest` while retaining the existing #709 serial ticket tests.

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
