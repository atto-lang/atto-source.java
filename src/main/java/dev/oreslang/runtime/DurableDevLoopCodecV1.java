package dev.oreslang.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned JSON codec for durable workflow events and commands.
 *
 * <p>This codec intentionally stores a small, explicit wire contract rather
 * than Java serialization so persisted workflow state remains inspectable and
 * migration-friendly.</p>
 */
public final class DurableDevLoopCodecV1 {

    private static final JsonFactory JSON = JsonFactory.builder().build();
    private static final int VERSION = 1;

    private DurableDevLoopCodecV1() { }

    public static String encodeEvent(DurableDevLoopV1.Event event) {
        Objects.requireNonNull(event, "event");
        return write(objectForEvent(event));
    }

    public static DurableDevLoopV1.Event decodeEvent(String json) {
        Map<String, String> v = read(json);
        requireVersion(v);
        String type = require(v, "type");
        return switch (type) {
            case "Start" -> new DurableDevLoopV1.Start(require(v, "goal"));
            case "CodeChanged" -> new DurableDevLoopV1.CodeChanged(
                    integer(v, "iteration"), require(v, "patchRef"));
            case "PushRecorded" -> new DurableDevLoopV1.PushRecorded(
                    integer(v, "iteration"), require(v, "commitSha"));
            case "CiCompleted" -> new DurableDevLoopV1.CiCompleted(
                    require(v, "commitSha"),
                    DurableDevLoopV1.CiConclusion.valueOf(
                            require(v, "conclusion")),
                    require(v, "reportRef"));
            case "EvaluationPassed" ->
                    new DurableDevLoopV1.EvaluationPassed(
                            require(v, "commitSha"));
            case "EvaluationFailed" ->
                    new DurableDevLoopV1.EvaluationFailed(
                            require(v, "commitSha"),
                            require(v, "reasonRef"));
            case "BugReportCreated" ->
                    new DurableDevLoopV1.BugReportCreated(
                            integer(v, "iteration"),
                            require(v, "commitSha"),
                            require(v, "bugReportRef"));
            case "ApprovalGranted" ->
                    new DurableDevLoopV1.ApprovalGranted(
                            require(v, "commitSha"));
            case "Cancel" ->
                    new DurableDevLoopV1.Cancel(require(v, "reason"));
            default -> throw new IllegalArgumentException(
                    "unknown durable workflow event type " + type);
        };
    }

    public static String encodeCommand(DurableDevLoopV1.Command command) {
        Objects.requireNonNull(command, "command");
        return write(objectForCommand(command));
    }

    public static DurableDevLoopV1.Command decodeCommand(String json) {
        Map<String, String> v = read(json);
        requireVersion(v);
        String type = require(v, "type");
        String effectKey = require(v, "effectKey");
        return switch (type) {
            case "RequestCode" -> new DurableDevLoopV1.RequestCode(
                    integer(v, "iteration"),
                    require(v, "goal"),
                    nullable(v, "feedbackRef"),
                    effectKey);
            case "PushCode" -> new DurableDevLoopV1.PushCode(
                    integer(v, "iteration"),
                    require(v, "patchRef"),
                    effectKey);
            case "AwaitCi" -> new DurableDevLoopV1.AwaitCi(
                    integer(v, "iteration"),
                    require(v, "commitSha"),
                    effectKey);
            case "Evaluate" -> new DurableDevLoopV1.Evaluate(
                    integer(v, "iteration"),
                    require(v, "commitSha"),
                    require(v, "reportRef"),
                    effectKey);
            case "CreateBugReport" ->
                    new DurableDevLoopV1.CreateBugReport(
                            integer(v, "iteration"),
                            require(v, "commitSha"),
                            require(v, "reportRef"),
                            require(v, "reasonRef"),
                            effectKey);
            case "RequestApproval" ->
                    new DurableDevLoopV1.RequestApproval(
                            integer(v, "iteration"),
                            require(v, "commitSha"),
                            effectKey);
            case "Escalate" -> new DurableDevLoopV1.Escalate(
                    integer(v, "iteration"),
                    require(v, "reason"),
                    effectKey);
            case "CancelWork" -> new DurableDevLoopV1.CancelWork(
                    require(v, "reason"),
                    effectKey);
            default -> throw new IllegalArgumentException(
                    "unknown durable workflow command type " + type);
        };
    }

