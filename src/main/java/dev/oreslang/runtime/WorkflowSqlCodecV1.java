package dev.oreslang.runtime;

import java.util.Objects;

/** Explicit versioned row codec for durable dev-loop events and commands. */
final class WorkflowSqlCodecV1 {

    record Encoded(
            String type,
            Integer int1,
            String text1,
            String text2,
            String text3,
            String text4) {
        Encoded {
            type = requireText(type, "type");
        }
    }

    private WorkflowSqlCodecV1() { }

    static Encoded encodeEvent(DurableDevLoopV1.Event event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof DurableDevLoopV1.Start value) {
            return new Encoded("start", null, value.goal(), null, null, null);
        }
        if (event instanceof DurableDevLoopV1.CodeChanged value) {
            return new Encoded(
                    "code_changed",
                    value.iteration(),
                    value.patchRef(),
                    null,
                    null,
                    null);
        }
        if (event instanceof DurableDevLoopV1.PushRecorded value) {
            return new Encoded(
                    "push_recorded",
                    value.iteration(),
                    value.commitSha(),
                    null,
                    null,
                    null);
        }
        if (event instanceof DurableDevLoopV1.CiCompleted value) {
            return new Encoded(
                    "ci_completed",
                    null,
                    value.commitSha(),
                    value.conclusion().name(),
                    value.reportRef(),
                    null);
        }
        if (event instanceof DurableDevLoopV1.EvaluationPassed value) {
            return new Encoded(
                    "evaluation_passed",
                    null,
                    value.commitSha(),
                    null,
                    null,
                    null);
        }
        if (event instanceof DurableDevLoopV1.EvaluationFailed value) {
            return new Encoded(
                    "evaluation_failed",
                    null,
                    value.commitSha(),
                    value.reasonRef(),
                    null,
                    null);
        }
        if (event instanceof DurableDevLoopV1.BugReportCreated value) {
            return new Encoded(
                    "bug_report_created",
                    value.iteration(),
                    value.commitSha(),
                    value.bugReportRef(),
                    null,
                    null);
        }
        if (event instanceof DurableDevLoopV1.ApprovalGranted value) {
            return new Encoded(
                    "approval_granted",
                    null,
                    value.commitSha(),
                    null,
                    null,
                    null);
        }
        if (event instanceof DurableDevLoopV1.Cancel value) {
            return new Encoded(
                    "cancel",
                    null,
                    value.reason(),
                    null,
                    null,
                    null);
        }
        throw new IllegalArgumentException(
                "unsupported workflow event " + event.getClass().getName());
    }

    static DurableDevLoopV1.Event decodeEvent(Encoded encoded) {
        Objects.requireNonNull(encoded, "encoded");
        validateEventShape(encoded);
        return switch (encoded.type()) {
            case "start" ->
                    new DurableDevLoopV1.Start(encoded.text1());
            case "code_changed" ->
                    new DurableDevLoopV1.CodeChanged(
                            requireInt(encoded.int1(), "iteration"),
                            encoded.text1());
            case "push_recorded" ->
                    new DurableDevLoopV1.PushRecorded(
                            requireInt(encoded.int1(), "iteration"),
                            encoded.text1());
            case "ci_completed" ->
                    new DurableDevLoopV1.CiCompleted(
                            encoded.text1(),
                            DurableDevLoopV1.CiConclusion.valueOf(
                                    requireText(
                                            encoded.text2(),
                                            "ci conclusion")),
                            encoded.text3());
            case "evaluation_passed" ->
                    new DurableDevLoopV1.EvaluationPassed(encoded.text1());
            case "evaluation_failed" ->
                    new DurableDevLoopV1.EvaluationFailed(
                            encoded.text1(), encoded.text2());
            case "bug_report_created" ->
                    new DurableDevLoopV1.BugReportCreated(
                            requireInt(encoded.int1(), "iteration"),
                            encoded.text1(),
                            encoded.text2());
            case "approval_granted" ->
                    new DurableDevLoopV1.ApprovalGranted(encoded.text1());
            case "cancel" ->
                    new DurableDevLoopV1.Cancel(encoded.text1());
            default -> throw new IllegalStateException(
                    "unknown persisted workflow event type "
                            + encoded.type());
        };
    }

    static Encoded encodeCommand(DurableDevLoopV1.Command command) {
        Objects.requireNonNull(command, "command");
        if (command instanceof DurableDevLoopV1.RequestCode value) {
            return new Encoded(
                    "request_code",
                    value.iteration(),
                    value.goal(),
                    value.feedbackRef(),
                    value.effectKey(),
                    null);
        }
        if (command instanceof DurableDevLoopV1.PushCode value) {
            return new Encoded(
                    "push_code",
                    value.iteration(),
                    value.patchRef(),
                    value.effectKey(),
                    null,
                    null);
        }
        if (command instanceof DurableDevLoopV1.AwaitCi value) {
            return new Encoded(
                    "await_ci",
                    value.iteration(),
                    value.commitSha(),
                    value.effectKey(),
                    null,
                    null);
        }
        if (command instanceof DurableDevLoopV1.Evaluate value) {
            return new Encoded(
                    "evaluate",
                    value.iteration(),
                    value.commitSha(),
                    value.reportRef(),
                    value.effectKey(),
                    null);
        }
        if (command instanceof DurableDevLoopV1.CreateBugReport value) {
            return new Encoded(
                    "create_bug_report",
                    value.iteration(),
                    value.commitSha(),
                    value.reportRef(),
                    value.reasonRef(),
                    value.effectKey());
        }
        if (command instanceof DurableDevLoopV1.RequestApproval value) {
            return new Encoded(
                    "request_approval",
                    value.iteration(),
                    value.commitSha(),
                    value.effectKey(),
                    null,
                    null);
        }
        if (command instanceof DurableDevLoopV1.Escalate value) {
            return new Encoded(
                    "escalate",
                    value.iteration(),
                    value.reason(),
                    value.effectKey(),
                    null,
                    null);
        }
        if (command instanceof DurableDevLoopV1.CancelWork value) {
            return new Encoded(
                    "cancel_work",
                    null,
                    value.reason(),
                    value.effectKey(),
                    null,
                    null);
        }
        throw new IllegalArgumentException(
                "unsupported workflow command "
                        + command.getClass().getName());
    }

    static DurableDevLoopV1.Command decodeCommand(Encoded encoded) {
        Objects.requireNonNull(encoded, "encoded");
        validateCommandShape(encoded);
        return switch (encoded.type()) {
            case "request_code" ->
                    new DurableDevLoopV1.RequestCode(
                            requireInt(encoded.int1(), "iteration"),
                            encoded.text1(),
                            encoded.text2(),
                            encoded.text3());
            case "push_code" ->
                    new DurableDevLoopV1.PushCode(
                            requireInt(encoded.int1(), "iteration"),
                            encoded.text1(),
                            encoded.text2());
            case "await_ci" ->
                    new DurableDevLoopV1.AwaitCi(
                            requireInt(encoded.int1(), "iteration"),
                            encoded.text1(),
                            encoded.text2());
            case "evaluate" ->
                    new DurableDevLoopV1.Evaluate(
                            requireInt(encoded.int1(), "iteration"),
                            encoded.text1(),
                            encoded.text2(),
                            encoded.text3());
            case "create_bug_report" ->
                    new DurableDevLoopV1.CreateBugReport(
                            requireInt(encoded.int1(), "iteration"),
                            encoded.text1(),
                            encoded.text2(),
                            encoded.text3(),
                            encoded.text4());
            case "request_approval" ->
                    new DurableDevLoopV1.RequestApproval(
                            requireInt(encoded.int1(), "iteration"),
                            encoded.text1(),
                            encoded.text2());
            case "escalate" ->
                    new DurableDevLoopV1.Escalate(
                            requireInt(encoded.int1(), "iteration"),
                            encoded.text1(),
                            encoded.text2());
            case "cancel_work" ->
                    new DurableDevLoopV1.CancelWork(
                            encoded.text1(), encoded.text2());
            default -> throw new IllegalStateException(
                    "unknown persisted workflow command type "
                            + encoded.type());
        };
    }

    private static void validateEventShape(Encoded e) {
        switch (e.type()) {
            case "start" -> {
                requireNull(e.int1(), "start.int1");
                requireText(e.text1(), "start.goal");
                requireNull(e.text2(), "start.text2");
                requireNull(e.text3(), "start.text3");
                requireNull(e.text4(), "start.text4");
            }
            case "code_changed", "push_recorded" -> {
                requireInt(e.int1(), e.type() + ".iteration");
                requireText(e.text1(), e.type() + ".text1");
                requireNull(e.text2(), e.type() + ".text2");
                requireNull(e.text3(), e.type() + ".text3");
                requireNull(e.text4(), e.type() + ".text4");
            }
            case "ci_completed" -> {
                requireNull(e.int1(), "ci_completed.int1");
                requireText(e.text1(), "ci_completed.commit");
                requireText(e.text2(), "ci_completed.conclusion");
                requireText(e.text3(), "ci_completed.report");
                requireNull(e.text4(), "ci_completed.text4");
            }
            case "evaluation_passed", "approval_granted", "cancel" -> {
                requireNull(e.int1(), e.type() + ".int1");
                requireText(e.text1(), e.type() + ".text1");
                requireNull(e.text2(), e.type() + ".text2");
                requireNull(e.text3(), e.type() + ".text3");
                requireNull(e.text4(), e.type() + ".text4");
            }
            case "evaluation_failed" -> {
                requireNull(e.int1(), "evaluation_failed.int1");
                requireText(e.text1(), "evaluation_failed.commit");
                requireText(e.text2(), "evaluation_failed.reason");
                requireNull(e.text3(), "evaluation_failed.text3");
                requireNull(e.text4(), "evaluation_failed.text4");
            }
            case "bug_report_created" -> {
                requireInt(e.int1(), "bug_report_created.iteration");
                requireText(e.text1(), "bug_report_created.commit");
                requireText(e.text2(), "bug_report_created.report");
                requireNull(e.text3(), "bug_report_created.text3");
                requireNull(e.text4(), "bug_report_created.text4");
            }
            default -> throw new IllegalStateException(
                    "unknown persisted workflow event type " + e.type());
        }
    }

    private static void validateCommandShape(Encoded e) {
        switch (e.type()) {
            case "request_code" -> {
                requireInt(e.int1(), "request_code.iteration");
                requireText(e.text1(), "request_code.goal");
                optionalText(e.text2(), "request_code.feedback");
                requireText(e.text3(), "request_code.effectKey");
                requireNull(e.text4(), "request_code.text4");
            }
            case "push_code", "await_ci", "request_approval", "escalate" -> {
                requireInt(e.int1(), e.type() + ".iteration");
                requireText(e.text1(), e.type() + ".text1");
                requireText(e.text2(), e.type() + ".effectKey");
                requireNull(e.text3(), e.type() + ".text3");
                requireNull(e.text4(), e.type() + ".text4");
            }
            case "evaluate" -> {
                requireInt(e.int1(), "evaluate.iteration");
                requireText(e.text1(), "evaluate.commit");
                requireText(e.text2(), "evaluate.report");
                requireText(e.text3(), "evaluate.effectKey");
                requireNull(e.text4(), "evaluate.text4");
            }
            case "create_bug_report" -> {
                requireInt(e.int1(), "create_bug_report.iteration");
                requireText(e.text1(), "create_bug_report.commit");
                requireText(e.text2(), "create_bug_report.report");
                requireText(e.text3(), "create_bug_report.reason");
                requireText(e.text4(), "create_bug_report.effectKey");
            }
            case "cancel_work" -> {
                requireNull(e.int1(), "cancel_work.int1");
                requireText(e.text1(), "cancel_work.reason");
                requireText(e.text2(), "cancel_work.effectKey");
                requireNull(e.text3(), "cancel_work.text3");
                requireNull(e.text4(), "cancel_work.text4");
            }
            default -> throw new IllegalStateException(
                    "unknown persisted workflow command type " + e.type());
        }
    }

    private static void requireNull(Object value, String name) {
        if (value != null) {
            throw new IllegalStateException(
                    "persisted " + name + " must be null");
        }
    }

    private static void optionalText(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalStateException(
                    "persisted " + name + " cannot be blank when present");
        }
    }

    private static int requireInt(Integer value, String name) {
        if (value == null) {
            throw new IllegalStateException(
                    "persisted " + name + " cannot be null");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "persisted " + name + " cannot be blank");
        }
        return value;
    }
}
