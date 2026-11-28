package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowJournalV1Test {

    @Test
    void appendAtomicallyRegistersVerifiedTransitionAndPendingEffect() {
        var loop = new DurableDevLoopV1("run-store", 3);
        var store = new WorkflowJournalV1();

        store.append(loop.accept(
                "start",
                new DurableDevLoopV1.Start("repair runtime")));

        var snapshot = store.snapshot();
        assertEquals("run-store", snapshot.runId());
        assertEquals(3, snapshot.maxIterations());
        assertEquals(1, snapshot.journalEntries());
        assertEquals(1, snapshot.pendingEffects());
        assertEquals(0, snapshot.claimedEffects());
        assertEquals(0, snapshot.abandonedEffects());

        var effect = store.effects().getFirst();
        assertEquals(
                "devloop/run-store/iteration/1/code",
                effect.effectKey());
        assertEquals(WorkflowJournalV1.EffectState.PENDING,
                effect.state());
        assertInstanceOf(
                DurableDevLoopV1.RequestCode.class,
                effect.command());
    }

    @Test
    void claimedEffectCompletesBeforeLeaseDeadlineAndIsNeverClaimedAgain() {
        var loop = new DurableDevLoopV1("run-complete", 3);
        var store = started(loop);

        var claim = store.claimNext(
                "worker-a", 1_000L, 100L).orElseThrow();
        store.complete(
                claim.effectKey(),
                claim.claimToken(),
                "receipt://model/1",
                1_099L);

        var effect = store.effect(
                claim.effectKey()).orElseThrow();
        assertEquals(WorkflowJournalV1.EffectState.COMPLETED,
                effect.state());
        assertEquals("receipt://model/1", effect.receiptRef());
        assertTrue(store.claimNext(
                "worker-b", 1_100L, 100L).isEmpty());
    }

    @Test
    void completionAtOrAfterLeaseDeadlineFailsClosedAsAmbiguous() {
        var loop = new DurableDevLoopV1("run-late-complete", 3);
        var store = started(loop);

        var claim = store.claimNext(
                "worker-a", 1_000L, 50L).orElseThrow();

        assertThrows(
                IllegalStateException.class,
                () -> store.complete(
                        claim.effectKey(),
                        claim.claimToken(),
                        "receipt://too-late",
                        1_050L));

        var effect = store.effect(
                claim.effectKey()).orElseThrow();
        assertEquals(WorkflowJournalV1.EffectState.AMBIGUOUS,
                effect.state());
        assertNull(effect.receiptRef());
        assertTrue(store.claimNext(
                "worker-b", 1_051L, 50L).isEmpty());
    }

    @Test
    void releaseAfterLeaseDeadlineAlsoRequiresReconciliation() {
        var loop = new DurableDevLoopV1("run-late-release", 3);
        var store = started(loop);

        var claim = store.claimNext(
                "worker-a", 100L, 20L).orElseThrow();

        assertThrows(
                IllegalStateException.class,
                () -> store.releaseUnexecuted(
                        claim.effectKey(),
                        claim.claimToken(),
                        "worker://claims-not-started",
                        120L));

        assertEquals(
                WorkflowJournalV1.EffectState.AMBIGUOUS,
                store.effect(claim.effectKey()).orElseThrow().state());
    }

    @Test
    void completeAndAppendAtomicallyAdvancesWorkflowAndCompletesEffect() {
        var loop = new DurableDevLoopV1("run-atomic-outcome", 3);
        var store = started(loop);
        var claim = store.claimNext(
                "worker-a", 100L, 100L).orElseThrow();

        var transition = store.completeAndAppend(
                claim.effectKey(),
                claim.claimToken(),
                "receipt://code/1",
                150L,
                "code-1",
                new DurableDevLoopV1.CodeChanged(
                        1, "patch://one"));

        assertEquals(
                DurableDevLoopV1.Disposition.APPLIED,
                transition.journalEntry().disposition());
        assertEquals(
                DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                store.replayDevLoop().machine().snapshot().phase());
        assertEquals(
                WorkflowJournalV1.EffectState.COMPLETED,
                store.effect(claim.effectKey()).orElseThrow().state());
        assertEquals(2, store.journal().size());
        assertEquals(1, store.snapshot().pendingEffects());
    }

    @Test
    void completeAndAppendCausationMismatchIsFailAtomic() {
        var loop = new DurableDevLoopV1("run-atomic-mismatch", 3);
        var store = started(loop);
        var claim = store.claimNext(
                "worker-a", 100L, 100L).orElseThrow();
        var before = store.persistentState();

        assertThrows(
                IllegalStateException.class,
                () -> store.completeAndAppend(
                        claim.effectKey(),
                        claim.claimToken(),
                        "receipt://wrong",
                        150L,
                        "cancel-1",
                        new DurableDevLoopV1.Cancel("wrong outcome")));

        assertEquals(before, store.persistentState());
        assertEquals(
                WorkflowJournalV1.EffectState.CLAIMED,
                store.effect(claim.effectKey()).orElseThrow().state());
    }

    @Test
    void completeAndAppendRetryAfterCommitIsIdempotent() {
        var loop = new DurableDevLoopV1("run-atomic-retry", 3);
        var store = started(loop);
        var claim = store.claimNext(
                "worker-a", 100L, 100L).orElseThrow();
        var event = new DurableDevLoopV1.CodeChanged(
                1, "patch://one");

        var first = store.completeAndAppend(
                claim.effectKey(),
                claim.claimToken(),
                "receipt://code/1",
                150L,
                "code-1",
                event);
        int journalSize = store.journal().size();

        var retry = store.completeAndAppend(
                claim.effectKey(),
                claim.claimToken(),
                "receipt://code/1",
                151L,
                "code-1",
                event);

        assertEquals(first, retry);
        assertEquals(journalSize, store.journal().size());
        assertEquals(
                WorkflowJournalV1.EffectState.COMPLETED,
                store.effect(claim.effectKey()).orElseThrow().state());
    }

    @Test
    void completeAndAppendRepairsEventFirstLegacySplitWithoutDuplicateJournal() {
        var loop = new DurableDevLoopV1("run-atomic-repair", 3);
        var store = started(loop);
        var claim = store.claimNext(
                "worker-a", 100L, 100L).orElseThrow();
        var event = new DurableDevLoopV1.CodeChanged(
                1, "patch://legacy");

        var machine = store.replayDevLoop().machine();
        store.append(machine.accept("code-legacy", event));
        assertEquals(2, store.journal().size());
        assertEquals(
                WorkflowJournalV1.EffectState.CLAIMED,
                store.effect(claim.effectKey()).orElseThrow().state());

        var repaired = store.completeAndAppend(
                claim.effectKey(),
                claim.claimToken(),
                "receipt://legacy",
                150L,
                "code-legacy",
                event);

        assertEquals(
                DurableDevLoopV1.Disposition.APPLIED,
                repaired.journalEntry().disposition());
        assertEquals(2, store.journal().size());
        assertEquals(
                WorkflowJournalV1.EffectState.COMPLETED,
                store.effect(claim.effectKey()).orElseThrow().state());
    }

    @Test
    void expiredClaimBecomesAmbiguousAndNeverBlindlyRetries() {
        var loop = new DurableDevLoopV1("run-ambiguous", 3);
        var store = started(loop);

        var first = store.claimNext(
                "worker-a", 1_000L, 50L).orElseThrow();
        assertEquals(1, first.attempt());

        assertTrue(store.claimNext(
                "worker-b", 1_050L, 50L).isEmpty(),
                "expiry must not auto-reissue an ambiguous side effect");

        var ambiguous = store.effect(
                first.effectKey()).orElseThrow();
        assertEquals(WorkflowJournalV1.EffectState.AMBIGUOUS,
                ambiguous.state());
        assertEquals(1, ambiguous.attempts());

        store.resolveAmbiguousForRetry(
                first.effectKey(),
                "reconcile://provider/not-found");

        var second = store.claimNext(
                "worker-b", 1_051L, 50L).orElseThrow();
        assertEquals(first.effectKey(), second.effectKey());
        assertEquals(2, second.attempt());
        assertNotEquals(first.claimToken(), second.claimToken());
    }

    @Test
    void ambiguousEffectCanBeResolvedAsCompletedWithoutRetry() {
        var loop = new DurableDevLoopV1("run-reconcile", 3);
        var store = started(loop);

        var claim = store.claimNext(
                "worker-a", 10L, 10L).orElseThrow();
        assertEquals(1, store.expireClaims(20L));

        store.resolveAmbiguousAsCompleted(
                claim.effectKey(),
                "receipt://provider/found",
                "reconcile://provider/query-1");

        var effect = store.effect(
                claim.effectKey()).orElseThrow();
        assertEquals(WorkflowJournalV1.EffectState.COMPLETED,
                effect.state());
        assertEquals(
                "receipt://provider/found",
                effect.receiptRef());
        assertEquals(
                "reconcile://provider/query-1",
                effect.reconciliationRef());
        assertTrue(store.claimNext(
                "worker-b", 21L, 10L).isEmpty());
    }

    @Test
    void positivelyUnexecutedLiveClaimCanBeReleasedAndRetried() {
        var loop = new DurableDevLoopV1("run-release", 3);
        var store = started(loop);

        var first = store.claimNext(
                "worker-a", 100L, 100L).orElseThrow();
        store.releaseUnexecuted(
                first.effectKey(),
                first.claimToken(),
                "worker://sandbox/start-failed",
                150L);

        var pending = store.effect(
                first.effectKey()).orElseThrow();
        assertEquals(WorkflowJournalV1.EffectState.PENDING,
                pending.state());

        var second = store.claimNext(
                "worker-b", 151L, 100L).orElseThrow();
        assertEquals(2, second.attempt());
        assertEquals(first.effectKey(), second.effectKey());
    }

    @Test
    void cancellationAbandonsPriorPendingEffectsButKeepsCancelEffectRunnable() {
        var loop = new DurableDevLoopV1("run-cancel-effects", 3);
        var store = started(loop);

        store.append(loop.accept(
                "cancel",
                new DurableDevLoopV1.Cancel("operator request")));

        var effects = store.effects();
        assertEquals(2, effects.size());
        assertEquals(WorkflowJournalV1.EffectState.ABANDONED,
                effects.get(0).state());
        assertInstanceOf(
                DurableDevLoopV1.RequestCode.class,
                effects.get(0).command());
        assertEquals(WorkflowJournalV1.EffectState.PENDING,
                effects.get(1).state());
        assertInstanceOf(
                DurableDevLoopV1.CancelWork.class,
                effects.get(1).command());
        assertEquals(1, store.snapshot().abandonedEffects());

        var claim = store.claimNext(
                "worker-cancel", 1_000L, 100L).orElseThrow();
        assertInstanceOf(
                DurableDevLoopV1.CancelWork.class,
                claim.command());
    }


    @Test
    void lateRejectedEventCannotAbandonPendingTerminalEffect() {
        var loop = new DurableDevLoopV1("run-terminal-preserve", 3);
        var store = started(loop);

        append(store, loop.accept(
                "cancel",
                new DurableDevLoopV1.Cancel("operator request")));
        var cancelKey = store.effects().get(1).effectKey();
        assertEquals(
                WorkflowJournalV1.EffectState.PENDING,
                store.effect(cancelKey).orElseThrow().state());

        append(store, loop.accept(
                "late-code",
                new DurableDevLoopV1.CodeChanged(
                        1, "patch://late")));
        assertEquals(
                WorkflowJournalV1.EffectState.PENDING,
                store.effect(cancelKey).orElseThrow().state());

        var claim = store.claimNext(
                "worker-cancel", 1_000L, 100L).orElseThrow();
        assertEquals(cancelKey, claim.effectKey());
        assertInstanceOf(
                DurableDevLoopV1.CancelWork.class,
                claim.command());
    }

    @Test
    void cancellationDoesNotPretendAlreadyClaimedEffectWasCancelled() {
        var loop = new DurableDevLoopV1("run-cancel-claimed", 3);
        var store = started(loop);
        var active = store.claimNext(
                "worker-a", 100L, 100L).orElseThrow();

        store.append(loop.accept(
                "cancel",
                new DurableDevLoopV1.Cancel("operator request")));

        var claimedAfterCancel =
                store.effect(active.effectKey()).orElseThrow();
        assertEquals(
                WorkflowJournalV1.EffectState.CLAIMED,
                claimedAfterCancel.state());
        assertFalse(claimedAfterCancel.retryAllowed());

        var cancel = store.claimNext(
                "worker-cancel", 101L, 100L).orElseThrow();
        assertInstanceOf(
                DurableDevLoopV1.CancelWork.class,
                cancel.command());

        assertEquals(1, store.expireClaims(200L));
        assertEquals(
                WorkflowJournalV1.EffectState.AMBIGUOUS,
                store.effect(active.effectKey()).orElseThrow().state());
        store.resolveAmbiguousForRetry(
                active.effectKey(),
                "reconcile://provider/not-found");
        assertEquals(
                WorkflowJournalV1.EffectState.ABANDONED,
                store.effect(active.effectKey()).orElseThrow().state());
    }

    @Test
    void knownUnexecutedClaimAfterCancellationIsAbandonedNotRequeued() {
        var loop = new DurableDevLoopV1("run-cancel-release", 3);
        var store = started(loop);
        var active = store.claimNext(
                "worker-a", 100L, 100L).orElseThrow();

        append(store, loop.accept(
                "cancel",
                new DurableDevLoopV1.Cancel("operator request")));

        store.releaseUnexecuted(
                active.effectKey(),
                active.claimToken(),
                "worker://confirmed-not-started",
                150L);

        var effect = store.effect(
                active.effectKey()).orElseThrow();
        assertEquals(
                WorkflowJournalV1.EffectState.ABANDONED,
                effect.state());
        assertFalse(effect.retryAllowed());

        var next = store.claimNext(
                "worker-next", 151L, 100L).orElseThrow();
        assertInstanceOf(
                DurableDevLoopV1.CancelWork.class,
                next.command());
    }

    @Test
    void appliedOutcomeRetiresStillPendingCausalEffect() {
        var loop = new DurableDevLoopV1("run-causal-retire", 3);
        var store = started(loop);

        String codeKey = store.effects().getFirst().effectKey();
        assertEquals(
                WorkflowJournalV1.EffectState.PENDING,
                store.effect(codeKey).orElseThrow().state());

        append(store, loop.accept(
                "code",
                new DurableDevLoopV1.CodeChanged(
                        1, "patch://one")));

        var retired = store.effect(codeKey).orElseThrow();
        assertEquals(
                WorkflowJournalV1.EffectState.ABANDONED,
                retired.state());
        assertFalse(retired.retryAllowed());

        var next = store.claimNext(
                "worker-push", 100L, 100L).orElseThrow();
        assertInstanceOf(
                DurableDevLoopV1.PushCode.class,
                next.command());
    }

    @Test
    void secondIterationCausalKeysUseCurrentIteration() {
        var loop = new DurableDevLoopV1("run-causal-iteration", 3);
        var store = started(loop);
        String sha1 = sha('a');
        String sha2 = sha('b');

        append(store, loop.accept(
                "code-1",
                new DurableDevLoopV1.CodeChanged(
                        1, "patch://1")));
        append(store, loop.accept(
                "push-1",
                new DurableDevLoopV1.PushRecorded(
                        1, sha1)));
        append(store, loop.accept(
                "ci-fail-1",
                new DurableDevLoopV1.CiCompleted(
                        sha1,
                        DurableDevLoopV1.CiConclusion.FAILURE,
                        "artifact://tests/1")));
        append(store, loop.accept(
                "bug-1",
                new DurableDevLoopV1.BugReportCreated(
                        1, sha1, "bug://1")));
        assertEquals(2, loop.snapshot().iteration());

        append(store, loop.accept(
                "code-2",
                new DurableDevLoopV1.CodeChanged(
                        2, "patch://2")));
        append(store, loop.accept(
                "push-2",
                new DurableDevLoopV1.PushRecorded(
                        2, sha2)));

        String ci2Key =
                "devloop/run-causal-iteration/iteration/2/ci/" + sha2;
        assertEquals(
                WorkflowJournalV1.EffectState.PENDING,
                store.effect(ci2Key).orElseThrow().state());

        append(store, loop.accept(
                "ci-ok-2",
                new DurableDevLoopV1.CiCompleted(
                        sha2,
                        DurableDevLoopV1.CiConclusion.SUCCESS,
                        "artifact://tests/2")));

        var retiredCi = store.effect(ci2Key).orElseThrow();
        assertEquals(
                WorkflowJournalV1.EffectState.ABANDONED,
                retiredCi.state());
        assertFalse(retiredCi.retryAllowed());

        String evaluate2Key =
                "devloop/run-causal-iteration/iteration/2/evaluate/" + sha2;
        assertEquals(
                WorkflowJournalV1.EffectState.PENDING,
                store.effect(evaluate2Key).orElseThrow().state());
    }

    @Test
    void journalReplayReconstructsReducerStateAcrossFailureLoop() {
        var loop = new DurableDevLoopV1("run-replay-store", 3);
        var store = new WorkflowJournalV1();
        String sha = sha('a');

        append(store, loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal")));
        append(store, loop.accept(
                "code-1",
                new DurableDevLoopV1.CodeChanged(
                        1, "patch://1")));
        append(store, loop.accept(
                "push-1",
                new DurableDevLoopV1.PushRecorded(
                        1, sha)));
        append(store, loop.accept(
                "ci-fail-1",
                new DurableDevLoopV1.CiCompleted(
                        sha,
                        DurableDevLoopV1.CiConclusion.FAILURE,
                        "artifact://tests/1")));
        append(store, loop.accept(
                "bug-1",
                new DurableDevLoopV1.BugReportCreated(
                        1, sha, "bug://1")));

        var replayed = store.replayDevLoop();
        assertEquals(loop.snapshot(), replayed.machine().snapshot());
        assertEquals(loop.journal(), replayed.machine().journal());

        List<String> storedKeys = store.effects().stream()
                .map(WorkflowJournalV1.EffectSnapshot::effectKey)
                .toList();
        List<String> replayedKeys = replayed.regeneratedCommands().stream()
                .map(DurableDevLoopV1.Command::effectKey)
                .distinct()
                .toList();
        assertEquals(storedKeys, replayedKeys);
    }

    @Test
    void forgedTransitionCommandsAreRejectedByIndependentReducerReplay() {
        var loop = new DurableDevLoopV1("run-forged-command", 3);
        var genuine = loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal"));
        var forgedCommand = new DurableDevLoopV1.RequestCode(
                1,
                "goal",
                null,
                "attacker/chosen/effect");
        var forged = new DurableDevLoopV1.Transition(
                genuine.journalEntry(),
                genuine.snapshot(),
                List.of(forgedCommand));
        var store = new WorkflowJournalV1();

        assertThrows(
                IllegalStateException.class,
                () -> store.append(forged));
        assertNull(store.snapshot().runId());
        assertTrue(store.effects().isEmpty());
    }

    @Test
    void forgedReducerSnapshotIsRejectedBeforeJournalMutation() {
        var loop = new DurableDevLoopV1("run-forged-snapshot", 3);
        var genuine = loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal"));
        var s = genuine.snapshot();
        var forgedSnapshot = new DurableDevLoopV1.Snapshot(
                s.runId(),
                DurableDevLoopV1.Phase.WAITING_FOR_CI,
                s.iteration(),
                s.maxIterations(),
                s.goal(),
                s.currentPatchRef(),
                sha('f'),
                s.lastReportRef(),
                s.lastFailureRef(),
                s.lastBugReportRef(),
                s.cancelReason(),
                s.nextSequence(),
                s.uniqueDeliveryIds(),
                s.journalEntries());
        var forged = new DurableDevLoopV1.Transition(
                genuine.journalEntry(),
                forgedSnapshot,
                genuine.commands());
        var store = new WorkflowJournalV1();

        assertThrows(
                IllegalStateException.class,
                () -> store.append(forged));
        assertNull(store.snapshot().runId());
        assertEquals(0, store.snapshot().journalEntries());
    }

    @Test
    void persistedIterationBudgetCannotChangeDuringRun() {
        var loop = new DurableDevLoopV1("run-policy", 3);
        var store = started(loop);

        var genuineRejected = loop.accept(
                "wrong-event",
                new DurableDevLoopV1.Start("other"));
        var s = genuineRejected.snapshot();
        var badSnapshot = new DurableDevLoopV1.Snapshot(
                s.runId(),
                s.phase(),
                s.iteration(),
                4,
                s.goal(),
                s.currentPatchRef(),
                s.expectedCommitSha(),
                s.lastReportRef(),
                s.lastFailureRef(),
                s.lastBugReportRef(),
                s.cancelReason(),
                s.nextSequence(),
                s.uniqueDeliveryIds(),
                s.journalEntries());

        assertThrows(
                IllegalArgumentException.class,
                () -> store.append(new DurableDevLoopV1.Transition(
                        genuineRejected.journalEntry(),
                        badSnapshot,
                        genuineRejected.commands())));
        assertEquals(3, store.snapshot().maxIterations());
        assertEquals(1, store.snapshot().journalEntries());
    }


    @Test
    void concurrentClaimOfSinglePendingEffectHasOneWinner() throws Exception {
        var loop = new DurableDevLoopV1("run-concurrent-claim", 3);
        var store = started(loop);
        int callers = 32;

        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var futures = new java.util.ArrayList<
                    java.util.concurrent.Future<
                            java.util.Optional<WorkflowJournalV1.Claim>>>();

            for (int i = 0; i < callers; i++) {
                final int worker = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return store.claimNext(
                            "worker-" + worker, 100L, 100L);
                }));
            }
            start.countDown();

            int winners = 0;
            WorkflowJournalV1.Claim winner = null;
            for (var future : futures) {
                var claim = future.get(5, TimeUnit.SECONDS);
                if (claim.isPresent()) {
                    winners++;
                    winner = claim.orElseThrow();
                }
            }

            assertEquals(1, winners);
            assertNotNull(winner);
            assertEquals(1, winner.attempt());
            assertEquals(1, store.snapshot().claimedEffects());
            assertEquals(0, store.snapshot().pendingEffects());
        }
    }

    @Test
    void leaseOverflowFailsBeforeTrustedClockMutation() {
        var loop = new DurableDevLoopV1("run-lease-overflow", 3);
        var store = started(loop);

        assertThrows(
                ArithmeticException.class,
                () -> store.claimNext(
                        "worker-overflow",
                        Long.MAX_VALUE - 5L,
                        10L));

        var claim = store.claimNext(
                "worker-good", 100L, 50L).orElseThrow();
        assertEquals(150L, claim.leaseUntilMillis());
    }

    @Test
    void journalClockCannotMoveBackwards() {
        var loop = new DurableDevLoopV1("run-clock", 3);
        var store = started(loop);
        var claim = store.claimNext(
                "worker-a", 100L, 50L).orElseThrow();

        assertThrows(
                IllegalArgumentException.class,
                () -> store.expireClaims(99L));
        assertEquals(
                WorkflowJournalV1.EffectState.CLAIMED,
                store.effect(claim.effectKey()).orElseThrow().state());
    }

    @Test
    void explicitPersistentStateRoundTripsClaimAndClockMetadata() {
        var loop = new DurableDevLoopV1("run-persistent-state", 3);
        var store = started(loop);
        var claim = store.claimNext(
                "worker-a", 100L, 75L).orElseThrow();

        var state = store.persistentState();
        var restored = WorkflowJournalV1.restore(state);

        assertEquals(store.snapshot(), restored.snapshot());
        assertEquals(store.journal(), restored.journal());
        assertEquals(store.effects(), restored.effects());
        assertEquals(
                claim.claimToken(),
                restored.effect(claim.effectKey())
                        .orElseThrow()
                        .claimToken());

        assertThrows(
                IllegalArgumentException.class,
                () -> restored.expireClaims(99L),
                "restored clock watermark must reject regression");
    }

    @Test
    void persistedEffectTamperingFailsClosed() {
        var loop = new DurableDevLoopV1("run-persist-tamper", 3);
        var store = started(loop);
        var state = store.persistentState();
        var original = state.effects().getFirst();

        var tampered = new WorkflowJournalV1.EffectSnapshot(
                original.effectKey(),
                new DurableDevLoopV1.RequestCode(
                        1,
                        "changed goal",
                        null,
                        original.effectKey()),
                original.state(),
                original.attempts(),
                original.workerId(),
                original.claimToken(),
                original.leaseUntilMillis(),
                original.receiptRef(),
                original.reconciliationRef(),
                original.retryAllowed());

        assertThrows(
                IllegalStateException.class,
                () -> WorkflowJournalV1.restore(
                        new WorkflowJournalV1.PersistentState(
                                state.runId(),
                                state.maxIterations(),
                                state.nextSequence(),
                                state.nextClaimNumber(),
                                state.lastObservedMillis(),
                                state.journal(),
                                List.of(tampered))));
    }

    @Test
    void independentClaimsWithSamePublicMetadataUseDifferentOpaqueTokens() {
        var firstLoop = new DurableDevLoopV1("run-token-random", 3);
        var secondLoop = new DurableDevLoopV1("run-token-random", 3);
        var firstStore = started(firstLoop);
        var secondStore = started(secondLoop);

        var first = firstStore.claimNext(
                "worker-a", 100L, 100L).orElseThrow();
        var second = secondStore.claimNext(
                "worker-a", 100L, 100L).orElseThrow();

        assertEquals(first.effectKey(), second.effectKey());
        assertEquals(first.attempt(), second.attempt());
        assertEquals(first.leaseUntilMillis(), second.leaseUntilMillis());
        assertNotEquals(first.claimToken(), second.claimToken());
        assertTrue(first.claimToken().contains("/nonce/"));
        assertTrue(second.claimToken().contains("/nonce/"));
    }

    @Test
    void persistedClaimTokenCannotBeRelabeledAcrossEffectOrAttempt() {
        var loop = new DurableDevLoopV1("run-token-tamper", 3);
        var store = started(loop);
        var claim = store.claimNext(
                "worker-a", 100L, 100L).orElseThrow();
        var state = store.persistentState();
        var original = state.effects().getFirst();

        assertEquals(claim.claimToken(), original.claimToken());

        var tamperedAttempt = new WorkflowJournalV1.EffectSnapshot(
                original.effectKey(),
                original.command(),
                original.state(),
                original.attempts(),
                original.workerId(),
                original.claimToken().replace(
                        "/attempt/1/",
                        "/attempt/2/"),
                original.leaseUntilMillis(),
                original.receiptRef(),
                original.reconciliationRef(),
                original.retryAllowed());

        assertThrows(
                IllegalStateException.class,
                () -> WorkflowJournalV1.restore(
                        new WorkflowJournalV1.PersistentState(
                                state.runId(),
                                state.maxIterations(),
                                state.nextSequence(),
                                state.nextClaimNumber(),
                                state.lastObservedMillis(),
                                state.journal(),
                                List.of(tamperedAttempt))));

        String originalToken = original.claimToken();
        char replacement =
                originalToken.charAt(originalToken.length() - 1) == '0'
                        ? '1'
                        : '0';
        var tamperedDigest = new WorkflowJournalV1.EffectSnapshot(
                original.effectKey(),
                original.command(),
                original.state(),
                original.attempts(),
                original.workerId(),
                originalToken.substring(
                        0, originalToken.length() - 1) + replacement,
                original.leaseUntilMillis(),
                original.receiptRef(),
                original.reconciliationRef(),
                original.retryAllowed());

        assertThrows(
                IllegalStateException.class,
                () -> WorkflowJournalV1.restore(
                        new WorkflowJournalV1.PersistentState(
                                state.runId(),
                                state.maxIterations(),
                                state.nextSequence(),
                                state.nextClaimNumber(),
                                state.lastObservedMillis(),
                                state.journal(),
                                List.of(tamperedDigest))));
    }

    @Test
    void persistedRetryAuthorityCannotOverrideJournalRevocation() {
        var loop = new DurableDevLoopV1("run-retry-tamper", 3);
        var store = started(loop);
        store.append(loop.accept(
                "cancel",
                new DurableDevLoopV1.Cancel("operator")));

        var state = store.persistentState();
        var effects = new java.util.ArrayList<>(state.effects());
        var original = effects.getFirst();
        assertFalse(original.retryAllowed());

        effects.set(
                0,
                new WorkflowJournalV1.EffectSnapshot(
                        original.effectKey(),
                        original.command(),
                        original.state(),
                        original.attempts(),
                        original.workerId(),
                        original.claimToken(),
                        original.leaseUntilMillis(),
                        original.receiptRef(),
                        original.reconciliationRef(),
                        true));

        assertThrows(
                IllegalStateException.class,
                () -> WorkflowJournalV1.restore(
                        new WorkflowJournalV1.PersistentState(
                                state.runId(),
                                state.maxIterations(),
                                state.nextSequence(),
                                state.nextClaimNumber(),
                                state.lastObservedMillis(),
                                state.journal(),
                                effects)));
    }

    @Test
    void outOfOrderJournalEntryFailsClosedWithoutPartialAppend() {
        var loop = new DurableDevLoopV1("run-order", 3);
        var first = loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal"));
        var second = loop.accept(
                "code",
                new DurableDevLoopV1.CodeChanged(1, "patch://1"));
        var store = new WorkflowJournalV1();

        assertThrows(
                IllegalStateException.class,
                () -> store.append(second));
        assertNull(store.snapshot().runId());
        assertEquals(0, store.snapshot().journalEntries());
        assertTrue(store.effects().isEmpty());

        store.append(first);
        store.append(second);
        assertEquals(2, store.snapshot().journalEntries());
    }

    @Test
    void nonAppliedTransitionCannotSmuggleSideEffectIntoOutbox() {
        var loop = new DurableDevLoopV1("run-rejected", 3);
        var rejected = loop.accept(
                "wrong-first-event",
                new DurableDevLoopV1.CodeChanged(
                        1, "patch://illegal"));
        assertEquals(
                DurableDevLoopV1.Disposition.REJECTED,
                rejected.journalEntry().disposition());

        var command = new DurableDevLoopV1.RequestCode(
                1, "goal", null, "effect/rejected");
        var forged = new DurableDevLoopV1.Transition(
                rejected.journalEntry(),
                rejected.snapshot(),
                List.of(command));
        var store = new WorkflowJournalV1();

        assertThrows(
                IllegalStateException.class,
                () -> store.append(forged));
        assertEquals(0, store.snapshot().journalEntries());
        assertTrue(store.effects().isEmpty());
    }

    private static WorkflowJournalV1 started(
            DurableDevLoopV1 loop) {
        var store = new WorkflowJournalV1();
        store.append(loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal")));
        return store;
    }

    private static void append(
            WorkflowJournalV1 store,
            DurableDevLoopV1.Transition transition) {
        store.append(transition);
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(40);
    }
}
