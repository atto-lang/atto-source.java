package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Private authenticated GitHub ingress actor.
 *
 * <p>Only maps/lists/scalars cross ActorRuntime. Raw webhook bytes and the
 * secret are base64 encoded by the host facade before transport, decoded only
 * on the serialized actor lane, and never exposed through actor outputs.</p>
 */
public final class GitHubWebhookActorV1 {

    private static final int MAX_MATCHED_DELIVERIES = 4_096;
    private static final int MAX_DELIVERY_IDENTITIES =
            MAX_MATCHED_DELIVERIES * 2;
    private static final int MAX_EXPECTATIONS = 10_000;
    private static final int MAX_SECRET_BYTES = 4_096;
    private static final int MAX_STATE_STRING_BYTES = 65_536;
    private static final int PRIVATE_STATE_BYTES = 16 * 1024 * 1024;

    public sealed interface Message permits Configure, RegisterRun,
            UnregisterRun, ReceiveCiWebhook, Shutdown { }

    public record Configure(
            byte[] secret,
            String databasePath) implements Message {
        public Configure {
            Objects.requireNonNull(secret, "secret");
            if (secret.length < 16
                    || secret.length > MAX_SECRET_BYTES) {
                throw new IllegalArgumentException(
                        "webhook secret must contain between 16 and "
                                + MAX_SECRET_BYTES + " bytes");
            }
            secret = secret.clone();
            if (databasePath != null) {
                databasePath = requireText(
                        databasePath, "databasePath");
            }
        }

        public Configure(byte[] secret) {
            this(secret, null);
        }

        @Override
        public byte[] secret() {
            return secret.clone();
        }
    }

    public record RegisterRun(
            GitHubCiCorrelationV1.Expectation expectation)
            implements Message {
        public RegisterRun {
            expectation = Objects.requireNonNull(
                    expectation, "expectation");
        }
    }

    public record UnregisterRun(String runId) implements Message {
        public UnregisterRun {
            runId = requireText(runId, "runId");
        }
    }

    public record ReceiveCiWebhook(
            GitHubWebhookSecurityV1.Headers headers,
            byte[] rawBody,
            GitHubWorkflowRunPayloadV1.GitTreeProof treeProof)
            implements Message {
        public ReceiveCiWebhook {
            headers = Objects.requireNonNull(headers, "headers");
            Objects.requireNonNull(rawBody, "rawBody");
            rawBody = rawBody.clone();
            treeProof = Objects.requireNonNull(treeProof, "treeProof");
        }

        @Override
        public byte[] rawBody() {
            return rawBody.clone();
        }
    }

    public record Shutdown() implements Message { }

    public sealed interface Output permits Ready, RunRegistered,
            RunUnregistered, Matched, Unmatched, Rejected, Stopping { }

    public record Ready() implements Output { }

    public record RunRegistered(String runId) implements Output {
        public RunRegistered {
            runId = requireText(runId, "runId");
        }
    }

    public record RunUnregistered(String runId) implements Output {
        public RunUnregistered {
            runId = requireText(runId, "runId");
        }
    }

    public record Matched(
            String runId,
            String deliveryId,
            String payloadSha256,
            long workflowRunId,
            String treeProofReceiptRef,
            DurableDevLoopV1.CiCompleted event)
            implements Output {
        public Matched {
            runId = requireText(runId, "runId");
            deliveryId = requireText(deliveryId, "deliveryId");
            payloadSha256 = requireText(
                    payloadSha256, "payloadSha256");
            treeProofReceiptRef = requireText(
                    treeProofReceiptRef, "treeProofReceiptRef");
            event = Objects.requireNonNull(event, "event");
            if (workflowRunId <= 0) {
                throw new IllegalArgumentException(
                        "workflowRunId must be positive");
            }
        }
    }

    public record Unmatched(
            String deliveryId,
            String payloadSha256,
            String reason) implements Output {
        public Unmatched {
            deliveryId = requireText(deliveryId, "deliveryId");
            payloadSha256 = requireText(
                    payloadSha256, "payloadSha256");
            reason = requireText(reason, "reason");
        }
    }

    public record Rejected(String reason) implements Output {
        public Rejected {
            reason = requireText(reason, "reason");
        }
    }

    public record Stopping() implements Output { }

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

