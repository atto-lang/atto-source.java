package dev.oreslang.runtime;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit data-only actor wire encoding for workflow host-reference actors.
 *
 * <p>ActorRuntime intentionally rejects arbitrary host records. These helpers
 * encode workflow records into maps/lists/scalars that the runtime can deeply
 * copy/freeze without invoking arbitrary constructors on transport threads.</p>
 */
final class WorkflowActorWireV1 {

    private WorkflowActorWireV1() { }

    static Map<String, Object> event(DurableDevLoopV1.Event event) {
        return encoded(WorkflowSqlCodecV1.encodeEvent(event));
    }

    static DurableDevLoopV1.Event event(Object value) {
        return WorkflowSqlCodecV1.decodeEvent(decoded(value));
    }

    static Map<String, Object> command(
            DurableDevLoopV1.Command command) {
        return encoded(WorkflowSqlCodecV1.encodeCommand(command));
    }

    static DurableDevLoopV1.Command command(Object value) {
        return WorkflowSqlCodecV1.decodeCommand(decoded(value));
    }

    static Map<String, Object> claim(WorkflowJournalV1.Claim claim) {
        return map(
                "effectKey", claim.effectKey(),
                "command", command(claim.command()),
                "claimToken", claim.claimToken(),
                "attempt", claim.attempt(),
                "leaseUntilMillis", claim.leaseUntilMillis(),
                "workerId", claim.workerId());
    }

    static WorkflowJournalV1.Claim claim(Object value) {
        Map<String, Object> map = objectMap(value);
        return new WorkflowJournalV1.Claim(
                string(map, "effectKey"),
                command(map.get("command")),
                string(map, "claimToken"),
                integer(map, "attempt"),
                longValue(map, "leaseUntilMillis"),
                nullableString(map, "workerId"));
    }

    static Map<String, Object> journalEntry(
            DurableDevLoopV1.JournalEntry entry) {
        return map(
                "sequence", entry.sequence(),
                "eventId", entry.eventId(),
                "event", event(entry.event()),
                "disposition", entry.disposition().name());
    }

    static DurableDevLoopV1.JournalEntry journalEntry(Object value) {
        Map<String, Object> map = objectMap(value);
        return new DurableDevLoopV1.JournalEntry(
                longValue(map, "sequence"),
                string(map, "eventId"),
                event(map.get("event")),
                DurableDevLoopV1.Disposition.valueOf(
                        string(map, "disposition")));
    }

    static Map<String, Object> projection(
            WorkflowRunProjectionV1 value) {
        return map(
                "runId", value.runId(),
                "phase", value.phase().name(),
                "iteration", value.iteration(),
                "maxIterations", value.maxIterations(),
                "expectedCommitSha", value.expectedCommitSha(),
                "lastBugReportRef", value.lastBugReportRef(),
                "lastTransitionSequence",
                        value.lastTransitionSequence(),
                "pendingEffects", value.pendingEffects(),
                "claimedEffects", value.claimedEffects(),
                "ambiguousEffects", value.ambiguousEffects(),
                "completedEffects", value.completedEffects(),
                "abandonedEffects", value.abandonedEffects(),
                "pendingApproval", value.pendingApproval(),
                "terminal", value.terminal(),
                "requiresAttention", value.requiresAttention(),
                "attentionReason", value.attentionReason());
    }

    static WorkflowRunProjectionV1 projection(Object value) {
        Map<String, Object> map = objectMap(value);
        return new WorkflowRunProjectionV1(
                string(map, "runId"),
                DurableDevLoopV1.Phase.valueOf(
                        string(map, "phase")),
                integer(map, "iteration"),
                integer(map, "maxIterations"),
                nullableString(map, "expectedCommitSha"),
                nullableString(map, "lastBugReportRef"),
                longValue(map, "lastTransitionSequence"),
                integer(map, "pendingEffects"),
                integer(map, "claimedEffects"),
                integer(map, "ambiguousEffects"),
                integer(map, "completedEffects"),
                integer(map, "abandonedEffects"),
                bool(map, "pendingApproval"),
                bool(map, "terminal"),
                bool(map, "requiresAttention"),
                nullableString(map, "attentionReason"));
    }