    private static Map<String, String> objectForEvent(
            DurableDevLoopV1.Event event) {
        Map<String, String> v = base(event.getClass().getSimpleName());
        if (event instanceof DurableDevLoopV1.Start e) {
            v.put("goal", e.goal());
        } else if (event instanceof DurableDevLoopV1.CodeChanged e) {
            v.put("iteration", Integer.toString(e.iteration()));
            v.put("patchRef", e.patchRef());
        } else if (event instanceof DurableDevLoopV1.PushRecorded e) {
            v.put("iteration", Integer.toString(e.iteration()));
            v.put("commitSha", e.commitSha());
        } else if (event instanceof DurableDevLoopV1.CiCompleted e) {
            v.put("commitSha", e.commitSha());
            v.put("conclusion", e.conclusion().name());
            v.put("reportRef", e.reportRef());
        } else if (event instanceof DurableDevLoopV1.EvaluationPassed e) {
            v.put("commitSha", e.commitSha());
        } else if (event instanceof DurableDevLoopV1.EvaluationFailed e) {
            v.put("commitSha", e.commitSha());
            v.put("reasonRef", e.reasonRef());
        } else if (event instanceof DurableDevLoopV1.BugReportCreated e) {
            v.put("iteration", Integer.toString(e.iteration()));
            v.put("commitSha", e.commitSha());
            v.put("bugReportRef", e.bugReportRef());
        } else if (event instanceof DurableDevLoopV1.ApprovalGranted e) {
            v.put("commitSha", e.commitSha());
        } else if (event instanceof DurableDevLoopV1.Cancel e) {
            v.put("reason", e.reason());
        } else {
            throw new IllegalArgumentException(
                    "unsupported durable workflow event " + event);
        }
        return v;
    }

    private static Map<String, String> objectForCommand(
            DurableDevLoopV1.Command command) {
        Map<String, String> v = base(command.getClass().getSimpleName());
        v.put("effectKey", command.effectKey());
        if (command instanceof DurableDevLoopV1.RequestCode c) {
            v.put("iteration", Integer.toString(c.iteration()));
            v.put("goal", c.goal());
            v.put("feedbackRef", c.feedbackRef());
        } else if (command instanceof DurableDevLoopV1.PushCode c) {
            v.put("iteration", Integer.toString(c.iteration()));
            v.put("patchRef", c.patchRef());
        } else if (command instanceof DurableDevLoopV1.AwaitCi c) {
            v.put("iteration", Integer.toString(c.iteration()));
            v.put("commitSha", c.commitSha());
        } else if (command instanceof DurableDevLoopV1.Evaluate c) {
            v.put("iteration", Integer.toString(c.iteration()));
            v.put("commitSha", c.commitSha());
            v.put("reportRef", c.reportRef());
        } else if (command instanceof DurableDevLoopV1.CreateBugReport c) {
            v.put("iteration", Integer.toString(c.iteration()));
            v.put("commitSha", c.commitSha());
            v.put("reportRef", c.reportRef());
            v.put("reasonRef", c.reasonRef());
        } else if (command instanceof DurableDevLoopV1.RequestApproval c) {
            v.put("iteration", Integer.toString(c.iteration()));
            v.put("commitSha", c.commitSha());
        } else if (command instanceof DurableDevLoopV1.Escalate c) {
            v.put("iteration", Integer.toString(c.iteration()));
            v.put("reason", c.reason());
        } else if (command instanceof DurableDevLoopV1.CancelWork c) {
            v.put("reason", c.reason());
        } else {
            throw new IllegalArgumentException(
                    "unsupported durable workflow command " + command);
        }
        return v;
    }

    private static Map<String, String> base(String type) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("version", Integer.toString(VERSION));
        v.put("type", type);
        return v;
    }

    private static String write(Map<String, String> values) {
        try {
            StringWriter out = new StringWriter();
            try (JsonGenerator g = JSON.createGenerator(out)) {
                g.writeStartObject();
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (entry.getValue() == null) {
                        g.writeNullField(entry.getKey());
                    } else {
                        g.writeStringField(entry.getKey(), entry.getValue());
                    }
                }
                g.writeEndObject();
            }
            return out.toString();
        } catch (IOException e) {
            throw new IllegalStateException("cannot encode workflow JSON", e);
        }
    }

    private static Map<String, String> read(String json) {
        Objects.requireNonNull(json, "json");
        Map<String, String> out = new LinkedHashMap<>();
        try (JsonParser p = JSON.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException(
                        "workflow JSON must be an object");
            }
            while (p.nextToken() != JsonToken.END_OBJECT) {
                if (p.currentToken() != JsonToken.FIELD_NAME) {
                    throw new IllegalArgumentException(
                            "invalid workflow JSON field");
                }
                String key = p.currentName();
                JsonToken token = p.nextToken();
                if (token == JsonToken.VALUE_NULL) {
                    out.put(key, null);
                } else if (token == JsonToken.VALUE_STRING) {
                    out.put(key, p.getText());
                } else {
                    throw new IllegalArgumentException(
                            "workflow JSON values must be strings or null");
                }
            }
            if (p.nextToken() != null) {
                throw new IllegalArgumentException(
                        "trailing workflow JSON content");
            }
            return out;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "cannot decode workflow JSON", e);
        }
    }

    private static void requireVersion(Map<String, String> v) {
        int version = integer(v, "version");
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "unsupported workflow codec version " + version);
        }
    }

    private static int integer(Map<String, String> v, String key) {
        try {
            return Integer.parseInt(require(v, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid integer field " + key, e);
        }
    }

    private static String require(Map<String, String> v, String key) {
        String value = v.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "missing workflow field " + key);
        }
        return value;
    }

    private static String nullable(Map<String, String> v, String key) {
        if (!v.containsKey(key)) {
            throw new IllegalArgumentException(
                    "missing workflow field " + key);
        }
        return v.get(key);
    }
}