    private GitHubWebhookActorV1() { }

    public static Ref spawn(ActorRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return new Ref(runtime.spawnPrivate(
                GitHubWebhookActorV1::createBehavior));
    }

    public static Output decodeOutput(Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return switch (WorkflowActorWireV1.string(map, "type")) {
            case "ready" -> new Ready();
            case "run_registered" -> new RunRegistered(
                    WorkflowActorWireV1.string(map, "runId"));
            case "run_unregistered" -> new RunUnregistered(
                    WorkflowActorWireV1.string(map, "runId"));
            case "matched" -> {
                DurableDevLoopV1.Event event =
                        WorkflowActorWireV1.event(
                                map.get("event"));
                if (!(event instanceof DurableDevLoopV1.CiCompleted ci)) {
                    throw new IllegalArgumentException(
                            "matched webhook output does not contain CI event");
                }
                yield new Matched(
                        WorkflowActorWireV1.string(map, "runId"),
                        WorkflowActorWireV1.string(map, "deliveryId"),
                        WorkflowActorWireV1.string(
                                map, "payloadSha256"),
                        WorkflowActorWireV1.longValue(
                                map, "workflowRunId"),
                        WorkflowActorWireV1.string(
                                map, "treeProofReceiptRef"),
                        ci);
            }
            case "unmatched" -> new Unmatched(
                    WorkflowActorWireV1.string(map, "deliveryId"),
                    WorkflowActorWireV1.string(
                            map, "payloadSha256"),
                    WorkflowActorWireV1.string(map, "reason"));
            case "rejected" -> new Rejected(
                    WorkflowActorWireV1.string(map, "reason"));
            case "stopping" -> new Stopping();
            default -> throw new IllegalArgumentException(
                    "unknown GitHub ingress output type "
                            + WorkflowActorWireV1.string(map, "type"));
        };
    }

    private static ActorRuntime.Behavior<Object> createBehavior(
            ActorRuntime.ActorContext<Object> context) {
        ActorRuntime.PrivateMemoryBlock state =
                context.privateMemory()
                        .orElseThrow(() -> new IllegalStateException(
                                "GitHub webhook actor requires private memory"))
                        .allocatePrivateBytes(PRIVATE_STATE_BYTES);
        return new Behavior(state);
    }

    private static final class Behavior
            implements ActorRuntime.Behavior<Object> {
        private record MatchedDelivery(
                String payloadSha256,
                Matched output) { }

        private record PrivateState(
                byte[] secret,
                String databasePath,
                LinkedHashMap<String, GitHubCiCorrelationV1.Expectation>
                        expectations,
                LinkedHashMap<String, MatchedDelivery> matchedDeliveries,
                LinkedHashMap<String, String> deliveryIdentities) {

            private static PrivateState empty() {
                return new PrivateState(
                        null,
                        null,
                        new LinkedHashMap<>(),
                        new LinkedHashMap<>(),
                        new LinkedHashMap<>());
            }

            private boolean configured() {
                return secret != null;
            }

            private PrivateState withExpectations(
                    LinkedHashMap<String,
                            GitHubCiCorrelationV1.Expectation> value) {
                return new PrivateState(
                        secret,
                        databasePath,
                        value,
                        matchedDeliveries,
                        deliveryIdentities);
            }

            private PrivateState withMatchedDeliveries(
                    LinkedHashMap<String, MatchedDelivery> value) {
                return new PrivateState(
                        secret,
                        databasePath,
                        expectations,
                        value,
                        deliveryIdentities);
            }

            private PrivateState withDeliveryIdentities(
                    LinkedHashMap<String, String> value) {
                return new PrivateState(
                        secret,
                        databasePath,
                        expectations,
                        matchedDeliveries,
                        value);
            }
        }

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
                configure(configure, state, context);
                return;
            }