    static Map<String, Object> headers(
            GitHubWebhookSecurityV1.Headers value) {
        return map(
                "signature256", value.signature256(),
                "deliveryId", value.deliveryId(),
                "eventName", value.eventName());
    }

    static GitHubWebhookSecurityV1.Headers headers(Object value) {
        Map<String, Object> map = objectMap(value);
        return new GitHubWebhookSecurityV1.Headers(
                string(map, "signature256"),
                string(map, "deliveryId"),
                string(map, "eventName"));
    }

    static Map<String, Object> expectation(
            GitHubCiCorrelationV1.Expectation value) {
        return map(
                "runId", value.runId(),
                "repositoryFullName", value.repositoryFullName(),
                "commitSha", value.commitSha(),
                "treeSha", value.treeSha(),
                "workflowName", value.workflowName());
    }

    static GitHubCiCorrelationV1.Expectation expectation(Object value) {
        Map<String, Object> map = objectMap(value);
        return new GitHubCiCorrelationV1.Expectation(
                string(map, "runId"),
                string(map, "repositoryFullName"),
                string(map, "commitSha"),
                nullableString(map, "treeSha"),
                nullableString(map, "workflowName"));
    }

    static Map<String, Object> treeProof(
            GitHubWorkflowRunPayloadV1.GitTreeProof value) {
        Objects.requireNonNull(value, "value");
        return map(
                "commitSha", value.commitSha(),
                "treeSha", value.treeSha(),
                "receiptRef", value.receiptRef());
    }

    static GitHubWorkflowRunPayloadV1.GitTreeProof treeProof(
            Object value) {
        Map<String, Object> map = objectMap(value);
        return new GitHubWorkflowRunPayloadV1.GitTreeProof(
                string(map, "commitSha"),
                string(map, "treeSha"),
                string(map, "receiptRef"));
    }

    static Map<String, Object> observed(
            GitHubCiCorrelationV1.Observed value) {
        return map(
                "repositoryFullName", value.repositoryFullName(),
                "commitSha", value.commitSha(),
                "treeSha", value.treeSha(),
                "workflowRunId", value.workflowRunId(),
                "workflowName", value.workflowName(),
                "conclusion", value.conclusion().name(),
                "reportRef", value.reportRef());
    }

    static GitHubCiCorrelationV1.Observed observed(Object value) {
        Map<String, Object> map = objectMap(value);
        return new GitHubCiCorrelationV1.Observed(
                string(map, "repositoryFullName"),
                string(map, "commitSha"),
                nullableString(map, "treeSha"),
                longValue(map, "workflowRunId"),
                string(map, "workflowName"),
                GitHubCiCorrelationV1.Conclusion.valueOf(
                        string(map, "conclusion")),
                string(map, "reportRef"));
    }

    static Map<String, Object> activityOutcome(
            WorkflowActivityProtocolV1.Outcome outcome) {
        if (outcome
                instanceof WorkflowActivityProtocolV1.Succeeded value) {
            return map(
                    "type", "succeeded",
                    "effectKey", value.effectKey(),
                    "claimToken", value.claimToken(),
                    "receiptRef", value.receiptRef(),
                    "event", event(value.resultingEvent()));
        }
        if (outcome
                instanceof WorkflowActivityProtocolV1.Completed value) {
            return map(
                    "type", "completed",
                    "effectKey", value.effectKey(),
                    "claimToken", value.claimToken(),
                    "receiptRef", value.receiptRef());
        }
        if (outcome
                instanceof WorkflowActivityProtocolV1.KnownUnexecuted value) {
            return map(
                    "type", "known_unexecuted",
                    "effectKey", value.effectKey(),
                    "claimToken", value.claimToken(),
                    "reasonRef", value.reasonRef());
        }
        if (outcome
                instanceof WorkflowActivityProtocolV1.Ambiguous value) {
            return map(
                    "type", "ambiguous",
                    "effectKey", value.effectKey(),
                    "claimToken", value.claimToken(),
                    "reconciliationRef", value.reconciliationRef());
        }
        throw new IllegalArgumentException(
                "unknown activity outcome "
                        + outcome.getClass().getName());
    }

