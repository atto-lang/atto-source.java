package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * ActorRuntime-backed durable development-loop coordinator.
 *
 * <p>The public records are host-side convenience types only. Every mailbox
 * message and actor output is encoded as maps/lists/scalars through
 * {@link WorkflowActorWireV1}, preserving ActorRuntime's data-only transport
 * boundary. SQLite remains the durable authority.</p>
 */
public final class DurableDevLoopActorV1 {

    public sealed interface Message permits OpenRun, Deliver,
            ClaimEffect, CompleteEffect, CompleteAndDeliver,
            ReleaseEffect, ExpireClaims, ReconcileCompleted,
            ReconcileRetry, QueryProjection, Shutdown { }

    public record OpenRun(
            String databasePath,
            String runId,
            int maxIterations,
            String startEventId,
            String goal) implements Message {
        public OpenRun {
            databasePath = requireText(databasePath, "databasePath");
            runId = requireText(runId, "runId");
            startEventId = requireText(startEventId, "startEventId");
            goal = requireText(goal, "goal");
            if (maxIterations <= 0 || maxIterations > 10_000) {
                throw new IllegalArgumentException(
                        "maxIterations must be in [1, 10000]");
            }
        }
    }

    public record Deliver(
            String eventId,
            DurableDevLoopV1.Event event) implements Message {
        public Deliver {
            eventId = requireText(eventId, "eventId");
            event = Objects.requireNonNull(event, "event");
        }
    }

    public record ClaimEffect(
            String workerId,
            long nowMillis,
            long leaseMillis) implements Message {
        public ClaimEffect {
            workerId = requireText(workerId, "workerId");
        }
    }

    public record CompleteEffect(
            String effectKey,
            String claimToken,
            String receiptRef,
            long nowMillis) implements Message {
        public CompleteEffect {
            effectKey = requireText(effectKey, "effectKey");
            claimToken = requireText(claimToken, "claimToken");
            receiptRef = requireText(receiptRef, "receiptRef");
        }
    }

    public record CompleteAndDeliver(
            String effectKey,
            String claimToken,
            String receiptRef,
            long nowMillis,
            String eventId,
            DurableDevLoopV1.Event event) implements Message {
        public CompleteAndDeliver {
            effectKey = requireText(effectKey, "effectKey");
            claimToken = requireText(claimToken, "claimToken");
            receiptRef = requireText(receiptRef, "receiptRef");
            eventId = requireText(eventId, "eventId");
            event = Objects.requireNonNull(event, "event");
        }
    }

    public record ReleaseEffect(
            String effectKey,
            String claimToken,
            String reasonRef,
            long nowMillis) implements Message {
        public ReleaseEffect {
            effectKey = requireText(effectKey, "effectKey");
            claimToken = requireText(claimToken, "claimToken");
            reasonRef = requireText(reasonRef, "reasonRef");
        }
    }

    public record ExpireClaims(long nowMillis) implements Message { }

    public record ReconcileCompleted(
            String effectKey,
            String receiptRef,
            String reconciliationRef) implements Message {
        public ReconcileCompleted {
            effectKey = requireText(effectKey, "effectKey");
            receiptRef = requireText(receiptRef, "receiptRef");
            reconciliationRef = requireText(
                    reconciliationRef, "reconciliationRef");
        }
    }

    public record ReconcileRetry(
            String effectKey,
            String reconciliationRef) implements Message {
        public ReconcileRetry {
            effectKey = requireText(effectKey, "effectKey");
            reconciliationRef = requireText(
                    reconciliationRef, "reconciliationRef");
        }
    }

    public record QueryProjection() implements Message { }

    public record Shutdown(String reason) implements Message {
        public Shutdown {
            reason = requireText(reason, "reason");
        }
    }

    public sealed interface Output permits Opened, Transitioned,
            EffectClaimed, NoEffectAvailable, EffectUpdated,
            ProjectionPublished, Stopping { }

