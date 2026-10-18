package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared authenticated GitHub ingress actor.
 *
 * <p>Only maps/lists/scalars cross ActorRuntime. Raw webhook bytes and the
 * secret are base64 encoded by the host facade before transport, decoded only
 * on the serialized actor lane, and never exposed through actor outputs.</p>
 */
public final class GitHubWebhookActorV1 {

    public sealed interface Message permits Configure, RegisterRun,
            UnregisterRun, ReceiveCiWebhook, Shutdown { }

    public record Configure(byte[] secret) implements Message {
        public Configure {
            Objects.requireNonNull(secret, "secret");
            if (secret.length < 16) {
                throw new IllegalArgumentException(
                        "webhook secret must contain at least 16 bytes");
            }
            secret = secret.clone();
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
            RunUnregistered, Matched, Unmatched, Stopping { }

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
        return new Ref(runtime.spawnShared(
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
            case "stopping" -> new Stopping();
            default -> throw new IllegalArgumentException(
                    "unknown GitHub ingress output type "
                            + WorkflowActorWireV1.string(map, "type"));
        };
    }

    private static ActorRuntime.Behavior<Object> createBehavior(
            ActorRuntime.ActorContext<Object> ignored) {
        return new Behavior();
    }

    private static final class Behavior
            implements ActorRuntime.Behavior<Object> {
        private byte[] secret;
        private final Map<String, GitHubCiCorrelationV1.Expectation>
                expectations = new LinkedHashMap<>();

        @Override
        public void onMessage(
                Object wire,
                ActorRuntime.ActorContext<Object> context) {
            Message message = decodeMessage(wire);

            if (message instanceof Configure configure) {
                if (secret != null) {
                    throw new IllegalStateException(
                            "GitHub webhook actor is already configured");
                }
                secret = configure.secret();
                emit(context, new Ready());
                return;
            }

            requireConfigured();

            if (message instanceof RegisterRun register) {
                var expectation = register.expectation();
                var prior = expectations.putIfAbsent(
                        expectation.runId(), expectation);
                if (prior != null && !prior.equals(expectation)) {
                    throw new IllegalStateException(
                            "run correlation already registered differently: "
                                    + expectation.runId());
                }
                emit(
                        context,
                        new RunRegistered(expectation.runId()));
                return;
            }

            if (message instanceof UnregisterRun unregister) {
                expectations.remove(unregister.runId());
                emit(
                        context,
                        new RunUnregistered(unregister.runId()));
                return;
            }

            if (message instanceof ReceiveCiWebhook receive) {
                var verified = GitHubWebhookSecurityV1.verify(
                        secret,
                        receive.rawBody(),
                        receive.headers());
                if (!"workflow_run".equals(verified.eventName())) {
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
                GitHubCiCorrelationV1.Observed observed =
                        parsed.observed(
                                receive.treeProof().treeSha());

                int matched = 0;
                String lastReason =
                        "no registered run matched CI identity";
                for (var expectation : expectations.values()) {
                    var result = GitHubCiCorrelationV1.correlate(
                            expectation,
                            observed);
                    if (!result.matched()) {
                        lastReason = result.reason();
                        continue;
                    }
                    matched++;
                    emit(
                            context,
                            new Matched(
                                    expectation.runId(),
                                    verified.deliveryId(),
                                    verified.payloadSha256(),
                                    observed.workflowRunId(),
                                    receive.treeProof().receiptRef(),
                                    result.event()));
                }

                if (matched == 0) {
                    emit(
                            context,
                            new Unmatched(
                                    verified.deliveryId(),
                                    verified.payloadSha256(),
                                    lastReason));
                }
                return;
            }

            if (message instanceof Shutdown) {
                emit(context, new Stopping());
                Arrays.fill(secret, (byte) 0);
                secret = null;
                expectations.clear();
                context.runtime().endCurrentActor();
                return;
            }

            throw new IllegalStateException(
                    "unsupported GitHub webhook actor message "
                            + message.getClass().getName());
        }

        private void requireConfigured() {
            if (secret == null) {
                throw new IllegalStateException(
                        "Configure must be the first GitHub webhook actor message");
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
                    "secret", WorkflowActorWireV1.bytes(value.secret()));
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
                    WorkflowActorWireV1.bytes(map.get("secret")));
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
