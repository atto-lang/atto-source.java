package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Stable textual codec for durable dev-loop events and commands.
 *
 * <p>The format is versioned by the SQLite schema, type-tagged, delimiter-safe,
 * and intentionally independent from Java serialization.</p>
 */
final class WorkflowPersistenceCodecV1 {

    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER =
            Base64.getUrlDecoder();

    private WorkflowPersistenceCodecV1() { }

    record Encoded(String type, String payload) { }

    static Encoded encodeEvent(DurableDevLoopV1.Event event) {
        if (event instanceof DurableDevLoopV1.Start e) {
            return encoded("start", e.goal());
        }
        if (event instanceof DurableDevLoopV1.CodeChanged e) {
            return encoded("code_changed",
                    Integer.toString(e.iteration()), e.patchRef());
        }
        if (event instanceof DurableDevLoopV1.PushRecorded e) {
            return encoded("push_recorded",
                    Integer.toString(e.iteration()), e.commitSha());
        }
        if (event instanceof DurableDevLoopV1.CiCompleted e) {
            return encoded("ci_completed",
                    e.commitSha(), e.conclusion().name(), e.reportRef());
        }
        if (event instanceof DurableDevLoopV1.EvaluationPassed e) {
            return encoded("evaluation_passed", e.commitSha());
        }
        if (event instanceof DurableDevLoopV1.EvaluationFailed e) {
            return encoded("evaluation_failed",
                    e.commitSha(), e.reasonRef());
        }
        if (event instanceof DurableDevLoopV1.BugReportCreated e) {
            return encoded("bug_report_created",
                    Integer.toString(e.iteration()),
                    e.commitSha(),
                    e.bugReportRef());
        }
        if (event instanceof DurableDevLoopV1.ApprovalGranted e) {
            return encoded("approval_granted", e.commitSha());
        }
        if (event instanceof DurableDevLoopV1.Cancel e) {
            return encoded("cancel", e.reason());
        }
        throw new IllegalArgumentException(
                "unsupported workflow event " + event.getClass().getName());
    }

    static DurableDevLoopV1.Event decodeEvent(
            String type,
            String payload) {
        List<String> f = fields(payload);
        return switch (type) {
            case "start" -> new DurableDevLoopV1.Start(one(f, 0));
            case "code_changed" -> new DurableDevLoopV1.CodeChanged(
                    parseInt(one(f, 0), "iteration"), one(f, 1));
            case "push_recorded" -> new DurableDevLoopV1.PushRecorded(
                    parseInt(one(f, 0), "iteration"), one(f, 1));
            case "ci_completed" -> new DurableDevLoopV1.CiCompleted(
                    one(f, 0),
                    DurableDevLoopV1.CiConclusion.valueOf(one(f, 1)),
                    one(f, 2));
            case "evaluation_passed" ->
                    new DurableDevLoopV1.EvaluationPassed(one(f, 0));
            case "evaluation_failed" ->
                    new DurableDevLoopV1.EvaluationFailed(
                            one(f, 0), one(f, 1));
            case "bug_report_created" ->
                    new DurableDevLoopV1.BugReportCreated(
                            parseInt(one(f, 0), "iteration"),
                            one(f, 1),
                            one(f, 2));
            case "approval_granted" ->
                    new DurableDevLoopV1.ApprovalGranted(one(f, 0));
            case "cancel" -> new DurableDevLoopV1.Cancel(one(f, 0));
            default -> throw new IllegalStateException(
                    "unknown persisted workflow event type " + type);
        };
    }

    static Encoded encodeCommand(DurableDevLoopV1.Command command) {
        if (command instanceof DurableDevLoopV1.RequestCode c) {
            return encodedNullable("request_code",
                    Integer.toString(c.iteration()),
                    c.goal(),
                    c.feedbackRef(),
                    c.effectKey());
        }
        if (command instanceof DurableDevLoopV1.PushCode c) {
            return encoded("push_code",
                    Integer.toString(c.iteration()),
                    c.patchRef(),
                    c.effectKey());
        }
        if (command instanceof DurableDevLoopV1.AwaitCi c) {
            return encoded("await_ci",
                    Integer.toString(c.iteration()),
                    c.commitSha(),
                    c.effectKey());
        }
        if (command instanceof DurableDevLoopV1.Evaluate c) {
            return encoded("evaluate",
                    Integer.toString(c.iteration()),
                    c.commitSha(),
                    c.reportRef(),
                    c.effectKey());
        }
        if (command instanceof DurableDevLoopV1.CreateBugReport c) {
            return encoded("create_bug_report",
                    Integer.toString(c.iteration()),
                    c.commitSha(),
                    c.reportRef(),
                    c.reasonRef(),
                    c.effectKey());
        }
        if (command instanceof DurableDevLoopV1.RequestApproval c) {
            return encoded("request_approval",
                    Integer.toString(c.iteration()),
                    c.commitSha(),
                    c.effectKey());
        }
        if (command instanceof DurableDevLoopV1.Escalate c) {
            return encoded("escalate",
                    Integer.toString(c.iteration()),
                    c.reason(),
                    c.effectKey());
        }
        if (command instanceof DurableDevLoopV1.CancelWork c) {
            return encoded("cancel_work", c.reason(), c.effectKey());
        }
        throw new IllegalArgumentException(
                "unsupported workflow command "
                        + command.getClass().getName());
    }

