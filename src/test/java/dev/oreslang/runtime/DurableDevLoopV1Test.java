package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class DurableDevLoopV1Test {

    @Test
    void happyPathGatesCompletionOnExactCommitAndApproval() {
        var loop = new DurableDevLoopV1("run-42", 5);
        String sha = sha('a');

        var start = loop.accept(
                "goal-1",
                new DurableDevLoopV1.Start("fix actor scheduling"));
        assertEquals(DurableDevLoopV1.Phase.CODING,
                start.snapshot().phase());
        assertInstanceOf(
                DurableDevLoopV1.RequestCode.class,
                only(start.commands()));

        var changed = loop.accept(
                "code-1",
                new DurableDevLoopV1.CodeChanged(1, "patch://one"));
        assertEquals(DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                changed.snapshot().phase());
        var push = assertInstanceOf(
                DurableDevLoopV1.PushCode.class,
                only(changed.commands()));
        assertEquals(
                "devloop/run-42/iteration/1/push",
                push.effectKey());

        var pushed = loop.accept(
                "push-1",
                new DurableDevLoopV1.PushRecorded(1, sha));
        assertEquals(DurableDevLoopV1.Phase.WAITING_FOR_CI,
                pushed.snapshot().phase());
        assertEquals(sha, pushed.snapshot().expectedCommitSha());

        var ci = loop.accept(
                "gh-delivery-1",
                new DurableDevLoopV1.CiCompleted(
                        sha,
                        DurableDevLoopV1.CiConclusion.SUCCESS,
                        "artifact://tests/1"));
        assertEquals(DurableDevLoopV1.Phase.EVALUATING,
                ci.snapshot().phase());
        assertInstanceOf(
                DurableDevLoopV1.Evaluate.class,
                only(ci.commands()));

        var evaluated = loop.accept(
                "eval-1",
                new DurableDevLoopV1.EvaluationPassed(sha));
        assertEquals(DurableDevLoopV1.Phase.WAITING_FOR_APPROVAL,
                evaluated.snapshot().phase());
        assertInstanceOf(
                DurableDevLoopV1.RequestApproval.class,
                only(evaluated.commands()));

        var approved = loop.accept(
                "approval-1",
                new DurableDevLoopV1.ApprovalGranted(sha));
        assertEquals(DurableDevLoopV1.Phase.COMPLETED,
                approved.snapshot().phase());
        assertTrue(approved.commands().isEmpty());
    }

    @Test
    void staleCiForDifferentCommitIsAuditedButCannotAdvanceRun() {
        var loop = readyForCi("run-stale", 3, sha('a'));

        var stale = loop.accept(
                "gh-old-delivery",
                new DurableDevLoopV1.CiCompleted(
                        sha('b'),
                        DurableDevLoopV1.CiConclusion.SUCCESS,
                        "artifact://old"));
        assertEquals(DurableDevLoopV1.Disposition.STALE,
                stale.journalEntry().disposition());
        assertEquals(DurableDevLoopV1.Phase.WAITING_FOR_CI,
                stale.snapshot().phase());
        assertEquals(sha('a'), stale.snapshot().expectedCommitSha());
        assertTrue(stale.commands().isEmpty());

        var current = loop.accept(
                "gh-current-delivery",
                new DurableDevLoopV1.CiCompleted(
                        sha('a'),
                        DurableDevLoopV1.CiConclusion.SUCCESS,
                        "artifact://current"));
        assertEquals(DurableDevLoopV1.Disposition.APPLIED,
                current.journalEntry().disposition());
        assertEquals(DurableDevLoopV1.Phase.EVALUATING,
                current.snapshot().phase());
    }

    @Test
    void exactDuplicateDeliveryIdAndPayloadIsNoOp() {
        var loop = readyForCi("run-dupe", 3, sha('a'));
        var event = new DurableDevLoopV1.CiCompleted(
                sha('a'),
                DurableDevLoopV1.CiConclusion.SUCCESS,
                "artifact://tests");

        var first = loop.accept(
                "gh-delivery-77",
                event);
        assertEquals(DurableDevLoopV1.Phase.EVALUATING,
                first.snapshot().phase());

        var duplicate = loop.accept(
                "gh-delivery-77",
                event);
        assertEquals(DurableDevLoopV1.Disposition.DUPLICATE,
                duplicate.journalEntry().disposition());
        assertEquals(DurableDevLoopV1.Phase.EVALUATING,
                duplicate.snapshot().phase());
        assertTrue(duplicate.commands().isEmpty());
        assertEquals(5, duplicate.snapshot().journalEntries());
        assertEquals(4, duplicate.snapshot().uniqueDeliveryIds());
    }


    @Test
    void reusedDeliveryIdWithDifferentPayloadIsConflictNotDuplicate() {
        var loop = readyForCi("run-conflict", 3, sha('a'));

        var first = loop.accept(
                "gh-delivery-conflict",
                new DurableDevLoopV1.CiCompleted(
                        sha('a'),
                        DurableDevLoopV1.CiConclusion.SUCCESS,
                        "artifact://tests/current"));
        assertEquals(DurableDevLoopV1.Disposition.APPLIED,
                first.journalEntry().disposition());

        var conflict = loop.accept(
                "gh-delivery-conflict",
                new DurableDevLoopV1.EvaluationPassed(sha('a')));
        assertEquals(DurableDevLoopV1.Disposition.CONFLICT,
                conflict.journalEntry().disposition());
        assertEquals(DurableDevLoopV1.Phase.EVALUATING,
                conflict.snapshot().phase());
        assertTrue(conflict.commands().isEmpty());
        assertEquals(4, conflict.snapshot().uniqueDeliveryIds());
    }

    @Test
    void phaseRejectedDeliveryCanBeRetriedWithSamePayloadLater() {
        var loop = new DurableDevLoopV1("run-early", 3);
        String sha = sha('a');
        var early = new DurableDevLoopV1.ApprovalGranted(sha);

        loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal"));
        loop.accept(
                "code",
                new DurableDevLoopV1.CodeChanged(
                        1, "patch://1"));
        loop.accept(
                "push",
                new DurableDevLoopV1.PushRecorded(
                        1, sha));

        var rejected = loop.accept(
                "approval-delivery",
                early);
        assertEquals(
                DurableDevLoopV1.Disposition.REJECTED,
                rejected.journalEntry().disposition());

        loop.accept(
                "ci",
                new DurableDevLoopV1.CiCompleted(
                        sha,
                        DurableDevLoopV1.CiConclusion.SUCCESS,
                        "artifact://tests"));
        loop.accept(
                "eval",
                new DurableDevLoopV1.EvaluationPassed(sha));

        var accepted = loop.accept(
                "approval-delivery",
                early);
        assertEquals(
                DurableDevLoopV1.Disposition.APPLIED,
                accepted.journalEntry().disposition());
        assertEquals(
                DurableDevLoopV1.Phase.COMPLETED,
                accepted.snapshot().phase());
    }

    @Test
    void rejectedDeliveryIdStillRejectsChangedPayloadAsConflict() {
        var loop = new DurableDevLoopV1("run-early-conflict", 3);
        String sha = sha('a');

        loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal"));
        var rejected = loop.accept(
                "shared-id",
                new DurableDevLoopV1.ApprovalGranted(sha));
        assertEquals(
                DurableDevLoopV1.Disposition.REJECTED,
                rejected.journalEntry().disposition());

        var conflict = loop.accept(
                "shared-id",
                new DurableDevLoopV1.Cancel("changed payload"));
        assertEquals(
                DurableDevLoopV1.Disposition.CONFLICT,
                conflict.journalEntry().disposition());
        assertEquals(
                DurableDevLoopV1.Phase.CODING,
                conflict.snapshot().phase());
    }

    @Test
    void failedCiProducesBugReportThenFeedsNextCodingIteration() {
        var loop = readyForCi("run-loop", 3, sha('a'));

        var failed = loop.accept(
                "ci-fail-1",
                new DurableDevLoopV1.CiCompleted(
                        sha('a'),
                        DurableDevLoopV1.CiConclusion.FAILURE,
                        "artifact://tests/failing"));
        assertEquals(DurableDevLoopV1.Phase.WRITING_BUG_REPORT,
                failed.snapshot().phase());
        var createBug = assertInstanceOf(
                DurableDevLoopV1.CreateBugReport.class,
                only(failed.commands()));
        assertEquals("ci:failure", createBug.reasonRef());

        var bug = loop.accept(
                "bug-1",
                new DurableDevLoopV1.BugReportCreated(
                        1, sha('a'), "bug://report/1"));
        assertEquals(DurableDevLoopV1.Phase.CODING,
                bug.snapshot().phase());
        assertEquals(2, bug.snapshot().iteration());
        assertNull(bug.snapshot().expectedCommitSha());

        var next = assertInstanceOf(
                DurableDevLoopV1.RequestCode.class,
                only(bug.commands()));
        assertEquals(2, next.iteration());
        assertEquals("bug://report/1", next.feedbackRef());
    }

    @Test
    void iterationBudgetEscalatesInsteadOfStartingUnboundedLoop() {
        var loop = readyForCi("run-budget", 1, sha('a'));
        loop.accept(
                "ci-fail",
                new DurableDevLoopV1.CiCompleted(
                        sha('a'),
                        DurableDevLoopV1.CiConclusion.FAILURE,
                        "artifact://tests/failing"));

        var exhausted = loop.accept(
                "bug-created",
                new DurableDevLoopV1.BugReportCreated(
                        1, sha('a'), "bug://terminal"));
        assertEquals(DurableDevLoopV1.Phase.FAILED,
                exhausted.snapshot().phase());

        var escalation = assertInstanceOf(
                DurableDevLoopV1.Escalate.class,
                only(exhausted.commands()));
        assertEquals(1, escalation.iteration());
        assertTrue(escalation.reason().contains("budget exhausted"));

        var late = loop.accept(
                "late-code",
                new DurableDevLoopV1.CodeChanged(1, "patch://late"));
        assertEquals(DurableDevLoopV1.Disposition.REJECTED,
                late.journalEntry().disposition());
        assertEquals(DurableDevLoopV1.Phase.FAILED,
                late.snapshot().phase());
    }

    @Test
    void evaluationFailureUsesExistingTestEvidenceAndLoopsThroughBugReport() {
        var loop = readyForCi("run-eval", 2, sha('c'));
        loop.accept(
                "ci-ok",
                new DurableDevLoopV1.CiCompleted(
                        sha('c'),
                        DurableDevLoopV1.CiConclusion.SUCCESS,
                        "artifact://tests/ok"));

        var failed = loop.accept(
                "eval-fail",
                new DurableDevLoopV1.EvaluationFailed(
                        sha('c'), "review://acceptance/mismatch"));
        assertEquals(DurableDevLoopV1.Phase.WRITING_BUG_REPORT,
                failed.snapshot().phase());
        var command = assertInstanceOf(
                DurableDevLoopV1.CreateBugReport.class,
                only(failed.commands()));
        assertEquals("artifact://tests/ok", command.reportRef());
        assertEquals(
                "review://acceptance/mismatch",
                command.reasonRef());
    }

    @Test
    void replayReconstructsStateAndRegeneratesStableEffectKeys() {
        var loop = new DurableDevLoopV1("run-replay", 4);
        String sha = sha('d');
        List<DurableDevLoopV1.Command> originalCommands =
                new ArrayList<>();

        originalCommands.addAll(loop.accept(
                "start",
                new DurableDevLoopV1.Start("repair parser")).commands());
        originalCommands.addAll(loop.accept(
                "code",
                new DurableDevLoopV1.CodeChanged(
                        1, "patch://parser")).commands());
        originalCommands.addAll(loop.accept(
                "push",
                new DurableDevLoopV1.PushRecorded(
                        1, sha)).commands());
        originalCommands.addAll(loop.accept(
                "stale-ci",
                new DurableDevLoopV1.CiCompleted(
                        sha('e'),
                        DurableDevLoopV1.CiConclusion.SUCCESS,
                        "artifact://stale")).commands());
        originalCommands.addAll(loop.accept(
                "current-ci",
                new DurableDevLoopV1.CiCompleted(
                        sha,
                        DurableDevLoopV1.CiConclusion.SUCCESS,
                        "artifact://current")).commands());
        originalCommands.addAll(loop.accept(
                "current-ci",
                new DurableDevLoopV1.CiCompleted(
                        sha,
                        DurableDevLoopV1.CiConclusion.FAILURE,
                        "artifact://mutated-duplicate")).commands());

        var replay = DurableDevLoopV1.replay(
                "run-replay", 4, loop.journal());

        assertEquals(loop.snapshot(), replay.machine().snapshot());
        assertEquals(loop.journal(), replay.machine().journal());
        assertEquals(originalCommands, replay.regeneratedCommands());
    }

    @Test
    void cancellationBeforeStartIsTerminalAndEmitsStableCancelEffect() {
        var loop = new DurableDevLoopV1("run-cancel", 3);

        var cancelled = loop.accept(
                "cancel-1",
                new DurableDevLoopV1.Cancel("operator request"));
        assertEquals(DurableDevLoopV1.Phase.CANCELLED,
                cancelled.snapshot().phase());
        var command = assertInstanceOf(
                DurableDevLoopV1.CancelWork.class,
                only(cancelled.commands()));
        assertEquals("devloop/run-cancel/cancel", command.effectKey());

        var start = loop.accept(
                "start-after-cancel",
                new DurableDevLoopV1.Start("must not run"));
        assertEquals(DurableDevLoopV1.Disposition.REJECTED,
                start.journalEntry().disposition());
        assertEquals(DurableDevLoopV1.Phase.CANCELLED,
                start.snapshot().phase());
    }

    @Test
    void concurrentExactDuplicateStartDeliveryAppliesExactlyOnce() throws Exception {
        var loop = new DurableDevLoopV1("run-concurrent-delivery", 3);
        var event = new DurableDevLoopV1.Start("goal");
        int callers = 32;

        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<
                    DurableDevLoopV1.Transition>>();

            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return loop.accept("same-delivery", event);
                }));
            }
            start.countDown();

            int applied = 0;
            int duplicates = 0;
            var sequences = new java.util.TreeSet<Long>();
            for (var future : futures) {
                var transition = future.get(5, TimeUnit.SECONDS);
                sequences.add(transition.journalEntry().sequence());
                if (transition.journalEntry().disposition()
                        == DurableDevLoopV1.Disposition.APPLIED) {
                    applied++;
                } else if (transition.journalEntry().disposition()
                        == DurableDevLoopV1.Disposition.DUPLICATE) {
                    duplicates++;
                }
            }

            assertEquals(1, applied);
            assertEquals(callers - 1, duplicates);
            assertEquals(callers, sequences.size());
            assertEquals(1L, sequences.first());
            assertEquals((long) callers, sequences.last());
            assertEquals(DurableDevLoopV1.Phase.CODING,
                    loop.snapshot().phase());
        }
    }

    @Test
    void malformedCommitIdentifiersFailBeforeTheyReachStateMachine() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DurableDevLoopV1.PushRecorded(
                        1, "main"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DurableDevLoopV1.CiCompleted(
                        "1234",
                        DurableDevLoopV1.CiConclusion.SUCCESS,
                        "artifact://x"));
    }

    private static DurableDevLoopV1 readyForCi(
            String runId,
            int maxIterations,
            String commitSha) {
        var loop = new DurableDevLoopV1(runId, maxIterations);
        loop.accept("start", new DurableDevLoopV1.Start("goal"));
        loop.accept(
                "code",
                new DurableDevLoopV1.CodeChanged(1, "patch://1"));
        loop.accept(
                "push",
                new DurableDevLoopV1.PushRecorded(1, commitSha));
        return loop;
    }

    private static DurableDevLoopV1.Command only(
            List<DurableDevLoopV1.Command> commands) {
        assertEquals(1, commands.size());
        return commands.getFirst();
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(40);
    }
}
