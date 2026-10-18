package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared GitHub webhook ingress actor for durable workflow runs.
 *
 * <p>The actor authenticates the exact raw request body before considering
 * normalized CI facts. It does not directly mutate a workflow actor: matched
 * deliveries are emitted as immutable outputs so a router/supervisor can send
 * the GitHub delivery id and typed CI event to the owning durable run.</p>
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
            GitHubCiCorrelationV1.Observed observed)
            implements Message {
        public ReceiveCiWebhook {
            headers = Objects.requireNonNull(headers, "headers");
            Objects.requireNonNull(rawBody, "rawBody");
            rawBody = rawBody.clone();
            observed = Objects.requireNonNull(observed, "observed");
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
            DurableDevLoopV1.CiCompleted event)
            implements Output {
        public Matched {
            runId = requireText(runId, "runId");
            deliveryId = requireText(deliveryId, "deliveryId");
            payloadSha256 = requireText(
                    payloadSha256, "payloadSha256");
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

    private GitHubWebhookActorV1() { }

    public static ActorRuntime.ActorRef<Message> spawn(
            ActorRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return runtime.spawnShared(
                GitHubWebhookActorV1::createBehavior);
    }

    private static ActorRuntime.Behavior<Message> createBehavior(
            ActorRuntime.ActorContext<Message> ignored) {
        return new Behavior();
    }

    private static final class Behavior
            implements ActorRuntime.Behavior<Message> {
        private byte[] secret;
        private final Map<String, GitHubCiCorrelationV1.Expectation>
                expectations = new LinkedHashMap<>();

        @Override
        public void onMessage(
                Message message,
                ActorRuntime.ActorContext<Message> context) {
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

                int matched = 0;
                String lastReason = "no registered run matched CI identity";
                for (var expectation : expectations.values()) {
                    var result = GitHubCiCorrelationV1.correlate(
                            expectation,
                            receive.observed());
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
                                    receive.observed().workflowRunId(),
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
                ActorRuntime.ActorContext<Message> context,
                Output output) {
            context.runtime().emitCurrentActorOutput(output);
        }
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