            requireConfigured(state);
            try {
                if (message instanceof RegisterRun register) {
                    register(register, state, context);
                    return;
                }

                if (message instanceof UnregisterRun unregister) {
                    unregister(unregister, state, context);
                    return;
                }

                if (message instanceof ReceiveCiWebhook receive) {
                    receive(receive, state, context);
                    return;
                }

                if (message instanceof Shutdown) {
                    emit(context, new Stopping());
                    stateBlock.close();
                    context.runtime().endCurrentActor();
                    return;
                }

                throw new IllegalStateException(
                        "unsupported GitHub webhook actor message "
                                + message.getClass().getName());
            } finally {
                Arrays.fill(state.secret(), (byte) 0);
            }
        }

        private void configure(
                Configure configure,
                PrivateState existing,
                ActorRuntime.ActorContext<Object> context) {
            if (existing.configured()) {
                throw new IllegalStateException(
                        "GitHub webhook actor is already configured");
            }

            LinkedHashMap<String, GitHubCiCorrelationV1.Expectation>
                    expectations = new LinkedHashMap<>();
            if (configure.databasePath() != null) {
                try (var ledger = SqliteGitHubWebhookLedgerV1.open(
                        Path.of(configure.databasePath()))) {
                    for (var expectation : ledger.expectations()) {
                        if (expectations.putIfAbsent(
                                expectation.runId(),
                                expectation) != null) {
                            throw new IllegalStateException(
                                    "duplicate durable GitHub run correlation "
                                            + expectation.runId());
                        }
                    }
                }
            }
            if (expectations.size() > MAX_EXPECTATIONS) {
                throw new IllegalStateException(
                        "too many durable GitHub run correlations");
            }

            byte[] secret = configure.secret();
            try {
                writeState(new PrivateState(
                        secret,
                        configure.databasePath(),
                        expectations,
                        new LinkedHashMap<>(),
                        new LinkedHashMap<>()));
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
            emit(context, new Ready());
        }

        private void register(
                RegisterRun register,
                PrivateState state,
                ActorRuntime.ActorContext<Object> context) {
            var expectation = register.expectation();
            var prior = state.expectations().get(
                    expectation.runId());
            if (prior != null && !prior.equals(expectation)) {
                throw new IllegalStateException(
                        "run correlation already registered differently: "
                                + expectation.runId());
            }

            if (state.databasePath() != null) {
                try (var ledger = SqliteGitHubWebhookLedgerV1.open(
                        Path.of(state.databasePath()))) {
                    ledger.register(expectation);
                }
            }

            if (prior == null) {
                if (state.expectations().size() >= MAX_EXPECTATIONS) {
                    throw new IllegalStateException(
                            "too many GitHub run correlations");
                }
                LinkedHashMap<String, GitHubCiCorrelationV1.Expectation>
                        next =
                        new LinkedHashMap<>(state.expectations());
                next.put(expectation.runId(), expectation);
                writeState(state.withExpectations(next));
            }

            emit(
                    context,
                    new RunRegistered(expectation.runId()));
        }

        private void unregister(
                UnregisterRun unregister,
                PrivateState state,
                ActorRuntime.ActorContext<Object> context) {
            if (state.databasePath() != null) {
                try (var ledger = SqliteGitHubWebhookLedgerV1.open(
                        Path.of(state.databasePath()))) {
                    ledger.unregister(unregister.runId());
                }
            }

            if (state.expectations().containsKey(
                    unregister.runId())) {
                LinkedHashMap<String, GitHubCiCorrelationV1.Expectation>
                        next =
                        new LinkedHashMap<>(state.expectations());
                next.remove(unregister.runId());
                writeState(state.withExpectations(next));
            }

            emit(
                    context,
                    new RunUnregistered(unregister.runId()));
        }

        private void receive(
                ReceiveCiWebhook receive,
                PrivateState state,
                ActorRuntime.ActorContext<Object> context) {
            try {
                var verified = GitHubWebhookSecurityV1.verify(
                        state.secret(),
                        receive.rawBody(),
                        receive.headers());

                if (!"workflow_run".equals(verified.eventName())) {
                    PrivateState recorded =
                            recordDelivery(state, verified, null);
                    if (recorded != state) {
                        writeState(recorded);
                    }
                    emit(
                            context,
                            new Unmatched(
                                    verified.deliveryId(),
                                    verified.payloadSha256(),
                                    "unsupported GitHub event type "
                                            + verified.eventName()));
                    return;
                }

                GitHubWorkflowRunPayloadV1.Parsed parsed =
                        GitHubWorkflowRunPayloadV1.parse(
                                receive.rawBody());
                receive.treeProof().verifyCommit(
                        parsed.commitSha());

                // Bind the delivery id only after the signed payload and exact
                // commit/tree proof agree. Rejected proof attempts must never
                // poison the durable or in-memory delivery identity.
                PrivateState recorded;
                try {
                    recorded = recordDelivery(
                            state,
                            verified,
                            receive.treeProof());
                } catch (SecurityException conflict) {
                    emit(
                            context,
                            new Unmatched(
                                    verified.deliveryId(),
                                    verified.payloadSha256(),
                                    conflict.getMessage()));
                    return;
                }
                if (recorded != state) {
                    writeState(recorded);
                }

                MatchedDelivery priorDelivery =
                        recorded.matchedDeliveries().get(
                                verified.deliveryId());
                if (priorDelivery != null) {
                    if (!priorDelivery.payloadSha256().equals(
                            verified.payloadSha256())) {
                        emit(
                                context,
                                new Unmatched(
                                        verified.deliveryId(),
                                        verified.payloadSha256(),
                                        "GitHub delivery id was reused with "
                                                + "different signed payload"));
                    } else {
                        emit(context, priorDelivery.output());
                    }
                    return;
                }

                GitHubCiCorrelationV1.Observed observed =
                        parsed.observed(
                                receive.treeProof().treeSha());

                int matched = 0;
                String matchedRunId = null;
                GitHubCiCorrelationV1.Result matchedResult = null;
                String lastReason =
                        "no registered run matched CI identity";
                for (var expectation
                        : recorded.expectations().values()) {
                    var result = GitHubCiCorrelationV1.correlate(
                            expectation,
                            observed);
                    if (!result.matched()) {
                        lastReason = result.reason();
                        continue;
                    }
                    matched++;
                    matchedRunId = expectation.runId();
                    matchedResult = result;
                }

                if (matched == 1) {
                    Matched output = new Matched(
                            matchedRunId,
                            verified.deliveryId(),
                            verified.payloadSha256(),
                            observed.workflowRunId(),
                            receive.treeProof().receiptRef(),
                            matchedResult.event());
                    PrivateState withMatch =
                            rememberMatchedDelivery(
                                    recorded,
                                    verified.deliveryId(),
                                    verified.payloadSha256(),
                                    output);
                    writeState(withMatch);
                    emit(context, output);
                } else if (matched == 0) {
                    emit(
                            context,
                            new Unmatched(
                                    verified.deliveryId(),
                                    verified.payloadSha256(),
                                    lastReason));
                } else {
                    emit(
                            context,
                            new Unmatched(
                                    verified.deliveryId(),
                                    verified.payloadSha256(),
                                    "ambiguous CI correlation matched "
                                            + matched
                                            + " registered runs"));
                }
            } catch (SecurityException | IllegalArgumentException failure) {
                emit(
                        context,
                        new Rejected(
                                "webhook rejected: "
                                        + failure.getMessage()));
            }
        }

        private PrivateState recordDelivery(
                PrivateState state,
                GitHubWebhookSecurityV1.VerifiedWebhook verified,
                GitHubWorkflowRunPayloadV1.GitTreeProof treeProof) {
            if (state.databasePath() != null) {
                try (var ledger = SqliteGitHubWebhookLedgerV1.open(
                        Path.of(state.databasePath()))) {
                    if (treeProof == null) {
                        ledger.recordDelivery(
                                verified.deliveryId(),
                                verified.payloadSha256(),
                                verified.eventName());
                    } else {
                        ledger.recordDelivery(
                                verified.deliveryId(),
                                verified.payloadSha256(),
                                verified.eventName(),
                                treeProof.commitSha(),
                                treeProof.treeSha(),
                                treeProof.receiptRef());
                    }
                }
                return state;
            }

            String identity =
                    verified.eventName()
                            + "\u0000"
                            + verified.payloadSha256()
                            + "\u0000"
                            + (treeProof == null
                                    ? "-"
                                    : treeProof.commitSha())
                            + "\u0000"
                            + (treeProof == null
                                    ? "-"
                                    : treeProof.treeSha())
                            + "\u0000"
                            + (treeProof == null
                                    ? "-"
                                    : treeProof.receiptRef());

            String prior = state.deliveryIdentities().get(
                    verified.deliveryId());
            if (prior != null) {
                if (!prior.equals(identity)) {
                    throw new SecurityException(
                            "GitHub delivery id was reused with "
                                    + "different authenticated content");
                }
                return state;
            }

            LinkedHashMap<String, String> next =
                    new LinkedHashMap<>(
                            state.deliveryIdentities());
            next.put(verified.deliveryId(), identity);
            if (next.size() > MAX_DELIVERY_IDENTITIES) {
                var iterator = next.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            return state.withDeliveryIdentities(next);
        }

        private PrivateState rememberMatchedDelivery(
                PrivateState state,
                String deliveryId,
                String payloadSha256,
                Matched output) {
            LinkedHashMap<String, MatchedDelivery> next =
                    new LinkedHashMap<>(
                            state.matchedDeliveries());
            if (!next.containsKey(deliveryId)
                    && next.size() >= MAX_MATCHED_DELIVERIES) {
                var iterator = next.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            next.put(
                    deliveryId,
                    new MatchedDelivery(
                            payloadSha256,
                            output));
            return state.withMatchedDeliveries(next);
        }

        private PrivateState readState() {
            byte[] payload =
                    WorkflowPrivateActorStateV1.read(stateBlock);
            if (payload.length == 0) {
                return PrivateState.empty();
            }

            byte[] decodedSecret = null;
            boolean decoded = false;
            try (DataInputStream input =
                         new DataInputStream(
                                 new ByteArrayInputStream(payload))) {
                int version = input.readInt();
                if (version != 1) {
                    throw new IllegalStateException(
                            "unsupported GitHub webhook private-state version "
                                    + version);
                }

                decodedSecret = readBytes(
                        input, 16, MAX_SECRET_BYTES, "secret");
                String databasePath =
                        readNullableString(input);

                int expectationCount = input.readInt();
                if (expectationCount < 0
                        || expectationCount > MAX_EXPECTATIONS) {
                    throw new IllegalStateException(
                            "invalid GitHub expectation count");
                }
                LinkedHashMap<String,
                        GitHubCiCorrelationV1.Expectation> expectations =
                        new LinkedHashMap<>();
                for (int i = 0; i < expectationCount; i++) {
                    GitHubCiCorrelationV1.Expectation expectation =
                            new GitHubCiCorrelationV1.Expectation(
                                    readString(input),
                                    readString(input),
                                    readString(input),
                                    readNullableString(input),
                                    readNullableString(input));
                    if (expectations.putIfAbsent(
                            expectation.runId(),
                            expectation) != null) {
                        throw new IllegalStateException(
                                "duplicate GitHub expectation run id");
                    }
                }

                int matchedCount = input.readInt();
                if (matchedCount < 0
                        || matchedCount > MAX_MATCHED_DELIVERIES) {
                    throw new IllegalStateException(
                            "invalid matched-delivery count");
                }
                LinkedHashMap<String, MatchedDelivery>
                        matchedDeliveries = new LinkedHashMap<>();
                for (int i = 0; i < matchedCount; i++) {
                    String key = readString(input);
                    String payloadSha256 = readString(input);
                    String runId = readString(input);
                    String deliveryId = readString(input);
                    String outputPayloadSha256 = readString(input);
                    long workflowRunId = input.readLong();
                    String receiptRef = readString(input);
                    DurableDevLoopV1.CiCompleted event =
                            new DurableDevLoopV1.CiCompleted(
                                    readString(input),
                                    DurableDevLoopV1.CiConclusion.valueOf(
                                            readString(input)),
                                    readString(input));
                    if (!key.equals(deliveryId)
                            || !payloadSha256.equals(
                                    outputPayloadSha256)) {
                        throw new IllegalStateException(
                                "matched-delivery private-state identity "
                                        + "mismatch");
                    }
                    Matched output = new Matched(
                            runId,
                            deliveryId,
                            outputPayloadSha256,
                            workflowRunId,
                            receiptRef,
                            event);
                    if (matchedDeliveries.putIfAbsent(
                            key,
                            new MatchedDelivery(
                                    payloadSha256,
                                    output)) != null) {
                        throw new IllegalStateException(
                                "duplicate matched-delivery id");
                    }
                }

                int identityCount = input.readInt();
                if (identityCount < 0
                        || identityCount > MAX_DELIVERY_IDENTITIES) {
                    throw new IllegalStateException(
                            "invalid delivery-identity count");
                }
                LinkedHashMap<String, String>
                        deliveryIdentities = new LinkedHashMap<>();
                for (int i = 0; i < identityCount; i++) {
                    String key = readString(input);
                    String value = readString(input);
                    if (deliveryIdentities.putIfAbsent(
                            key, value) != null) {
                        throw new IllegalStateException(
                                "duplicate delivery identity");
                    }
                }

                if (input.available() != 0) {
                    throw new IllegalStateException(
                            "trailing bytes in GitHub webhook private state");
                }

                PrivateState state = new PrivateState(
                        decodedSecret,
                        databasePath,
                        expectations,
                        matchedDeliveries,
                        deliveryIdentities);
                decoded = true;
                return state;
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof IllegalStateException stateFailure) {
                    throw stateFailure;
                }
                throw new IllegalStateException(
                        "invalid GitHub webhook private state",
                        failure);
            } finally {
                Arrays.fill(payload, (byte) 0);
                if (!decoded && decodedSecret != null) {
                    Arrays.fill(decodedSecret, (byte) 0);
                }
            }
        }

        private void writeState(PrivateState state) {
            WorkflowPrivateActorStateV1.ScrubbableOutput bytes =
                    new WorkflowPrivateActorStateV1.ScrubbableOutput();
            byte[] encoded = null;
            try {
                try (DataOutputStream output =
                             new DataOutputStream(bytes)) {
                    output.writeInt(1);
                    writeBytes(output, state.secret());
                    writeNullableString(
                            output, state.databasePath());

                    output.writeInt(state.expectations().size());
                    for (var expectation
                            : state.expectations().values()) {
                        writeString(
                                output, expectation.runId());
                        writeString(
                                output,
                                expectation.repositoryFullName());
                        writeString(
                                output, expectation.commitSha());
                        writeNullableString(
                                output, expectation.treeSha());
                        writeNullableString(
                                output, expectation.workflowName());
                    }

                    output.writeInt(
                            state.matchedDeliveries().size());
                    for (var entry
                            : state.matchedDeliveries().entrySet()) {
                        MatchedDelivery value = entry.getValue();
                        Matched matched = value.output();
                        writeString(output, entry.getKey());
                        writeString(
                                output, value.payloadSha256());
                        writeString(output, matched.runId());
                        writeString(
                                output, matched.deliveryId());
                        writeString(
                                output, matched.payloadSha256());
                        output.writeLong(
                                matched.workflowRunId());
                        writeString(
                                output,
                                matched.treeProofReceiptRef());
                        writeString(
                                output,
                                matched.event().commitSha());
                        writeString(
                                output,
                                matched.event()
                                        .conclusion().name());
                        writeString(
                                output,
                                matched.event().reportRef());
                    }

                    output.writeInt(
                            state.deliveryIdentities().size());
                    for (var entry
                            : state.deliveryIdentities().entrySet()) {
                        writeString(output, entry.getKey());
                        writeString(output, entry.getValue());
                    }
                }
                encoded = bytes.snapshotAndWipe();
                WorkflowPrivateActorStateV1.write(
                        stateBlock, encoded);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "failed to encode GitHub webhook private state",
                        failure);
            } finally {
                bytes.wipe();
                if (encoded != null) {
                    Arrays.fill(encoded, (byte) 0);
                }
            }
        }

        private static void requireConfigured(
                PrivateState state) {
            if (!state.configured()) {
                throw new IllegalStateException(
                        "Configure must be the first GitHub webhook actor "
                                + "message");
            }
        }

        private static void writeBytes(
                DataOutputStream output,
                byte[] value) throws IOException {
            Objects.requireNonNull(value, "private-state bytes");
            if (value.length < 16
                    || value.length > MAX_SECRET_BYTES) {
                throw new IllegalStateException(
                        "invalid private-state secret length");
            }
            output.writeInt(value.length);
            output.write(value);
        }

        private static byte[] readBytes(
                DataInputStream input,
                int minimum,
                int maximum,
                String name) throws IOException {
            int length = input.readInt();
            if (length < minimum || length > maximum) {
                throw new IllegalStateException(
                        "invalid private-state " + name + " length");
            }
            byte[] value = input.readNBytes(length);
            if (value.length != length) {
                throw new IllegalStateException(
                        "truncated private-state " + name);
            }
            return value;
        }

        private static void writeString(
                DataOutputStream output,
                String value) throws IOException {
            byte[] bytes = WorkflowPrivateActorStateV1.encodeUtf8(
                    requireText(value, "private-state string"));
            try {
                if (bytes.length > MAX_STATE_STRING_BYTES) {
                    throw new IllegalStateException(
                            "private-state string exceeds "
                                    + MAX_STATE_STRING_BYTES + " bytes");
                }
                output.writeInt(bytes.length);
                output.write(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }

        private static String readString(
                DataInputStream input) throws IOException {
            int length = input.readInt();
            if (length <= 0
                    || length > MAX_STATE_STRING_BYTES) {
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
                    "secret", WorkflowActorWireV1.bytes(value.secret()),
                    "databasePath", value.databasePath());
        }
        if (message instanceof RegisterRun value) {
            return WorkflowActorWireV1.map(
                    "type", "register",
                    "expectation",
                            WorkflowActorWireV1.expectation(
                                    value.expectation()));
        }
        if (message instanceof UnregisterRun value) {
            return WorkflowActorWireV1.map(
                    "type", "unregister",
                    "runId", value.runId());
        }
        if (message instanceof ReceiveCiWebhook value) {
            return WorkflowActorWireV1.map(
                    "type", "receive_ci",
                    "headers",
                            WorkflowActorWireV1.headers(value.headers()),
                    "rawBody",
                            WorkflowActorWireV1.bytes(value.rawBody()),
                    "treeProof",
                            WorkflowActorWireV1.treeProof(
                                    value.treeProof()));
        }
        if (message instanceof Shutdown) {
            return WorkflowActorWireV1.map("type", "shutdown");
        }
        throw new IllegalArgumentException(
                "unsupported GitHub ingress message "
                        + message.getClass().getName());
    }

    private static Message decodeMessage(Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return switch (WorkflowActorWireV1.string(map, "type")) {
            case "configure" -> new Configure(
                    WorkflowActorWireV1.bytes(map.get("secret")),
                    WorkflowActorWireV1.nullableString(
                            map, "databasePath"));
            case "register" -> new RegisterRun(
                    WorkflowActorWireV1.expectation(
                            map.get("expectation")));
            case "unregister" -> new UnregisterRun(
                    WorkflowActorWireV1.string(map, "runId"));
            case "receive_ci" -> new ReceiveCiWebhook(
                    WorkflowActorWireV1.headers(map.get("headers")),
                    WorkflowActorWireV1.bytes(map.get("rawBody")),
                    WorkflowActorWireV1.treeProof(map.get("treeProof")));
            case "shutdown" -> new Shutdown();
            default -> throw new IllegalArgumentException(
                    "unknown GitHub ingress message type "
                            + WorkflowActorWireV1.string(map, "type"));
        };
    }

    private static Map<String, Object> encodeOutput(Output output) {
        if (output instanceof Ready) {
            return WorkflowActorWireV1.map("type", "ready");
        }
        if (output instanceof RunRegistered value) {
            return WorkflowActorWireV1.map(
                    "type", "run_registered",
                    "runId", value.runId());
        }
        if (output instanceof RunUnregistered value) {
            return WorkflowActorWireV1.map(
                    "type", "run_unregistered",
                    "runId", value.runId());
        }
        if (output instanceof Matched value) {
            return WorkflowActorWireV1.map(
                    "type", "matched",
                    "runId", value.runId(),
                    "deliveryId", value.deliveryId(),
                    "payloadSha256", value.payloadSha256(),
                    "workflowRunId", value.workflowRunId(),
                    "treeProofReceiptRef", value.treeProofReceiptRef(),
                    "event", WorkflowActorWireV1.event(value.event()));
        }
        if (output instanceof Unmatched value) {
            return WorkflowActorWireV1.map(
                    "type", "unmatched",
                    "deliveryId", value.deliveryId(),
                    "payloadSha256", value.payloadSha256(),
                    "reason", value.reason());
        }
        if (output instanceof Rejected value) {
            return WorkflowActorWireV1.map(
                    "type", "rejected",
                    "reason", value.reason());
        }
        if (output instanceof Stopping) {
            return WorkflowActorWireV1.map("type", "stopping");
        }
        throw new IllegalArgumentException(
                "unsupported GitHub ingress output "
                        + output.getClass().getName());
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
