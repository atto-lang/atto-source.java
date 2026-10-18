package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowJournalV1Test {

    @Test
    void appendAtomicallyRegistersJournalEntryAndPendingEffect() {
        var loop = new DurableDevLoopV1("run-store", 3);
        var store = new WorkflowJournalV1();

        var transition = loop.accept(
                "start",
                new DurableDevLoopV1.Start("repair runtime"));
        store.append(transition);

        var snapshot = store.snapshot();
        assertEquals("run-store", snapshot.runId());
        assertEquals(1, snapshot.journalEntries());
        assertEquals(1, snapshot.pendingEffects());
        assertEquals(0, snapshot.claimedEffects());

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
    void claimedEffectCompletesWithReceiptAndIsNeverClaimedAgain() {
        var loop = new DurableDevLoopV1("run-complete", 3);
        var store = new WorkflowJournalV1();
        store.append(loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal")));

        var claim = store.claimNext(
                "worker-a", 1_000L, 100L).orElseThrow();
        store.complete(
                claim.effectKey(),
                claim.claimToken(),
                "receipt://model/1");

        var effect = store.effect(
                claim.effectKey()).orElseThrow();
        assertEquals(WorkflowJournalV1.EffectState.COMPLETED,
                effect.state());
        assertEquals("receipt://model/1", effect.receiptRef());
        assertTrue(store.claimNext(
                "worker-b", 1_050L, 100L).isEmpty());
    }

    @Test
    void expiredClaimBecomesAmbiguousAndNeverBlindlyRetries() {
        var loop = new DurableDevLoopV1("run-ambiguous", 3);
        var store = new WorkflowJournalV1();
        store.append(loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal")));

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
        var store = new WorkflowJournalV1();
        store.append(loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal")));

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
    void positivelyUnexecutedClaimCanBeReleasedWithoutAmbiguity() {
        var loop = new DurableDevLoopV1("run-release", 3);
        var store = new WorkflowJournalV1();
        store.append(loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal")));

        var first = store.claimNext(
                "worker-a", 100L, 100L).orElseThrow();
        store.releaseUnexecuted(
                first.effectKey(),
                first.claimToken(),
                "worker://sandbox/start-failed");

        var pending = store.effect(
                first.effectKey()).orElseThrow();
        assertEquals(WorkflowJournalV1.EffectState.PENDING,
                pending.state());

        var second = store.claimNext(
                "worker-b", 101L, 100L).orElseThrow();
        assertEquals(2, second.attempt());
        assertEquals(first.effectKey(), second.effectKey());
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

        var replayed = store.replayDevLoop(3);
        assertEquals(loop.snapshot(), replayed.machine().snapshot());
        assertEquals(loop.journal(), replayed.machine().journal());
        assertEquals(DurableDevLoopV1.Phase.CODING,
                replayed.machine().snapshot().phase());
        assertEquals(2, replayed.machine().snapshot().iteration());

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
    void outOfOrderJournalEntryFailsClosedWithoutPartialAppend() {
        var store = new WorkflowJournalV1();
        var command = new DurableDevLoopV1.RequestCode(
                1, "goal", null, "devloop/run-x/iteration/1/code");
        var bad = fakeTransition(
                "run-x", 2L, command);

        assertThrows(
                IllegalStateException.class,
                () -> store.append(bad));
        assertEquals(0, store.snapshot().journalEntries());
        assertTrue(store.effects().isEmpty());
    }

    @Test
    void stableEffectKeyCollisionFailsBeforeJournalMutation() {
        var store = new WorkflowJournalV1();
        var first = new DurableDevLoopV1.RequestCode(
                1, "goal", null, "effect/shared");
        var second = new DurableDevLoopV1.PushCode(
                1, "patch://different", "effect/shared");

        store.append(fakeTransition("run-collision", 1L, first));

        assertThrows(
                IllegalStateException.class,
                () -> store.append(fakeTransition(
                        "run-collision", 2L, second)));

        assertEquals(1, store.snapshot().journalEntries());
        assertEquals(1, store.effects().size());
        assertEquals(first, store.effects().getFirst().command());
    }

    @Test
    void nonAppliedTransitionCannotSmuggleSideEffectIntoOutbox() {
        var store = new WorkflowJournalV1();
        var command = new DurableDevLoopV1.RequestCode(
                1, "goal", null, "effect/rejected");

        var entry = new DurableDevLoopV1.JournalEntry(
                1L,
                "event-1",
                new DurableDevLoopV1.Start("goal"),
                DurableDevLoopV1.Disposition.REJECTED);
        var snapshot = fakeSnapshot("run-rejected", 1L);

        var transition = new DurableDevLoopV1.Transition(
                entry, snapshot, List.of(command));

        assertThrows(
                IllegalStateException.class,
                () -> store.append(transition));
        assertEquals(0, store.snapshot().journalEntries());
        assertTrue(store.effects().isEmpty());
    }

    private static void append(
            WorkflowJournalV1 store,
            DurableDevLoopV1.Transition transition) {
        store.append(transition);
    }

    private static DurableDevLoopV1.Transition fakeTransition(
            String runId,
            long sequence,
            DurableDevLoopV1.Command command) {
        var entry = new DurableDevLoopV1.JournalEntry(
                sequence,
                "event-" + sequence,
                new DurableDevLoopV1.Start("goal"),
                DurableDevLoopV1.Disposition.APPLIED);
        return new DurableDevLoopV1.Transition(
                entry,
                fakeSnapshot(runId, sequence),
                List.of(command));
    }

    private static DurableDevLoopV1.Snapshot fakeSnapshot(
            String runId,
            long sequence) {
        return new DurableDevLoopV1.Snapshot(
                runId,
                DurableDevLoopV1.Phase.CODING,
                1,
                3,
                "goal",
                null,
                null,
                null,
                null,
                null,
                null,
                sequence + 1L,
                (int) sequence,
                (int) sequence);
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(40);
    }
}
