package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class DurableDevLoopCoordinatorV1Test {

    @TempDir
    Path tempDir;

    @Test
    void inMemoryCoordinatorBindsEffectReceiptAndOutcomeAtomically() {
        var store = new WorkflowJournalV1();
        try (var coordinator = new DurableDevLoopCoordinatorV1(
                store, "run-coordinator", 3)) {
            var start = coordinator.accept(
                    "start",
                    new DurableDevLoopV1.Start("repair parser"));
            assertEquals(
                    DurableDevLoopV1.Phase.CODING,
                    start.snapshot().phase());

            var claim = coordinator.claimNextEffect(
                    "coder", 100L, 100L).orElseThrow();
            assertInstanceOf(
                    DurableDevLoopV1.RequestCode.class,
                    claim.command());

            var outcome = coordinator.completeEffect(
                    claim.effectKey(),
                    claim.claimToken(),
                    "receipt://code/1",
                    150L,
                    "code-1",
                    new DurableDevLoopV1.CodeChanged(
                            1, "patch://one"));

            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    outcome.snapshot().phase());

            var completed = store.effect(
                    claim.effectKey()).orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.COMPLETED,
                    completed.state());
            assertEquals(
                    "receipt://code/1",
                    completed.receiptRef());

            var projection = coordinator.projection();
            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    projection.phase());
            assertEquals(1, projection.pendingEffects());
        }
    }

    @Test
    void sqliteCoordinatorSurvivesCloseAndContinuesRun() {
        Path db = tempDir.resolve("coordinator.db");
        WorkflowJournalV1.Claim claim;

        try (var coordinator = new DurableDevLoopCoordinatorV1(
                SqliteWorkflowJournalV1.open(db),
                "run-sqlite-coordinator",
                4)) {
            coordinator.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal"));
            claim = coordinator.claimNextEffect(
                    "coder", 100L, 100L).orElseThrow();
        }

        try (var coordinator = new DurableDevLoopCoordinatorV1(
                SqliteWorkflowJournalV1.open(db),
                "run-sqlite-coordinator",
                4)) {
            var transition = coordinator.completeEffect(
                    claim.effectKey(),
                    claim.claimToken(),
                    "receipt://code/recovered",
                    150L,
                    "code-1",
                    new DurableDevLoopV1.CodeChanged(
                            1, "patch://recovered"));

            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    transition.snapshot().phase());
            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    coordinator.workflowSnapshot().phase());
            assertEquals(
                    2L,
                    coordinator.projection()
                            .lastTransitionSequence());
        }
    }

    @Test
    void directExternalOutcomeRetiresWaitEffectWithoutFakeReceipt() {
        var store = new WorkflowJournalV1();
        try (var coordinator = new DurableDevLoopCoordinatorV1(
                store, "run-external", 3)) {
            String sha = sha('a');
            coordinator.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal"));

            var codeClaim = coordinator.claimNextEffect(
                    "coder", 100L, 100L).orElseThrow();
            coordinator.completeEffect(
                    codeClaim.effectKey(),
                    codeClaim.claimToken(),
                    "receipt://code",
                    150L,
                    "code",
                    new DurableDevLoopV1.CodeChanged(
                            1, "patch://1"));

            var pushClaim = coordinator.claimNextEffect(
                    "git", 151L, 100L).orElseThrow();
            coordinator.completeEffect(
                    pushClaim.effectKey(),
                    pushClaim.claimToken(),
                    "receipt://push",
                    180L,
                    "push",
                    new DurableDevLoopV1.PushRecorded(
                            1, sha));

            String waitKey =
                    "devloop/run-external/iteration/1/ci/" + sha;
            assertEquals(
                    WorkflowJournalV1.EffectState.PENDING,
                    store.effect(waitKey).orElseThrow().state());

            coordinator.accept(
                    "github-delivery-1",
                    new DurableDevLoopV1.CiCompleted(
                            sha,
                            DurableDevLoopV1.CiConclusion.SUCCESS,
                            "artifact://ci/1"));

            var wait = store.effect(waitKey).orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.ABANDONED,
                    wait.state());
            assertFalse(wait.retryAllowed());
            assertEquals(
                    DurableDevLoopV1.Phase.EVALUATING,
                    coordinator.workflowSnapshot().phase());
        }
    }

    @Test
    void coordinatorRejectsRunIdentityMismatchOnExistingStore() {
        var store = new WorkflowJournalV1();
        var loop = new DurableDevLoopV1("run-a", 3);
        store.append(loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal")));

        assertThrows(
                IllegalStateException.class,
                () -> new DurableDevLoopCoordinatorV1(
                        store, "run-b", 3));
    }

    @Test
    void coordinatorRejectsPolicyMismatchOnExistingStore() {
        var store = new WorkflowJournalV1();
        var loop = new DurableDevLoopV1("run-policy", 3);
        store.append(loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal")));

        assertThrows(
                IllegalStateException.class,
                () -> new DurableDevLoopCoordinatorV1(
                        store, "run-policy", 4));
    }

    @Test
    void closedCoordinatorRejectsFurtherCommands() {
        var coordinator = new DurableDevLoopCoordinatorV1(
                new WorkflowJournalV1(),
                "run-closed",
                3);
        coordinator.close();

        assertThrows(
                IllegalStateException.class,
                () -> coordinator.accept(
                        "start",
                        new DurableDevLoopV1.Start("goal")));
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(40);
    }
}
