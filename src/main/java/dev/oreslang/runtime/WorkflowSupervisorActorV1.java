package dev.oreslang.runtime;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Long-running actor facade over the durable workflow supervisor ledger. */
public final class WorkflowSupervisorActorV1 {

    public sealed interface Message permits Configure, Admit,
            Cancel, QueryUsage, Shutdown { }

    public record Configure(
            String databasePath,
            String runId,
            WorkflowSupervisorPolicyV1.Policy policy,
            long startedAtMillis,
            String workflowDatabasePath) implements Message {
        public Configure {
            databasePath = requireText(databasePath, "databasePath");
            runId = requireText(runId, "runId");
            policy = Objects.requireNonNull(policy, "policy");
            if (startedAtMillis < 0) {
                throw new IllegalArgumentException(
                        "startedAtMillis cannot be negative");
            }
            if (workflowDatabasePath != null) {
                workflowDatabasePath = requireText(
                        workflowDatabasePath,
                        "workflowDatabasePath");
            }
        }

        public Configure(
                String databasePath,
                String runId,
                WorkflowSupervisorPolicyV1.Policy policy,
                long startedAtMillis) {
            this(
                    databasePath,
                    runId,
                    policy,
                    startedAtMillis,
                    null);
        }
    }

    public record Admit(
            WorkflowActivityProtocolV1.Kind activity,
            long nowMillis,
            long additionalModelTokens,
            long additionalSpendMicros,
            int currentAmbiguousEffects,
            boolean protectedWrite) implements Message {
        public Admit {
            activity = Objects.requireNonNull(activity, "activity");
        }
    }

    public record Cancel(String reason) implements Message {
        public Cancel {
            reason = requireText(reason, "reason");
        }
    }

    public record QueryUsage() implements Message { }

    public record Shutdown() implements Message { }

    public sealed interface Output permits Ready, AdmissionEvaluated,
            UsagePublished, Stopping { }

    public record Ready(
            String runId,
            WorkflowSupervisorPolicyV1.Usage usage) implements Output {
        public Ready {
            runId = requireText(runId, "runId");
            usage = Objects.requireNonNull(usage, "usage");
        }
    }

    public record AdmissionEvaluated(
            WorkflowSupervisorPolicyV1.Admission admission)
            implements Output {
        public AdmissionEvaluated {
            admission = Objects.requireNonNull(
                    admission, "admission");
        }
    }

    public record UsagePublished(
            WorkflowSupervisorPolicyV1.Usage usage) implements Output {
        public UsagePublished {
            usage = Objects.requireNonNull(usage, "usage");
        }
    }

    public record Stopping(String runId) implements Output {
        public Stopping {
            runId = requireText(runId, "runId");
        }
    }

    public static final class Ref {
        private final ActorRuntime.ActorRef<Object> raw;

        private Ref(ActorRuntime.ActorRef<Object> raw) {
            this.raw = Objects.requireNonNull(raw, "raw");
        }

        public ActorRuntime.ActorRef<Object> raw() {
            return raw;
        }

        public ActorRuntime.ActorOutputStream<Object> outputs() {
            return raw.outputs();
        }

        public void send(Message message) {
            raw.send(encodeMessage(
                    Objects.requireNonNull(message, "message")));
        }
    }

    private WorkflowSupervisorActorV1() { }

    public static Ref spawn(ActorRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return new Ref(runtime.spawnPrivate(
                WorkflowSupervisorActorV1::createBehavior));
    }

    public static Output decodeOutput(Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return switch (WorkflowActorWireV1.string(map, "type")) {
            case "ready" -> new Ready(
                    WorkflowActorWireV1.string(map, "runId"),
                    decodeUsage(map.get("usage")));
            case "admission" -> new AdmissionEvaluated(
                    decodeAdmission(map.get("admission")));
            case "usage" -> new UsagePublished(
                    decodeUsage(map.get("usage")));
            case "stopping" -> new Stopping(
                    WorkflowActorWireV1.string(map, "runId"));
            default -> throw new IllegalArgumentException(
                    "unknown supervisor actor output type "
                            + WorkflowActorWireV1.string(map, "type"));
        };
    }

    private static ActorRuntime.Behavior<Object> createBehavior(
            ActorRuntime.ActorContext<Object> ignored) {
        return new Behavior();
    }