    public record Opened(
            WorkflowRunProjectionV1 projection) implements Output {
        public Opened {
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record Transitioned(
            DurableDevLoopV1.JournalEntry journalEntry,
            WorkflowRunProjectionV1 projection) implements Output {
        public Transitioned {
            journalEntry = Objects.requireNonNull(
                    journalEntry, "journalEntry");
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record EffectClaimed(
            WorkflowJournalV1.Claim claim,
            WorkflowRunProjectionV1 projection) implements Output {
        public EffectClaimed {
            claim = Objects.requireNonNull(claim, "claim");
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record NoEffectAvailable(
            WorkflowRunProjectionV1 projection) implements Output {
        public NoEffectAvailable {
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record EffectUpdated(
            String effectKey,
            WorkflowRunProjectionV1 projection) implements Output {
        public EffectUpdated {
            effectKey = requireText(effectKey, "effectKey");
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record ProjectionPublished(
            WorkflowRunProjectionV1 projection) implements Output {
        public ProjectionPublished {
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record Stopping(
            String runId,
            String reason) implements Output {
        public Stopping {
            runId = requireText(runId, "runId");
            reason = requireText(reason, "reason");
        }
    }

    /** Host-side typed facade over the data-only ActorRuntime reference. */
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

    private DurableDevLoopActorV1() { }

    public static Ref spawn(ActorRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        ActorRuntime.ActorRef<Object> raw =
                runtime.spawnPrivate(
                        DurableDevLoopActorV1::createBehavior);
        return new Ref(raw);
    }

    public static Output decodeOutput(Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return switch (WorkflowActorWireV1.string(map, "type")) {
            case "opened" -> new Opened(
                    WorkflowActorWireV1.projection(
                            map.get("projection")));
            case "transitioned" -> new Transitioned(
                    WorkflowActorWireV1.journalEntry(
                            map.get("journalEntry")),
                    WorkflowActorWireV1.projection(
                            map.get("projection")));
            case "effect_claimed" -> new EffectClaimed(
                    WorkflowActorWireV1.claim(map.get("claim")),
                    WorkflowActorWireV1.projection(
                            map.get("projection")));
            case "no_effect" -> new NoEffectAvailable(
                    WorkflowActorWireV1.projection(
                            map.get("projection")));
            case "effect_updated" -> new EffectUpdated(
                    WorkflowActorWireV1.string(map, "effectKey"),
                    WorkflowActorWireV1.projection(
                            map.get("projection")));
            case "projection" -> new ProjectionPublished(
                    WorkflowActorWireV1.projection(
                            map.get("projection")));
            case "stopping" -> new Stopping(
                    WorkflowActorWireV1.string(map, "runId"),
                    WorkflowActorWireV1.string(map, "reason"));
            default -> throw new IllegalArgumentException(
                    "unknown dev-loop actor output type "
                            + WorkflowActorWireV1.string(map, "type"));
        };
    }

    private static final int PRIVATE_STATE_BYTES = 16_384;

    private record PrivateConfig(
            String databasePath,
            String runId,
            int maxIterations) { }

    private static ActorRuntime.Behavior<Object> createBehavior(
            ActorRuntime.ActorContext<Object> context) {
        ActorRuntime.PrivateMemoryBlock state =
                context.privateMemory()
                        .orElseThrow(() -> new IllegalStateException(
                                "dev-loop actor requires private memory"))
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

            if (message instanceof OpenRun open) {
                open(open, config, context);
                return;
            }

            requireOpen(config);

            if (message instanceof Shutdown shutdown) {
                emit(
                        context,
                        new Stopping(
                                config.runId(),
                                shutdown.reason()));
                stateBlock.close();
                context.runtime().endCurrentActor();
                return;
            }

            try (SqliteWorkflowJournalV1 store =
                    SqliteWorkflowJournalV1.open(
                            Path.of(config.databasePath()))) {
                verifyConfig(store, config);

                if (message instanceof Deliver deliver) {
                    if (requiresAtomicCompletion(deliver.event())) {
                        throw new IllegalStateException(
                                "causal workflow outcome requires atomic "
                                        + "CompleteAndDeliver: "
                                        + deliver.event()
                                                .getClass()
                                                .getSimpleName());
                    }
                    DurableDevLoopV1 machine =
                            store.replayDevLoop().machine();
                    DurableDevLoopV1.Transition transition =
                            machine.accept(
                                    deliver.eventId(),
                                    deliver.event());
                    store.append(transition);
                    emit(
                            context,
                            new Transitioned(
                                    transition.journalEntry(),
                                    projection(store, config)));
                    return;
                }

                if (message instanceof ClaimEffect claim) {
                    var claimed = store.claimNext(
                            claim.workerId(),
                            claim.nowMillis(),
                            claim.leaseMillis());
                    emit(
                            context,
                            claimed.<Output>map(value ->
                                    new EffectClaimed(
                                            value,
                                            projection(
                                                    store,
                                                    config)))
                                    .orElseGet(() ->
                                            new NoEffectAvailable(
                                                    projection(
                                                            store,
                                                            config))));
                    return;
                }

                if (message instanceof CompleteEffect complete) {
                    DurableDevLoopV1.Command command =
                            store.effect(complete.effectKey())
                                    .orElseThrow(() ->
                                            new IllegalArgumentException(
                                                    "unknown effect key "
                                                            + complete.effectKey()))
                                    .command();
                    if (requiresOutcome(command)) {
                        throw new IllegalStateException(
                                "effect " + complete.effectKey()
                                        + " requires atomic CompleteAndDeliver");
                    }
                    store.complete(
                            complete.effectKey(),
                            complete.claimToken(),
                            complete.receiptRef(),
                            complete.nowMillis());
                    emit(
                            context,
                            new EffectUpdated(
                                    complete.effectKey(),
                                    projection(store, config)));
                    return;
                }

                if (message instanceof CompleteAndDeliver complete) {
                    DurableDevLoopV1.Transition transition =
                            store.completeAndAppend(
                                    complete.effectKey(),
                                    complete.claimToken(),
                                    complete.receiptRef(),
                                    complete.nowMillis(),
                                    complete.eventId(),
                                    complete.event());
                    emit(
                            context,
                            new Transitioned(
                                    transition.journalEntry(),
                                    projection(store, config)));
                    return;
                }

                if (message instanceof ReleaseEffect release) {
                    store.releaseUnexecuted(
                            release.effectKey(),
                            release.claimToken(),
                            release.reasonRef(),
                            release.nowMillis());
                    emit(
                            context,
                            new EffectUpdated(
                                    release.effectKey(),
                                    projection(store, config)));
                    return;
                }

                if (message instanceof ExpireClaims expire) {
                    store.expireClaims(expire.nowMillis());
                    emit(
                            context,
                            new ProjectionPublished(
                                    projection(store, config)));
                    return;
                }

                if (message instanceof ReconcileCompleted completed) {
                    store.resolveAmbiguousAsCompleted(
                            completed.effectKey(),
                            completed.receiptRef(),
                            completed.reconciliationRef());
                    emit(
                            context,
                            new EffectUpdated(
                                    completed.effectKey(),
                                    projection(store, config)));
                    return;
                }

                if (message instanceof ReconcileRetry retry) {
                    store.resolveAmbiguousForRetry(
                            retry.effectKey(),
                            retry.reconciliationRef());
                    emit(
                            context,
                            new EffectUpdated(
                                    retry.effectKey(),
                                    projection(store, config)));
                    return;
                }

                if (message instanceof QueryProjection) {
                    emit(
                            context,
                            new ProjectionPublished(
                                    projection(store, config)));
                    return;
                }
            }

            throw new IllegalStateException(
                    "unsupported dev-loop actor message "
                            + message.getClass().getName());
        }

        private void open(
                OpenRun open,
                PrivateConfig existing,
                ActorRuntime.ActorContext<Object> context) {
            if (existing != null) {
                throw new IllegalStateException(
                        "dev-loop actor is already open");
            }

            try (SqliteWorkflowJournalV1 store =
                    SqliteWorkflowJournalV1.open(
                            Path.of(open.databasePath()))) {
                WorkflowJournalV1.StoreSnapshot snapshot =
                        store.snapshot();
                DurableDevLoopV1 machine;

                if (snapshot.runId() == null) {
                    machine = new DurableDevLoopV1(
                            open.runId(), open.maxIterations());
                    store.append(machine.accept(
                            open.startEventId(),
                            new DurableDevLoopV1.Start(open.goal())));
                } else {
                    if (!open.runId().equals(snapshot.runId())
                            || snapshot.maxIterations() == null
                            || open.maxIterations()
                                    != snapshot.maxIterations()) {
                        throw new IllegalStateException(
                                "existing durable run identity/policy "
                                        + "does not match OpenRun");
                    }
                    machine = store.replayDevLoop().machine();
                    if (!open.goal().equals(
                            machine.snapshot().goal())) {
                        throw new IllegalStateException(
                                "existing durable run goal does not "
                                        + "match OpenRun");
                    }
                }

                PrivateConfig config = new PrivateConfig(
                        open.databasePath(),
                        open.runId(),
                        open.maxIterations());
                writeConfig(config);
                emit(
                        context,
                        new Opened(projection(store, config)));
            }
        }

        private static void verifyConfig(
                SqliteWorkflowJournalV1 store,
                PrivateConfig config) {
            WorkflowJournalV1.StoreSnapshot snapshot =
                    store.snapshot();
            if (!config.runId().equals(snapshot.runId())
                    || snapshot.maxIterations() == null
                    || config.maxIterations()
                            != snapshot.maxIterations()) {
                throw new IllegalStateException(
                        "actor durable run identity/policy drift");
            }
        }

        private static WorkflowRunProjectionV1 projection(
                SqliteWorkflowJournalV1 store,
                PrivateConfig config) {
            DurableDevLoopV1.Snapshot reducer =
                    store.replayDevLoop().machine().snapshot();
            WorkflowJournalV1.StoreSnapshot persisted =
                    store.snapshot();
            if (!config.runId().equals(reducer.runId())
                    || config.maxIterations()
                            != reducer.maxIterations()) {
                throw new IllegalStateException(
                        "actor durable run identity/policy drift");
            }
            return WorkflowRunProjectionV1.from(
                    reducer, persisted);
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
                            "unsupported dev-loop private-state version "
                                    + version);
                }
                String databasePath = readString(input);
                String runId = readString(input);
                int maxIterations = input.readInt();
                if (maxIterations <= 0 || maxIterations > 10_000) {
                    throw new IllegalStateException(
                            "invalid persisted maxIterations");
                }
                if (input.available() != 0) {
                    throw new IllegalStateException(
                            "trailing bytes in dev-loop private state");
                }
                return new PrivateConfig(
                        databasePath,
                        runId,
                        maxIterations);
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof IllegalStateException stateFailure) {
                    throw stateFailure;
                }
                throw new IllegalStateException(
                        "invalid dev-loop private state",
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
                    output.writeInt(config.maxIterations());
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
                        "failed to encode dev-loop private state",
                        failure);
            } finally {
                bytes.wipe();
            }
        }

        private static void requireOpen(PrivateConfig config) {
            if (config == null) {
                throw new IllegalStateException(
                        "OpenRun must be the first dev-loop actor message");
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

    private static Map<String, Object> encodeMessage(Message message) {
        if (message instanceof OpenRun value) {
            return WorkflowActorWireV1.map(
                    "type", "open",
                    "databasePath", value.databasePath(),
                    "runId", value.runId(),
                    "maxIterations", value.maxIterations(),
                    "startEventId", value.startEventId(),
                    "goal", value.goal());
        }
        if (message instanceof Deliver value) {
            return WorkflowActorWireV1.map(
                    "type", "deliver",
                    "eventId", value.eventId(),
                    "event", WorkflowActorWireV1.event(value.event()));
        }
        if (message instanceof ClaimEffect value) {
            return WorkflowActorWireV1.map(
                    "type", "claim",
                    "workerId", value.workerId(),
                    "nowMillis", value.nowMillis(),
                    "leaseMillis", value.leaseMillis());
        }
        if (message instanceof CompleteEffect value) {
            return WorkflowActorWireV1.map(
                    "type", "complete",
                    "effectKey", value.effectKey(),
                    "claimToken", value.claimToken(),
                    "receiptRef", value.receiptRef(),
                    "nowMillis", value.nowMillis());
        }
        if (message instanceof CompleteAndDeliver value) {
            return WorkflowActorWireV1.map(
                    "type", "complete_and_deliver",
                    "effectKey", value.effectKey(),
                    "claimToken", value.claimToken(),
                    "receiptRef", value.receiptRef(),
                    "nowMillis", value.nowMillis(),
                    "eventId", value.eventId(),
                    "event", WorkflowActorWireV1.event(value.event()));
        }
        if (message instanceof ReleaseEffect value) {
            return WorkflowActorWireV1.map(
                    "type", "release",
                    "effectKey", value.effectKey(),
                    "claimToken", value.claimToken(),
                    "reasonRef", value.reasonRef(),
                    "nowMillis", value.nowMillis());
        }
        if (message instanceof ExpireClaims value) {
            return WorkflowActorWireV1.map(
                    "type", "expire",
                    "nowMillis", value.nowMillis());
        }
        if (message instanceof ReconcileCompleted value) {
            return WorkflowActorWireV1.map(
                    "type", "reconcile_completed",
                    "effectKey", value.effectKey(),
                    "receiptRef", value.receiptRef(),
                    "reconciliationRef", value.reconciliationRef());
        }
        if (message instanceof ReconcileRetry value) {
            return WorkflowActorWireV1.map(
                    "type", "reconcile_retry",
                    "effectKey", value.effectKey(),
                    "reconciliationRef", value.reconciliationRef());
        }
        if (message instanceof QueryProjection) {
            return WorkflowActorWireV1.map("type", "query");
        }
        if (message instanceof Shutdown value) {
            return WorkflowActorWireV1.map(
                    "type", "shutdown",
                    "reason", value.reason());
        }
        throw new IllegalArgumentException(
                "unsupported dev-loop message "
                        + message.getClass().getName());
    }

    private static Message decodeMessage(Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return switch (WorkflowActorWireV1.string(map, "type")) {
            case "open" -> new OpenRun(
                    WorkflowActorWireV1.string(map, "databasePath"),
                    WorkflowActorWireV1.string(map, "runId"),
                    WorkflowActorWireV1.integer(map, "maxIterations"),
                    WorkflowActorWireV1.string(map, "startEventId"),
                    WorkflowActorWireV1.string(map, "goal"));
            case "deliver" -> new Deliver(
                    WorkflowActorWireV1.string(map, "eventId"),
                    WorkflowActorWireV1.event(map.get("event")));
            case "claim" -> new ClaimEffect(
                    WorkflowActorWireV1.string(map, "workerId"),
                    WorkflowActorWireV1.longValue(map, "nowMillis"),
                    WorkflowActorWireV1.longValue(map, "leaseMillis"));
            case "complete" -> new CompleteEffect(
                    WorkflowActorWireV1.string(map, "effectKey"),
                    WorkflowActorWireV1.string(map, "claimToken"),
                    WorkflowActorWireV1.string(map, "receiptRef"),
                    WorkflowActorWireV1.longValue(map, "nowMillis"));
            case "complete_and_deliver" ->
                    new CompleteAndDeliver(
                            WorkflowActorWireV1.string(map, "effectKey"),
                            WorkflowActorWireV1.string(map, "claimToken"),
                            WorkflowActorWireV1.string(map, "receiptRef"),
                            WorkflowActorWireV1.longValue(map, "nowMillis"),
                            WorkflowActorWireV1.string(map, "eventId"),
                            WorkflowActorWireV1.event(map.get("event")));
            case "release" -> new ReleaseEffect(
                    WorkflowActorWireV1.string(map, "effectKey"),
                    WorkflowActorWireV1.string(map, "claimToken"),
                    WorkflowActorWireV1.string(map, "reasonRef"),
                    WorkflowActorWireV1.longValue(map, "nowMillis"));
            case "expire" -> new ExpireClaims(
                    WorkflowActorWireV1.longValue(map, "nowMillis"));
            case "reconcile_completed" -> new ReconcileCompleted(
                    WorkflowActorWireV1.string(map, "effectKey"),
                    WorkflowActorWireV1.string(map, "receiptRef"),
                    WorkflowActorWireV1.string(
                            map, "reconciliationRef"));
            case "reconcile_retry" -> new ReconcileRetry(
                    WorkflowActorWireV1.string(map, "effectKey"),
                    WorkflowActorWireV1.string(
                            map, "reconciliationRef"));
            case "query" -> new QueryProjection();
            case "shutdown" -> new Shutdown(
                    WorkflowActorWireV1.string(map, "reason"));
            default -> throw new IllegalArgumentException(
                    "unknown dev-loop actor message type "
                            + WorkflowActorWireV1.string(map, "type"));
        };
    }

    private static Map<String, Object> encodeOutput(Output output) {
        if (output instanceof Opened value) {
            return WorkflowActorWireV1.map(
                    "type", "opened",
                    "projection",
                            WorkflowActorWireV1.projection(
                                    value.projection()));
        }
        if (output instanceof Transitioned value) {
            return WorkflowActorWireV1.map(
                    "type", "transitioned",
                    "journalEntry",
                            WorkflowActorWireV1.journalEntry(
                                    value.journalEntry()),
                    "projection",
                            WorkflowActorWireV1.projection(
                                    value.projection()));
        }
        if (output instanceof EffectClaimed value) {
            return WorkflowActorWireV1.map(
                    "type", "effect_claimed",
                    "claim", WorkflowActorWireV1.claim(value.claim()),
                    "projection",
                            WorkflowActorWireV1.projection(
                                    value.projection()));
        }
        if (output instanceof NoEffectAvailable value) {
            return WorkflowActorWireV1.map(
                    "type", "no_effect",
                    "projection",
                            WorkflowActorWireV1.projection(
                                    value.projection()));
        }
        if (output instanceof EffectUpdated value) {
            return WorkflowActorWireV1.map(
                    "type", "effect_updated",
                    "effectKey", value.effectKey(),
                    "projection",
                            WorkflowActorWireV1.projection(
                                    value.projection()));
        }
        if (output instanceof ProjectionPublished value) {
            return WorkflowActorWireV1.map(
                    "type", "projection",
                    "projection",
                            WorkflowActorWireV1.projection(
                                    value.projection()));
        }
        if (output instanceof Stopping value) {
            return WorkflowActorWireV1.map(
                    "type", "stopping",
                    "runId", value.runId(),
                    "reason", value.reason());
        }
        throw new IllegalArgumentException(
                "unsupported dev-loop output "
                        + output.getClass().getName());
    }

    private static boolean requiresAtomicCompletion(
            DurableDevLoopV1.Event event) {
        return event instanceof DurableDevLoopV1.CodeChanged
                || event instanceof DurableDevLoopV1.PushRecorded
                || event instanceof DurableDevLoopV1.CiCompleted
                || event instanceof DurableDevLoopV1.EvaluationPassed
                || event instanceof DurableDevLoopV1.EvaluationFailed
                || event instanceof DurableDevLoopV1.BugReportCreated
                || event instanceof DurableDevLoopV1.ApprovalGranted;
    }

    private static boolean requiresOutcome(
            DurableDevLoopV1.Command command) {
        return command instanceof DurableDevLoopV1.RequestCode
                || command instanceof DurableDevLoopV1.PushCode
                || command instanceof DurableDevLoopV1.AwaitCi
                || command instanceof DurableDevLoopV1.Evaluate
                || command instanceof DurableDevLoopV1.CreateBugReport
                || command instanceof DurableDevLoopV1.RequestApproval;
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
