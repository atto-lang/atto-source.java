package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

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