    private static final class Behavior
            implements ActorRuntime.Behavior<Object> {
        private SqliteWorkflowSupervisorLedgerV1 ledger;
        private SqliteWorkflowJournalV1 workflowStore;
        private String runId;

        @Override
        public void onMessage(
                Object wire,
                ActorRuntime.ActorContext<Object> context) {
            Message message = decodeMessage(wire);

            if (message instanceof Configure configure) {
                if (ledger != null) {
                    throw new IllegalStateException(
                            "workflow supervisor actor is already configured");
                }
                SqliteWorkflowSupervisorLedgerV1 opened =
                        SqliteWorkflowSupervisorLedgerV1.open(
                                Path.of(configure.databasePath()),
                                configure.runId(),
                                configure.policy(),
                                configure.startedAtMillis());
                SqliteWorkflowJournalV1 workflowOpened = null;
                boolean accepted = false;
                try {
                    if (configure.workflowDatabasePath() != null) {
                        workflowOpened = SqliteWorkflowJournalV1.open(
                                Path.of(configure.workflowDatabasePath()));
                        WorkflowJournalV1.StoreSnapshot snapshot =
                                workflowOpened.snapshot();
                        if (snapshot.runId() != null
                                && !configure.runId().equals(
                                        snapshot.runId())) {
                            throw new IllegalStateException(
                                    "supervisor workflow database run id "
                                            + "does not match configuration");
                        }
                    }
                    this.ledger = opened;
                    this.workflowStore = workflowOpened;
                    this.runId = configure.runId();
                    accepted = true;
                    emit(
                            context,
                            new Ready(runId, ledger.usage()));
                } finally {
                    if (!accepted) {
                        opened.close();
                        if (workflowOpened != null) {
                            workflowOpened.close();
                        }
                    }
                }
                return;
            }

            requireConfigured();

            if (message instanceof Admit admit) {
                emit(
                        context,
                        new AdmissionEvaluated(
                                ledger.admit(
                                        admit.activity(),
                                        admit.nowMillis(),
                                        admit.additionalModelTokens(),
                                        admit.additionalSpendMicros(),
                                        workflowStore == null
                                                ? admit.currentAmbiguousEffects()
                                                : workflowStore.snapshot()
                                                        .ambiguousEffects(),
                                        admit.protectedWrite())));
                return;
            }

            if (message instanceof Cancel cancel) {
                emit(
                        context,
                        new UsagePublished(
                                ledger.cancel(cancel.reason())));
                return;
            }

            if (message instanceof QueryUsage) {
                emit(
                        context,
                        new UsagePublished(ledger.usage()));
                return;
            }

            if (message instanceof Shutdown) {
                emit(context, new Stopping(runId));
                SqliteWorkflowSupervisorLedgerV1 closing = ledger;
                SqliteWorkflowJournalV1 closingWorkflow =
                        workflowStore;
                ledger = null;
                workflowStore = null;
                context.runtime().endCurrentActorWithCleanup(() -> {
                    RuntimeException failure = null;
                    try {
                        closing.close();
                    } catch (RuntimeException e) {
                        failure = e;
                    }
                    if (closingWorkflow != null) {
                        try {
                            closingWorkflow.close();
                        } catch (RuntimeException e) {
                            if (failure == null) {
                                failure = e;
                            } else {
                                failure.addSuppressed(e);
                            }
                        }
                    }
                    if (failure != null) {
                        throw failure;
                    }
                });
                return;
            }

            throw new IllegalStateException(
                    "unsupported supervisor actor message "
                            + message.getClass().getName());
        }

        private void requireConfigured() {
            if (ledger == null) {
                throw new IllegalStateException(
                        "Configure must be the first supervisor message");
            }
        }

        private static void emit(
                ActorRuntime.ActorContext<Object> context,
                Output output) {
            context.runtime().emitCurrentActorOutput(
                    encodeOutput(output));
        }
    }

    private static Map<String, Object> encodeMessage(Message message) {
        if (message instanceof Configure value) {
            return WorkflowActorWireV1.map(
                    "type", "configure",
                    "databasePath", value.databasePath(),
                    "runId", value.runId(),
                    "policy", encodePolicy(value.policy()),
                    "startedAtMillis", value.startedAtMillis(),
                    "workflowDatabasePath",
                            value.workflowDatabasePath());
        }
        if (message instanceof Admit value) {
            return WorkflowActorWireV1.map(
                    "type", "admit",
                    "activity", value.activity().name(),
                    "nowMillis", value.nowMillis(),
                    "additionalModelTokens",
                            value.additionalModelTokens(),
                    "additionalSpendMicros",
                            value.additionalSpendMicros(),
                    "currentAmbiguousEffects",
                            value.currentAmbiguousEffects(),
                    "protectedWrite", value.protectedWrite());
        }
        if (message instanceof Cancel value) {
            return WorkflowActorWireV1.map(
                    "type", "cancel",
                    "reason", value.reason());
        }
        if (message instanceof QueryUsage) {
            return WorkflowActorWireV1.map("type", "query");
        }
        if (message instanceof Shutdown) {
            return WorkflowActorWireV1.map("type", "shutdown");
        }
        throw new IllegalArgumentException(
                "unsupported supervisor message "
                        + message.getClass().getName());
    }