    static WorkflowActivityProtocolV1.Outcome activityOutcome(
            Object value) {
        Map<String, Object> map = objectMap(value);
        return switch (string(map, "type")) {
            case "succeeded" ->
                    new WorkflowActivityProtocolV1.Succeeded(
                            string(map, "effectKey"),
                            string(map, "claimToken"),
                            string(map, "receiptRef"),
                            event(map.get("event")));
            case "completed" ->
                    new WorkflowActivityProtocolV1.Completed(
                            string(map, "effectKey"),
                            string(map, "claimToken"),
                            string(map, "receiptRef"));
            case "known_unexecuted" ->
                    new WorkflowActivityProtocolV1.KnownUnexecuted(
                            string(map, "effectKey"),
                            string(map, "claimToken"),
                            string(map, "reasonRef"));
            case "ambiguous" ->
                    new WorkflowActivityProtocolV1.Ambiguous(
                            string(map, "effectKey"),
                            string(map, "claimToken"),
                            string(map, "reconciliationRef"));
            default -> throw new IllegalStateException(
                    "unknown wire activity outcome type "
                            + string(map, "type"));
        };
    }

    static List<Object> capabilities(
            Set<WorkflowActivityProtocolV1.Capability> values) {
        return values.stream()
                .map(Enum::name)
                .sorted()
                .map(value -> (Object) value)
                .toList();
    }

    static Set<WorkflowActivityProtocolV1.Capability> capabilities(
            Object value) {
        List<?> list = list(value);
        java.util.LinkedHashSet<
                WorkflowActivityProtocolV1.Capability> result =
                new java.util.LinkedHashSet<>();
        for (Object item : list) {
            result.add(
                    WorkflowActivityProtocolV1.Capability.valueOf(
                            scalarString(item)));
        }
        return Set.copyOf(result);
    }

    static String bytes(byte[] value) {
        Objects.requireNonNull(value, "value");
        return Base64.getEncoder().encodeToString(value);
    }

    static byte[] bytes(Object value) {
        return Base64.getDecoder().decode(scalarString(value));
    }

    static Map<String, Object> map(Object... values) {
        if ((values.length & 1) != 0) {
            throw new IllegalArgumentException(
                    "wire map requires key/value pairs");
        }
        LinkedHashMap<String, Object> result =
                new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            String key = Objects.requireNonNull(
                    (String) values[i], "wire key");
            result.put(key, values[i + 1]);
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(
                    "wire value must be a map");
        }
        LinkedHashMap<String, Object> result =
                new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "wire map keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String string)
                || string.isBlank()) {
            throw new IllegalArgumentException(
                    "wire field " + key + " must be nonblank text");
        }
        return string;
    }

    static String nullableString(
            Map<String, Object> map,
            String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException(
                    "wire field " + key + " must be text or null");
        }
        return string;
    }

    static int integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    "wire field " + key + " must be numeric");
        }
        return Math.toIntExact(number.longValue());
    }

    static long longValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    "wire field " + key + " must be numeric");
        }
        return number.longValue();
    }

    static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException(
                    "wire field " + key + " must be boolean");
        }
        return bool;
    }

    static List<?> list(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "wire value must be a list");
        }
        return list;
    }

    private static Map<String, Object> encoded(
            WorkflowSqlCodecV1.Encoded value) {
        return map(
                "type", value.type(),
                "int1", value.int1(),
                "text1", value.text1(),
                "text2", value.text2(),
                "text3", value.text3(),
                "text4", value.text4());
    }

    private static WorkflowSqlCodecV1.Encoded decoded(Object value) {
        Map<String, Object> map = objectMap(value);
        Object rawInt = map.get("int1");
        final Integer int1;
        if (rawInt == null) {
            int1 = null;
        } else if (rawInt instanceof Number number) {
            int1 = Math.toIntExact(number.longValue());
        } else {
            throw new IllegalArgumentException(
                    "wire field int1 must be numeric or null");
        }
        return new WorkflowSqlCodecV1.Encoded(
                string(map, "type"),
                int1,
                nullableString(map, "text1"),
                nullableString(map, "text2"),
                nullableString(map, "text3"),
                nullableString(map, "text4"));
    }

    private static String scalarString(Object value) {
        if (!(value instanceof String string)
                || string.isBlank()) {
            throw new IllegalArgumentException(
                    "wire scalar must be nonblank text");
        }
        return string;
    }
}
