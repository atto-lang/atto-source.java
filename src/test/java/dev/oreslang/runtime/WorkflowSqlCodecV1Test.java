package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowSqlCodecV1Test {

    @Test
    void allEventsRoundTripCanonically() {
        String sha = sha('a');
        List<DurableDevLoopV1.Event> events = List.of(
                new DurableDevLoopV1.Start("goal"),
                new DurableDevLoopV1.CodeChanged(2, "patch://2"),
                new DurableDevLoopV1.PushRecorded(2, sha),
                new DurableDevLoopV1.CiCompleted(
                        sha,
                        DurableDevLoopV1.CiConclusion.SUCCESS,
                        "artifact://ci"),
                new DurableDevLoopV1.EvaluationPassed(sha),
                new DurableDevLoopV1.EvaluationFailed(
                        sha, "review://failure"),
                new DurableDevLoopV1.BugReportCreated(
                        2, sha, "bug://2"),
                new DurableDevLoopV1.ApprovalGranted(sha),
                new DurableDevLoopV1.Cancel("operator"));

        for (DurableDevLoopV1.Event event : events) {
            var encoded = WorkflowSqlCodecV1.encodeEvent(event);
            assertEquals(
                    event,
                    WorkflowSqlCodecV1.decodeEvent(encoded),
                    event.getClass().getSimpleName());
        }
    }

    @Test
    void allCommandsRoundTripCanonically() {
        String sha = sha('b');
        List<DurableDevLoopV1.Command> commands = List.of(
                new DurableDevLoopV1.RequestCode(
                        2, "goal", null, "effect/code"),
                new DurableDevLoopV1.RequestCode(
                        2, "goal", "bug://1", "effect/code-feedback"),
                new DurableDevLoopV1.PushCode(
                        2, "patch://2", "effect/push"),
                new DurableDevLoopV1.AwaitCi(
                        2, sha, "effect/ci"),
                new DurableDevLoopV1.Evaluate(
                        2, sha, "artifact://ci", "effect/evaluate"),
                new DurableDevLoopV1.CreateBugReport(
                        2,
                        sha,
                        "artifact://ci",
                        "review://failure",
                        "effect/bug"),
                new DurableDevLoopV1.RequestApproval(
                        2, sha, "effect/approval"),
                new DurableDevLoopV1.Escalate(
                        2, "budget", "effect/escalate"),
                new DurableDevLoopV1.CancelWork(
                        "operator", "effect/cancel"));

        for (DurableDevLoopV1.Command command : commands) {
            var encoded = WorkflowSqlCodecV1.encodeCommand(command);
            assertEquals(
                    command,
                    WorkflowSqlCodecV1.decodeCommand(encoded),
                    command.getClass().getSimpleName());
        }
    }

    @Test
    void eventDecoderRejectsPopulatedUnusedColumns() {
        var encoded = new WorkflowSqlCodecV1.Encoded(
                "start",
                99,
                "goal",
                null,
                null,
                null);

        assertThrows(
                IllegalStateException.class,
                () -> WorkflowSqlCodecV1.decodeEvent(encoded));
    }

    @Test
    void commandDecoderRejectsPopulatedUnusedColumns() {
        var encoded = new WorkflowSqlCodecV1.Encoded(
                "push_code",
                1,
                "patch://1",
                "effect/push",
                "ignored",
                null);

        assertThrows(
                IllegalStateException.class,
                () -> WorkflowSqlCodecV1.decodeCommand(encoded));
    }

    @Test
    void ciConclusionPersistenceIsCanonicalAndCaseSensitive() {
        var encoded = new WorkflowSqlCodecV1.Encoded(
                "ci_completed",
                null,
                sha('c'),
                "success",
                "artifact://ci",
                null);

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowSqlCodecV1.decodeEvent(encoded));
    }

    @Test
    void optionalRequestCodeFeedbackRejectsBlankWhenPresent() {
        var encoded = new WorkflowSqlCodecV1.Encoded(
                "request_code",
                1,
                "goal",
                " ",
                "effect/code",
                null);

        assertThrows(
                IllegalStateException.class,
                () -> WorkflowSqlCodecV1.decodeCommand(encoded));
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(40);
    }
}
