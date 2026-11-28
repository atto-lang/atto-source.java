package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Non-blocking actor boundary for one class of external workflow activity.
 *
 * <p>Only data-only maps/lists/scalars cross ActorRuntime. Typed Java records
 * remain host-side facades and are encoded/decoded explicitly.</p>
 */
public final class WorkflowActivityActorV1 {

    public sealed interface Message permits Configure, Dispatch,
            ReportOutcome, CancelAttempt, Shutdown { }

    public record Configure(
            String workerId,
            WorkflowActivityProtocolV1.Kind kind,
            Set<WorkflowActivityProtocolV1.Capability> capabilities)
            implements Message {
        public Configure {
            workerId = requireText(workerId, "workerId");
            kind = Objects.requireNonNull(kind, "kind");
            capabilities = Set.copyOf(
                    Objects.requireNonNull(
                            capabilities, "capabilities"));
        }
    }

    public record Dispatch(
            String runId,
            WorkflowJournalV1.Claim claim) implements Message {
        public Dispatch {
            runId = requireText(runId, "runId");
            claim = Objects.requireNonNull(claim, "claim");
        }
    }

    public record ReportOutcome(
            WorkflowActivityProtocolV1.Outcome outcome)
            implements Message {
        public ReportOutcome {
            outcome = Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record CancelAttempt(
            String effectKey,
            String claimToken,
            String reason) implements Message {
        public CancelAttempt {
            effectKey = requireText(effectKey, "effectKey");
            claimToken = requireText(claimToken, "claimToken");
            reason = requireText(reason, "reason");
        }
    }

    public record Shutdown() implements Message { }

    public sealed interface Output permits Ready, Authorized,
            OutcomeAccepted, AttemptCancelled, Stopping { }

    public record Ready(
            String workerId,
            WorkflowActivityProtocolV1.Kind kind) implements Output {
        public Ready {
            workerId = requireText(workerId, "workerId");
            kind = Objects.requireNonNull(kind, "kind");
        }
    }

    public record Authorized(
            WorkflowActivityProtocolV1.Request request)
            implements Output {
        public Authorized {
            request = Objects.requireNonNull(request, "request");
        }
    }

    public record OutcomeAccepted(
            WorkflowActivityProtocolV1.Outcome outcome)
            implements Output {
        public OutcomeAccepted {
            outcome = Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record AttemptCancelled(
            String effectKey,
            String claimToken,
            String reason) implements Output {
        public AttemptCancelled {
            effectKey = requireText(effectKey, "effectKey");
            claimToken = requireText(claimToken, "claimToken");
            reason = requireText(reason, "reason");
        }
    }

    public record Stopping(String workerId) implements Output {
        public Stopping {
            workerId = requireText(workerId, "workerId");
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

        public void send(Message message) {
            raw.send(encodeMessage(
                    Objects.requireNonNull(message, "message")));
        }

        public ActorRuntime.ActorOutputStream<Object> outputs() {
            return raw.outputs();
        }
    }

    private WorkflowActivityActorV1() { }

    public static Ref spawn(ActorRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return new Ref(runtime.spawnPrivate(
                WorkflowActivityActorV1::createBehavior));
    }

    public static Output decodeOutput(Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return switch (WorkflowActorWireV1.string(map, "type")) {
            case "ready" -> new Ready(
                    WorkflowActorWireV1.string(map, "workerId"),
                    WorkflowActivityProtocolV1.Kind.valueOf(
                            WorkflowActorWireV1.string(map, "kind")));
            case "authorized" -> new Authorized(
                    decodeRequest(map.get("request")));
            case "outcome_accepted" -> new OutcomeAccepted(
                    WorkflowActorWireV1.activityOutcome(
                            map.get("outcome")));
            case "attempt_cancelled" -> new AttemptCancelled(
                    WorkflowActorWireV1.string(map, "effectKey"),
                    WorkflowActorWireV1.string(map, "claimToken"),
                    WorkflowActorWireV1.string(map, "reason"));
            case "stopping" -> new Stopping(
                    WorkflowActorWireV1.string(map, "workerId"));
            default -> throw new IllegalArgumentException(
                    "unknown activity actor output type "
                            + WorkflowActorWireV1.string(map, "type"));
        };
    }

    private static final int PRIVATE_STATE_BYTES = 1_048_576;

    private record PrivateState(
            String workerId,
            WorkflowActivityProtocolV1.Kind kind,
            Set<WorkflowActivityProtocolV1.Capability> capabilities,
            LinkedHashMap<String, WorkflowJournalV1.Claim> inFlight) {

        private static PrivateState empty() {
            return new PrivateState(
                    null, null, Set.of(), new LinkedHashMap<>());
        }

        private boolean configured() {
            return workerId != null;
        }
    }

    private static ActorRuntime.Behavior<Object> createBehavior(
            ActorRuntime.ActorContext<Object> context) {
        ActorRuntime.PrivateMemoryBlock state =
                context.privateMemory()
                        .orElseThrow(() -> new IllegalStateException(
                                "workflow activity actor requires private memory"))
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
            PrivateState state = readState();

            if (message instanceof Configure configure) {
                if (state.configured()) {
                    throw new IllegalStateException(
                            "workflow activity actor is already configured");
                }
                PrivateState configured = new PrivateState(
                        configure.workerId(),
                        configure.kind(),
                        configure.capabilities(),
                        new LinkedHashMap<>());
                writeState(configured);
                emit(
                        context,
                        new Ready(
                                configured.workerId(),
                                configured.kind()));
                return;
            }

            requireConfigured(state);

            if (message instanceof Dispatch dispatch) {
                WorkflowJournalV1.Claim claim = dispatch.claim();
                if (claim.workerId() != null
                        && !state.workerId().equals(claim.workerId())) {
                    throw new SecurityException(
                            "activity worker "
                                    + state.workerId()
                                    + " cannot execute claim leased to "
                                    + claim.workerId());
                }
                WorkflowJournalV1.Claim existing =
                        state.inFlight().get(claim.effectKey());
                if (existing != null) {
                    if (existing.claimToken().equals(
                            claim.claimToken())) {
                        throw new IllegalStateException(
                                "activity attempt is already in flight: "
                                        + claim.effectKey());
                    }
                    throw new IllegalStateException(
                            "new activity attempt cannot overlap unresolved "
                                    + "claim for " + claim.effectKey());
                }

                var request = new WorkflowActivityProtocolV1.Request(
                        dispatch.runId(),
                        claim,
                        state.kind(),
                        state.capabilities());
                LinkedHashMap<String, WorkflowJournalV1.Claim> next =
                        new LinkedHashMap<>(state.inFlight());
                next.put(claim.effectKey(), claim);
                writeState(new PrivateState(
                        state.workerId(),
                        state.kind(),
                        state.capabilities(),
                        next));
                emit(context, new Authorized(request));
                return;
            }

            if (message instanceof ReportOutcome report) {
                WorkflowActivityProtocolV1.Outcome outcome =
                        report.outcome();
                String effectKey = effectKey(outcome);
                WorkflowJournalV1.Claim claim =
                        state.inFlight().get(effectKey);
                if (claim == null) {
                    throw new IllegalStateException(
                            "activity outcome has no in-flight claim: "
                                    + effectKey);
                }

                WorkflowActivityProtocolV1.validateOutcome(
                        claim, outcome);
                LinkedHashMap<String, WorkflowJournalV1.Claim> next =
                        new LinkedHashMap<>(state.inFlight());
                next.remove(effectKey);
                writeState(new PrivateState(
                        state.workerId(),
                        state.kind(),
                        state.capabilities(),
                        next));
                emit(context, new OutcomeAccepted(outcome));
                return;
            }

            if (message instanceof CancelAttempt cancel) {
                WorkflowJournalV1.Claim claim =
                        state.inFlight().get(cancel.effectKey());
                if (claim == null) {
                    throw new IllegalStateException(
                            "activity cancellation has no in-flight claim: "
                                    + cancel.effectKey());
                }
                if (!claim.claimToken().equals(
                        cancel.claimToken())) {
                    throw new IllegalStateException(
                            "activity cancellation claim token is stale");
                }
                LinkedHashMap<String, WorkflowJournalV1.Claim> next =
                        new LinkedHashMap<>(state.inFlight());
                next.remove(cancel.effectKey());
                writeState(new PrivateState(
                        state.workerId(),
                        state.kind(),
                        state.capabilities(),
                        next));
                emit(
                        context,
                        new AttemptCancelled(
                                cancel.effectKey(),
                                cancel.claimToken(),
                                cancel.reason()));
                return;
            }

            if (message instanceof Shutdown) {
                if (!state.inFlight().isEmpty()) {
                    throw new IllegalStateException(
                            "activity actor cannot stop with unresolved "
                                    + "external effects "
                                    + state.inFlight().keySet());
                }
                emit(context, new Stopping(state.workerId()));
                stateBlock.close();
                context.runtime().endCurrentActor();
                return;
            }

            throw new IllegalStateException(
                    "unsupported workflow activity actor message "
                            + message.getClass().getName());
        }

        private PrivateState readState() {
            byte[] payload =
                    WorkflowPrivateActorStateV1.read(stateBlock);
            if (payload.length == 0) {
                return PrivateState.empty();
            }
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(payload))) {
                int version = input.readInt();
                if (version != 1) {
                    throw new IllegalStateException(
                            "unsupported activity private-state version "
                                    + version);
                }
                String workerId = readString(input);
                WorkflowActivityProtocolV1.Kind kind =
                        WorkflowActivityProtocolV1.Kind.valueOf(
                                readString(input));
                int capabilityCount = input.readInt();
                if (capabilityCount < 0
                        || capabilityCount
                                > WorkflowActivityProtocolV1.Capability
                                        .values().length) {
                    throw new IllegalStateException(
                            "invalid activity capability count");
                }
                java.util.LinkedHashSet<
                        WorkflowActivityProtocolV1.Capability> capabilities =
                        new java.util.LinkedHashSet<>();
                for (int i = 0; i < capabilityCount; i++) {
                    capabilities.add(
                            WorkflowActivityProtocolV1.Capability.valueOf(
                                    readString(input)));
                }
                int inFlightCount = input.readInt();
                if (inFlightCount < 0 || inFlightCount > 10_000) {
                    throw new IllegalStateException(
                            "invalid activity in-flight count");
                }
                LinkedHashMap<String, WorkflowJournalV1.Claim> inFlight =
                        new LinkedHashMap<>();
                for (int i = 0; i < inFlightCount; i++) {
                    WorkflowJournalV1.Claim claim = readClaim(input);
                    if (inFlight.putIfAbsent(
                            claim.effectKey(), claim) != null) {
                        throw new IllegalStateException(
                                "duplicate in-flight effect key");
                    }
                }
                if (input.available() != 0) {
                    throw new IllegalStateException(
                            "trailing bytes in activity private state");
                }
                return new PrivateState(
                        workerId,
                        kind,
                        Set.copyOf(capabilities),
                        inFlight);
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof IllegalStateException stateFailure) {
                    throw stateFailure;
                }
                throw new IllegalStateException(
                        "invalid activity private state", failure);
            } finally {
                Arrays.fill(payload, (byte) 0);
            }
        }

        private void writeState(PrivateState state) {
            WorkflowPrivateActorStateV1.ScrubbableOutput bytes =
                    new WorkflowPrivateActorStateV1.ScrubbableOutput();
            try {
                try (DataOutputStream output =
                        new DataOutputStream(bytes)) {
                    output.writeInt(1);
                    writeString(output, state.workerId());
                    writeString(output, state.kind().name());
                    output.writeInt(state.capabilities().size());
                    state.capabilities().stream()
                            .map(Enum::name)
                            .sorted()
                            .forEach(name -> writeStringUnchecked(
                                    output, name));
                    output.writeInt(state.inFlight().size());
                    for (WorkflowJournalV1.Claim claim
                            : state.inFlight().values()) {
                        writeClaim(output, claim);
                    }
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
                        "failed to encode activity private state",
                        failure);
            } finally {
                bytes.wipe();
            }
        }

        private static void requireConfigured(PrivateState state) {
            if (!state.configured()) {
                throw new IllegalStateException(
                        "Configure must be the first activity actor message");
            }
        }

        private static String effectKey(
                WorkflowActivityProtocolV1.Outcome outcome) {
            return switch (outcome) {
                case WorkflowActivityProtocolV1.Succeeded value ->
                        value.effectKey();
                case WorkflowActivityProtocolV1.Completed value ->
                        value.effectKey();
                case WorkflowActivityProtocolV1.KnownUnexecuted value ->
                        value.effectKey();
                case WorkflowActivityProtocolV1.Ambiguous value ->
                        value.effectKey();
            };
        }

        private static void emit(
                ActorRuntime.ActorContext<Object> context,
                Output output) {
            context.runtime().emitCurrentActorOutput(
                    encodeOutput(output));
        }
    }

    private static void writeClaim(
            DataOutputStream output,
            WorkflowJournalV1.Claim claim) throws IOException {
        writeString(output, claim.effectKey());
        WorkflowSqlCodecV1.Encoded encoded =
                WorkflowSqlCodecV1.encodeCommand(claim.command());
        writeEncoded(output, encoded);
        writeString(output, claim.claimToken());
        output.writeInt(claim.attempt());
        output.writeLong(claim.leaseUntilMillis());
        writeNullableString(output, claim.workerId());
    }

    private static WorkflowJournalV1.Claim readClaim(
            DataInputStream input) throws IOException {
        String effectKey = readString(input);
        DurableDevLoopV1.Command command =
                WorkflowSqlCodecV1.decodeCommand(
                        readEncoded(input));
        String claimToken = readString(input);
        int attempt = input.readInt();
        long leaseUntilMillis = input.readLong();
        String workerId = readNullableString(input);
        if (!effectKey.equals(command.effectKey())) {
            throw new IllegalStateException(
                    "activity claim effect key disagrees with command");
        }
        return new WorkflowJournalV1.Claim(
                effectKey,
                command,
                claimToken,
                attempt,
                leaseUntilMillis,
                workerId);
    }

    private static void writeEncoded(
            DataOutputStream output,
            WorkflowSqlCodecV1.Encoded encoded) throws IOException {
        writeString(output, encoded.type());
        output.writeBoolean(encoded.int1() != null);
        if (encoded.int1() != null) {
            output.writeInt(encoded.int1());
        }
        writeNullableString(output, encoded.text1());
        writeNullableString(output, encoded.text2());
        writeNullableString(output, encoded.text3());
        writeNullableString(output, encoded.text4());
    }

    private static WorkflowSqlCodecV1.Encoded readEncoded(
            DataInputStream input) throws IOException {
        String type = readString(input);
        Integer int1 = input.readBoolean()
                ? input.readInt()
                : null;
        return new WorkflowSqlCodecV1.Encoded(
                type,
                int1,
                readNullableString(input),
                readNullableString(input),
                readNullableString(input),
                readNullableString(input));
    }

    private static void writeStringUnchecked(
            DataOutputStream output,
            String value) {
        try {
            writeString(output, value);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "failed to encode activity private state",
                    failure);
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
                    "workerId", value.workerId(),
                    "kind", value.kind().name(),
                    "capabilities",
                            WorkflowActorWireV1.capabilities(
                                    value.capabilities()));
        }
        if (message instanceof Dispatch value) {
            return WorkflowActorWireV1.map(
                    "type", "dispatch",
                    "runId", value.runId(),
                    "claim", WorkflowActorWireV1.claim(value.claim()));
        }
        if (message instanceof ReportOutcome value) {
            return WorkflowActorWireV1.map(
                    "type", "report",
                    "outcome",
                            WorkflowActorWireV1.activityOutcome(
                                    value.outcome()));
        }
        if (message instanceof CancelAttempt value) {
            return WorkflowActorWireV1.map(
                    "type", "cancel_attempt",
                    "effectKey", value.effectKey(),
                    "claimToken", value.claimToken(),
                    "reason", value.reason());
        }
        if (message instanceof Shutdown) {
            return WorkflowActorWireV1.map("type", "shutdown");
        }
        throw new IllegalArgumentException(
                "unsupported activity actor message "
                        + message.getClass().getName());
    }

    private static Message decodeMessage(Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return switch (WorkflowActorWireV1.string(map, "type")) {
            case "configure" -> new Configure(
                    WorkflowActorWireV1.string(map, "workerId"),
                    WorkflowActivityProtocolV1.Kind.valueOf(
                            WorkflowActorWireV1.string(map, "kind")),
                    WorkflowActorWireV1.capabilities(
                            map.get("capabilities")));
            case "dispatch" -> new Dispatch(
                    WorkflowActorWireV1.string(map, "runId"),
                    WorkflowActorWireV1.claim(map.get("claim")));
            case "report" -> new ReportOutcome(
                    WorkflowActorWireV1.activityOutcome(
                            map.get("outcome")));
            case "cancel_attempt" -> new CancelAttempt(
                    WorkflowActorWireV1.string(map, "effectKey"),
                    WorkflowActorWireV1.string(map, "claimToken"),
                    WorkflowActorWireV1.string(map, "reason"));
            case "shutdown" -> new Shutdown();
            default -> throw new IllegalArgumentException(
                    "unknown activity actor message type "
                            + WorkflowActorWireV1.string(map, "type"));
        };
    }

    private static Map<String, Object> encodeOutput(Output output) {
        if (output instanceof Ready value) {
            return WorkflowActorWireV1.map(
                    "type", "ready",
                    "workerId", value.workerId(),
                    "kind", value.kind().name());
        }
        if (output instanceof Authorized value) {
            return WorkflowActorWireV1.map(
                    "type", "authorized",
                    "request", encodeRequest(value.request()));
        }
        if (output instanceof OutcomeAccepted value) {
            return WorkflowActorWireV1.map(
                    "type", "outcome_accepted",
                    "outcome",
                            WorkflowActorWireV1.activityOutcome(
                                    value.outcome()));
        }
        if (output instanceof AttemptCancelled value) {
            return WorkflowActorWireV1.map(
                    "type", "attempt_cancelled",
                    "effectKey", value.effectKey(),
                    "claimToken", value.claimToken(),
                    "reason", value.reason());
        }
        if (output instanceof Stopping value) {
            return WorkflowActorWireV1.map(
                    "type", "stopping",
                    "workerId", value.workerId());
        }
        throw new IllegalArgumentException(
                "unsupported activity actor output "
                        + output.getClass().getName());
    }

    private static Map<String, Object> encodeRequest(
            WorkflowActivityProtocolV1.Request request) {
        return WorkflowActorWireV1.map(
                "runId", request.runId(),
                "claim", WorkflowActorWireV1.claim(request.claim()),
                "kind", request.kind().name(),
                "capabilities",
                        WorkflowActorWireV1.capabilities(
                                request.grantedCapabilities()));
    }

    private static WorkflowActivityProtocolV1.Request decodeRequest(
            Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return new WorkflowActivityProtocolV1.Request(
                WorkflowActorWireV1.string(map, "runId"),
                WorkflowActorWireV1.claim(map.get("claim")),
                WorkflowActivityProtocolV1.Kind.valueOf(
                        WorkflowActorWireV1.string(map, "kind")),
                WorkflowActorWireV1.capabilities(
                        map.get("capabilities")));
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