    private static Message decodeMessage(Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return switch (WorkflowActorWireV1.string(map, "type")) {
            case "configure" -> new Configure(
                    WorkflowActorWireV1.string(map, "databasePath"),
                    WorkflowActorWireV1.string(map, "runId"),
                    decodePolicy(map.get("policy")),
                    WorkflowActorWireV1.longValue(
                            map, "startedAtMillis"),
                    WorkflowActorWireV1.nullableString(
                            map, "workflowDatabasePath"));
            case "admit" -> new Admit(
                    WorkflowActivityProtocolV1.Kind.valueOf(
                            WorkflowActorWireV1.string(
                                    map, "activity")),
                    WorkflowActorWireV1.longValue(map, "nowMillis"),
                    WorkflowActorWireV1.longValue(
                            map, "additionalModelTokens"),
                    WorkflowActorWireV1.longValue(
                            map, "additionalSpendMicros"),
                    WorkflowActorWireV1.integer(
                            map, "currentAmbiguousEffects"),
                    WorkflowActorWireV1.bool(
                            map, "protectedWrite"));
            case "cancel" -> new Cancel(
                    WorkflowActorWireV1.string(map, "reason"));
            case "query" -> new QueryUsage();
            case "shutdown" -> new Shutdown();
            default -> throw new IllegalArgumentException(
                    "unknown supervisor message type "
                            + WorkflowActorWireV1.string(map, "type"));
        };
    }

    private static Map<String, Object> encodeOutput(Output output) {
        if (output instanceof Ready value) {
            return WorkflowActorWireV1.map(
                    "type", "ready",
                    "runId", value.runId(),
                    "usage", encodeUsage(value.usage()));
        }
        if (output instanceof AdmissionEvaluated value) {
            return WorkflowActorWireV1.map(
                    "type", "admission",
                    "admission", encodeAdmission(value.admission()));
        }
        if (output instanceof UsagePublished value) {
            return WorkflowActorWireV1.map(
                    "type", "usage",
                    "usage", encodeUsage(value.usage()));
        }
        if (output instanceof Stopping value) {
            return WorkflowActorWireV1.map(
                    "type", "stopping",
                    "runId", value.runId());
        }
        throw new IllegalArgumentException(
                "unsupported supervisor output "
                        + output.getClass().getName());
    }

    private static Map<String, Object> encodePolicy(
            WorkflowSupervisorPolicyV1.Policy value) {
        return WorkflowActorWireV1.map(
                "maxWallClockMillis", value.maxWallClockMillis(),
                "maxModelTokens", value.maxModelTokens(),
                "maxCiRuns", value.maxCiRuns(),
                "maxSpendMicros", value.maxSpendMicros(),
                "maxAmbiguousEffects", value.maxAmbiguousEffects(),
                "requireApprovalBeforeProtectedWrite",
                        value.requireApprovalBeforeProtectedWrite());
    }

    private static WorkflowSupervisorPolicyV1.Policy decodePolicy(
            Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return new WorkflowSupervisorPolicyV1.Policy(
                WorkflowActorWireV1.longValue(
                        map, "maxWallClockMillis"),
                WorkflowActorWireV1.longValue(
                        map, "maxModelTokens"),
                WorkflowActorWireV1.integer(map, "maxCiRuns"),
                WorkflowActorWireV1.longValue(
                        map, "maxSpendMicros"),
                WorkflowActorWireV1.integer(
                        map, "maxAmbiguousEffects"),
                WorkflowActorWireV1.bool(
                        map,
                        "requireApprovalBeforeProtectedWrite"));
    }

    private static Map<String, Object> encodeUsage(
            WorkflowSupervisorPolicyV1.Usage value) {
        return WorkflowActorWireV1.map(
                "startedAtMillis", value.startedAtMillis(),
                "modelTokens", value.modelTokens(),
                "ciRuns", value.ciRuns(),
                "spendMicros", value.spendMicros(),
                "ambiguousEffects", value.ambiguousEffects(),
                "cancelled", value.cancelled(),
                "cancellationReason", value.cancellationReason());
    }

    private static WorkflowSupervisorPolicyV1.Usage decodeUsage(
            Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return new WorkflowSupervisorPolicyV1.Usage(
                WorkflowActorWireV1.longValue(
                        map, "startedAtMillis"),
                WorkflowActorWireV1.longValue(map, "modelTokens"),
                WorkflowActorWireV1.integer(map, "ciRuns"),
                WorkflowActorWireV1.longValue(map, "spendMicros"),
                WorkflowActorWireV1.integer(
                        map, "ambiguousEffects"),
                WorkflowActorWireV1.bool(map, "cancelled"),
                WorkflowActorWireV1.nullableString(
                        map, "cancellationReason"));
    }

    private static Map<String, Object> encodeAdmission(
            WorkflowSupervisorPolicyV1.Admission value) {
        return WorkflowActorWireV1.map(
                "decision", value.decision().name(),
                "usage", encodeUsage(value.usage()),
                "reason", value.reason());
    }

    private static WorkflowSupervisorPolicyV1.Admission decodeAdmission(
            Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return new WorkflowSupervisorPolicyV1.Admission(
                WorkflowSupervisorPolicyV1.Decision.valueOf(
                        WorkflowActorWireV1.string(
                                map, "decision")),
                decodeUsage(map.get("usage")),
                WorkflowActorWireV1.nullableString(
                        map, "reason"));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must not be blank");
        }
        return normalized;
    }
}
