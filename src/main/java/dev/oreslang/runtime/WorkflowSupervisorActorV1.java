package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Long-running actor facade over the durable workflow supervisor ledger. */
public final class WorkflowSupervisorActorV1 {

    public sealed interface Message permits Configure, Admit, Reserve,
            SettleUsage, Cancel, QueryUsage, Shutdown { }

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

    public record Reserve(
            String effectKey,
            WorkflowActivityProtocolV1.Kind activity,
            long nowMillis,
            long estimatedModelTokens,
            long estimatedSpendMicros,
            int currentAmbiguousEffects,
            boolean protectedWrite) implements Message {
        public Reserve {
            effectKey = requireText(effectKey, "effectKey");
            activity = Objects.requireNonNull(activity, "activity");
        }
    }

    public record SettleUsage(
            String effectKey,
            long actualModelTokens,
            long actualSpendMicros) implements Message {
        public SettleUsage {
            effectKey = requireText(effectKey, "effectKey");
            if (actualModelTokens < 0 || actualSpendMicros < 0) {
                throw new IllegalArgumentException(
                        "actual usage cannot be negative");
            }
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

    private static final int PRIVATE_STATE_BYTES = 16_384;

    private record PrivateConfig(
            String databasePath,
            String runId,
            WorkflowSupervisorPolicyV1.Policy policy,
            long startedAtMillis,
            String workflowDatabasePath) { }

    private static final class Resources implements AutoCloseable {
        private final SqliteWorkflowSupervisorLedgerV1 ledger;
        private final SqliteWorkflowJournalV1 workflowStore;

        private Resources(
                SqliteWorkflowSupervisorLedgerV1 ledger,
                SqliteWorkflowJournalV1 workflowStore) {
            this.ledger = ledger;
            this.workflowStore = workflowStore;
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                ledger.close();
            } catch (RuntimeException e) {
                failure = e;
            }
            if (workflowStore != null) {
                try {
                    workflowStore.close();
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
        }
    }

    private static ActorRuntime.Behavior<Object> createBehavior(
            ActorRuntime.ActorContext<Object> context) {
        ActorRuntime.PrivateMemoryBlock state =
                context.privateMemory()
                        .orElseThrow(() -> new IllegalStateException(
                                "workflow supervisor actor requires private memory"))
                        .allocatePrivateBytes(PRIVATE_STATE_BYTES);
        return new Behavior(state);
    }

    private static final class Behavior
            implements ActorRuntime.Behavior<Object> {
        private final ActorRuntime.PrivateMemoryBlock stateBlock;

        private Behavior(ActorRuntime.PrivateMemoryBlock stateBlock) {
            this.stateBlock = Objects.requireNonNull(
                    stateBlock, "stateBlock");
        }

        @Override
        public void onMessage(
                Object wire,
                ActorRuntime.ActorContext<Object> context) {
            Message message = decodeMessage(wire);
            PrivateConfig config = readConfig();

            if (message instanceof Configure configure) {
                if (config != null) {
                    throw new IllegalStateException(
                            "workflow supervisor actor is already configured");
                }
                PrivateConfig next = new PrivateConfig(
                        configure.databasePath(),
                        configure.runId(),
                        configure.policy(),
                        configure.startedAtMillis(),
                        configure.workflowDatabasePath());
                try (Resources resources = openResources(next)) {
                    reconcileCancellation(resources);
                    writeConfig(next);
                    emit(
                            context,
                            new Ready(
                                    next.runId(),
                                    resources.ledger.usage()));
                }
                return;
            }

            requireConfigured(config);

            if (message instanceof Shutdown) {
                emit(context, new Stopping(config.runId()));
                stateBlock.close();
                context.runtime().endCurrentActor();
                return;
            }

            try (Resources resources = openResources(config)) {
                SqliteWorkflowSupervisorLedgerV1 ledger =
                        resources.ledger;
                SqliteWorkflowJournalV1 workflowStore =
                        resources.workflowStore;

                reconcileCancellation(resources);

                if (message instanceof Admit admit) {
                    WorkflowSupervisorPolicyV1.Admission terminal =
                            terminalAdmission(ledger, workflowStore);
                    if (terminal != null) {
                        emit(
                                context,
                                new AdmissionEvaluated(terminal));
                        return;
                    }

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

                if (message instanceof Reserve reserve) {
                    if (workflowStore != null) {
                        WorkflowJournalV1.EffectSnapshot effect =
                                workflowStore.effect(reserve.effectKey())
                                        .orElseThrow(() ->
                                                new IllegalArgumentException(
                                                        "unknown workflow effect "
                                                                + reserve.effectKey()));
                        WorkflowActivityProtocolV1.Kind expectedKind =
                                WorkflowActivityProtocolV1.kindFor(
                                        effect.command());
                        if (expectedKind != reserve.activity()) {
                            throw new IllegalArgumentException(
                                    "supervisor reservation activity "
                                            + reserve.activity()
                                            + " does not match effect command kind "
                                            + expectedKind);
                        }
                        if (effect.state()
                                != WorkflowJournalV1.EffectState.PENDING
                                && effect.state()
                                != WorkflowJournalV1.EffectState.CLAIMED) {
                            throw new IllegalStateException(
                                    "cannot reserve budget for non-runnable effect "
                                            + reserve.effectKey()
                                            + " in state "
                                            + effect.state());
                        }

                        WorkflowSupervisorPolicyV1.Admission terminal =
                                terminalAdmission(
                                        ledger, workflowStore);
                        if (terminal != null) {
                            emit(
                                    context,
                                    new AdmissionEvaluated(terminal));
                            return;
                        }
                    }

                    emit(
                            context,
                            new AdmissionEvaluated(
                                    ledger.reserve(
                                            reserve.effectKey(),
                                            reserve.activity(),
                                            reserve.nowMillis(),
                                            reserve.estimatedModelTokens(),
                                            reserve.estimatedSpendMicros(),
                                            workflowStore == null
                                                    ? reserve.currentAmbiguousEffects()
                                                    : workflowStore.snapshot()
                                                            .ambiguousEffects(),
                                            reserve.protectedWrite())));
                    return;
                }

                if (message instanceof SettleUsage settle) {
                    WorkflowSupervisorPolicyV1.Usage usage =
                            ledger.settle(
                                    settle.effectKey(),
                                    settle.actualModelTokens(),
                                    settle.actualSpendMicros());

                    if (usage.cancelled() && workflowStore != null) {
                        DurableDevLoopV1 machine =
                                workflowStore.replayDevLoop().machine();
                        if (!isTerminal(machine.snapshot().phase())) {
                            workflowStore.append(machine.accept(
                                    "supervisor-budget-cancel",
                                    new DurableDevLoopV1.Cancel(
                                            usage.cancellationReason())));
                        }
                    }

                    emit(context, new UsagePublished(usage));
                    return;
                }

                if (message instanceof Cancel cancel) {
                    WorkflowSupervisorPolicyV1.Usage usage;
                    if (workflowStore == null) {
                        usage = ledger.cancel(cancel.reason());
                    } else {
                        DurableDevLoopV1 machine =
                                workflowStore.replayDevLoop().machine();
                        DurableDevLoopV1.Snapshot workflow =
                                machine.snapshot();
                        if (!isTerminal(workflow.phase())) {
                            workflowStore.append(machine.accept(
                                    "supervisor-cancel",
                                    new DurableDevLoopV1.Cancel(
                                            cancel.reason())));
                            usage = ledger.cancel(cancel.reason());
                        } else if (workflow.phase()
                                == DurableDevLoopV1.Phase.CANCELLED) {
                            usage = ledger.usage().cancelled()
                                    ? ledger.usage()
                                    : ledger.cancel(
                                            workflow.cancelReason());
                        } else {
                            usage = ledger.usage();
                        }
                    }
                    emit(context, new UsagePublished(usage));
                    return;
                }

                if (message instanceof QueryUsage) {
                    emit(
                            context,
                            new UsagePublished(ledger.usage()));
                    return;
                }
            }

            throw new IllegalStateException(
                    "unsupported supervisor actor message "
                            + message.getClass().getName());
        }

        private Resources openResources(PrivateConfig config) {
            SqliteWorkflowSupervisorLedgerV1 ledger =
                    SqliteWorkflowSupervisorLedgerV1.open(
                            Path.of(config.databasePath()),
                            config.runId(),
                            config.policy(),
                            config.startedAtMillis());
            SqliteWorkflowJournalV1 workflowStore = null;
            boolean accepted = false;
            try {
                if (config.workflowDatabasePath() != null) {
                    workflowStore = SqliteWorkflowJournalV1.open(
                            Path.of(config.workflowDatabasePath()));
                    WorkflowJournalV1.StoreSnapshot snapshot =
                            workflowStore.snapshot();
                    if (snapshot.runId() != null
                            && !config.runId().equals(
                                    snapshot.runId())) {
                        throw new IllegalStateException(
                                "supervisor workflow database run id "
                                        + "does not match configuration");
                    }
                }
                accepted = true;
                return new Resources(ledger, workflowStore);
            } finally {
                if (!accepted) {
                    ledger.close();
                    if (workflowStore != null) {
                        workflowStore.close();
                    }
                }
            }
        }

        private static void reconcileCancellation(
                Resources resources) {
            if (resources.workflowStore == null) {
                return;
            }
            WorkflowJournalV1.StoreSnapshot snapshot =
                    resources.workflowStore.snapshot();
            if (snapshot.runId() == null) {
                return;
            }
            DurableDevLoopV1.Snapshot reducer =
                    resources.workflowStore.replayDevLoop()
                            .machine()
                            .snapshot();
            if (reducer.phase()
                    == DurableDevLoopV1.Phase.CANCELLED
                    && !resources.ledger.usage().cancelled()) {
                resources.ledger.cancel(reducer.cancelReason());
            }
        }

        private static WorkflowSupervisorPolicyV1.Admission
                terminalAdmission(
                        SqliteWorkflowSupervisorLedgerV1 ledger,
                        SqliteWorkflowJournalV1 workflowStore) {
            if (workflowStore == null
                    || workflowStore.snapshot().runId() == null) {
                return null;
            }
            DurableDevLoopV1.Snapshot workflow =
                    workflowStore.replayDevLoop()
                            .machine()
                            .snapshot();
            if (!isTerminal(workflow.phase())) {
                return null;
            }
            WorkflowSupervisorPolicyV1.Usage usage =
                    ledger.usage();
            String reason = switch (workflow.phase()) {
                case CANCELLED ->
                        usage.cancelled()
                                ? usage.cancellationReason()
                                : "workflow_cancelled";
                case FAILED -> "workflow_failed";
                case COMPLETED -> "workflow_completed";
                default -> throw new IllegalStateException(
                        "non-terminal workflow reached terminal guard");
            };
            return new WorkflowSupervisorPolicyV1.Admission(
                    WorkflowSupervisorPolicyV1.Decision.CANCEL,
                    usage,
                    reason);
        }

        private PrivateConfig readConfig() {
            byte[] payload =
                    WorkflowPrivateActorStateV1.read(stateBlock);
            if (payload.length == 0) {
                return null;
            }
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(payload))) {
                int version = input.readInt();
                if (version != 1) {
                    throw new IllegalStateException(
                            "unsupported supervisor private-state version "
                                    + version);
                }
                String databasePath = readString(input);
                String runId = readString(input);
                WorkflowSupervisorPolicyV1.Policy policy =
                        new WorkflowSupervisorPolicyV1.Policy(
                                input.readLong(),
                                input.readLong(),
                                input.readInt(),
                                input.readLong(),
                                input.readInt(),
                                input.readBoolean());
                long startedAtMillis = input.readLong();
                String workflowDatabasePath =
                        readNullableString(input);
                if (input.available() != 0) {
                    throw new IllegalStateException(
                            "trailing bytes in supervisor private state");
                }
                return new PrivateConfig(
                        databasePath,
                        runId,
                        policy,
                        startedAtMillis,
                        workflowDatabasePath);
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof IllegalStateException stateFailure) {
                    throw stateFailure;
                }
                throw new IllegalStateException(
                        "invalid supervisor private state",
                        failure);
            } finally {
                Arrays.fill(payload, (byte) 0);
            }
        }

        private void writeConfig(PrivateConfig config) {
            WorkflowPrivateActorStateV1.ScrubbableOutput bytes =
                    new WorkflowPrivateActorStateV1.ScrubbableOutput();
            try {
                try (DataOutputStream output =
                        new DataOutputStream(bytes)) {
                    output.writeInt(1);
                    writeString(output, config.databasePath());
                    writeString(output, config.runId());
                    WorkflowSupervisorPolicyV1.Policy policy =
                            config.policy();
                    output.writeLong(policy.maxWallClockMillis());
                    output.writeLong(policy.maxModelTokens());
                    output.writeInt(policy.maxCiRuns());
                    output.writeLong(policy.maxSpendMicros());
                    output.writeInt(policy.maxAmbiguousEffects());
                    output.writeBoolean(
                            policy.requireApprovalBeforeProtectedWrite());
                    output.writeLong(config.startedAtMillis());
                    writeNullableString(
                            output,
                            config.workflowDatabasePath());
                }
                byte[] encoded = bytes.snapshotAndWipe();
                try {
                    WorkflowPrivateActorStateV1.write(
                            stateBlock, encoded);
                } finally {
                    Arrays.fill(encoded, (byte) 0);
                }
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "failed to encode supervisor private state",
                        failure);
            } finally {
                bytes.wipe();
            }
        }

        private static void requireConfigured(
                PrivateConfig config) {
            if (config == null) {
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

    private static void writeString(
            DataOutputStream output,
            String value) throws IOException {
        byte[] bytes = WorkflowPrivateActorStateV1.encodeUtf8(
                requireText(value, "private-state string"));
        try {
            output.writeInt(bytes.length);
            output.write(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String readString(
            DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > PRIVATE_STATE_BYTES) {
            throw new IllegalStateException(
                    "invalid private-state string length");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalStateException(
                    "truncated private-state string");
        }
        try {
            return WorkflowPrivateActorStateV1.decodeUtf8(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void writeNullableString(
            DataOutputStream output,
            String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            writeString(output, value);
        }
    }

    private static String readNullableString(
            DataInputStream input) throws IOException {
        return input.readBoolean()
                ? readString(input)
                : null;
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
        if (message instanceof Reserve value) {
            return WorkflowActorWireV1.map(
                    "type", "reserve",
                    "effectKey", value.effectKey(),
                    "activity", value.activity().name(),
                    "nowMillis", value.nowMillis(),
                    "estimatedModelTokens",
                            value.estimatedModelTokens(),
                    "estimatedSpendMicros",
                            value.estimatedSpendMicros(),
                    "currentAmbiguousEffects",
                            value.currentAmbiguousEffects(),
                    "protectedWrite", value.protectedWrite());
        }
        if (message instanceof SettleUsage value) {
            return WorkflowActorWireV1.map(
                    "type", "settle_usage",
                    "effectKey", value.effectKey(),
                    "actualModelTokens", value.actualModelTokens(),
                    "actualSpendMicros", value.actualSpendMicros());
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
            case "reserve" -> new Reserve(
                    WorkflowActorWireV1.string(map, "effectKey"),
                    WorkflowActivityProtocolV1.Kind.valueOf(
                            WorkflowActorWireV1.string(
                                    map, "activity")),
                    WorkflowActorWireV1.longValue(
                            map, "nowMillis"),
                    WorkflowActorWireV1.longValue(
                            map, "estimatedModelTokens"),
                    WorkflowActorWireV1.longValue(
                            map, "estimatedSpendMicros"),
                    WorkflowActorWireV1.integer(
                            map, "currentAmbiguousEffects"),
                    WorkflowActorWireV1.bool(
                            map, "protectedWrite"));
            case "settle_usage" -> new SettleUsage(
                    WorkflowActorWireV1.string(
                            map, "effectKey"),
                    WorkflowActorWireV1.longValue(
                            map, "actualModelTokens"),
                    WorkflowActorWireV1.longValue(
                            map, "actualSpendMicros"));
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

    private static boolean isTerminal(
            DurableDevLoopV1.Phase phase) {
        return phase == DurableDevLoopV1.Phase.COMPLETED
                || phase == DurableDevLoopV1.Phase.FAILED
                || phase == DurableDevLoopV1.Phase.CANCELLED;
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