    static DurableDevLoopV1.Command decodeCommand(
            String type,
            String payload) {
        List<String> f = nullableFields(payload);
        return switch (type) {
            case "request_code" -> new DurableDevLoopV1.RequestCode(
                    parseInt(one(f, 0), "iteration"),
                    one(f, 1),
                    f.get(2),
                    one(f, 3));
            case "push_code" -> new DurableDevLoopV1.PushCode(
                    parseInt(one(f, 0), "iteration"),
                    one(f, 1),
                    one(f, 2));
            case "await_ci" -> new DurableDevLoopV1.AwaitCi(
                    parseInt(one(f, 0), "iteration"),
                    one(f, 1),
                    one(f, 2));
            case "evaluate" -> new DurableDevLoopV1.Evaluate(
                    parseInt(one(f, 0), "iteration"),
                    one(f, 1),
                    one(f, 2),
                    one(f, 3));
            case "create_bug_report" ->
                    new DurableDevLoopV1.CreateBugReport(
                            parseInt(one(f, 0), "iteration"),
                            one(f, 1),
                            one(f, 2),
                            one(f, 3),
                            one(f, 4));
            case "request_approval" ->
                    new DurableDevLoopV1.RequestApproval(
                            parseInt(one(f, 0), "iteration"),
                            one(f, 1),
                            one(f, 2));
            case "escalate" -> new DurableDevLoopV1.Escalate(
                    parseInt(one(f, 0), "iteration"),
                    one(f, 1),
                    one(f, 2));
            case "cancel_work" -> new DurableDevLoopV1.CancelWork(
                    one(f, 0), one(f, 1));
            default -> throw new IllegalStateException(
                    "unknown persisted workflow command type " + type);
        };
    }

    private static Encoded encoded(String type, String... fields) {
        return new Encoded(type, join(false, fields));
    }

    private static Encoded encodedNullable(
            String type,
            String... fields) {
        return new Encoded(type, join(true, fields));
    }

    private static String join(
            boolean nullable,
            String... values) {
        List<String> out = new ArrayList<>(values.length);
        for (String value : values) {
            if (value == null) {
                if (!nullable) {
                    throw new IllegalArgumentException(
                            "non-null codec field was null");
                }
                out.add("~");
            } else {
                out.add(ENCODER.encodeToString(
                        value.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return String.join(".", out);
    }

    private static List<String> fields(String payload) {
        List<String> f = nullableFields(payload);
        if (f.stream().anyMatch(v -> v == null)) {
            throw new IllegalStateException(
                    "non-null persisted field decoded as null");
        }
        return f;
    }

    private static List<String> nullableFields(String payload) {
        String[] parts = payload.split("\\.", -1);
        List<String> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            if ("~".equals(part)) {
                out.add(null);
            } else {
                try {
                    out.add(new String(
                            DECODER.decode(part),
                            StandardCharsets.UTF_8));
                } catch (IllegalArgumentException failure) {
                    throw new IllegalStateException(
                            "invalid persisted workflow base64 field",
                            failure);
                }
            }
        }
        return List.copyOf(out);
    }

    private static String one(List<String> fields, int index) {
        if (index >= fields.size()) {
            throw new IllegalStateException(
                    "persisted workflow payload is missing field " + index);
        }
        String value = fields.get(index);
        if (value == null) {
            throw new IllegalStateException(
                    "persisted workflow payload field "
                            + index + " is unexpectedly null");
        }
        return value;
    }

    private static int parseInt(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalStateException(
                    "persisted " + label + " is not an integer",
                    failure);
        }
    }
}
