package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Non-blocking actor boundary for one class of external workflow activity.
 *
 * <p>The actor does not execute network/model/Git work on an actor carrier.
 * Instead it authorizes a durable claim and emits an {@link Authorized} request
 * to a host/tool adapter. The adapter later reports an attempt-bound outcome.
 * This preserves actor serialization while keeping blocking/foreign work off
 * actor carriers.</p>
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

    private WorkflowActivityActorV1() { }

    public static ActorRuntime.ActorRef<Message> spawn(
            ActorRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return runtime.spawnPrivate(
                WorkflowActivityActorV1::createBehavior);
    }

    private static ActorRuntime.Behavior<Message> createBehavior(
            ActorRuntime.ActorContext<Message> ignored) {
        return new Behavior();
    }

    private static final class Behavior
            implements ActorRuntime.Behavior<Message> {
        private String workerId;
        private WorkflowActivityProtocolV1.Kind kind;
        private Set<WorkflowActivityProtocolV1.Capability> capabilities;
        private final Map<String, WorkflowJournalV1.Claim> inFlight =
                new LinkedHashMap<>();

        @Override
        public void onMessage(
                Message message,
                ActorRuntime.ActorContext<Message> context) {
            Objects.requireNonNull(message, "message");

            if (message instanceof Configure configure) {
                if (workerId != null) {
                    throw new IllegalStateException(
                            "workflow activity actor is already configured");
                }
                workerId = configure.workerId();
                kind = configure.kind();
                capabilities = configure.capabilities();
                emit(context, new Ready(workerId, kind));
                return;
            }

            requireConfigured();

            if (message instanceof Dispatch dispatch) {
                WorkflowJournalV1.Claim claim = dispatch.claim();
                WorkflowJournalV1.Claim existing =
                        inFlight.get(claim.effectKey());
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
                        kind,
                        capabilities);
                inFlight.put(claim.effectKey(), claim);
                emit(context, new Authorized(request));
                return;
            }

            if (message instanceof ReportOutcome report) {
                WorkflowActivityProtocolV1.Outcome outcome =
                        report.outcome();
                String effectKey = effectKey(outcome);
                WorkflowJournalV1.Claim claim =
                        inFlight.get(effectKey);
                if (claim == null) {
                    throw new IllegalStateException(
                            "activity outcome has no in-flight claim: "
                                    + effectKey);
                }

                WorkflowActivityProtocolV1.validateOutcome(
                        claim, outcome);
                inFlight.remove(effectKey);
                emit(context, new OutcomeAccepted(outcome));
                return;
            }

            if (message instanceof CancelAttempt cancel) {
                WorkflowJournalV1.Claim claim =
                        inFlight.get(cancel.effectKey());
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
                inFlight.remove(cancel.effectKey());
                emit(
                        context,
                        new AttemptCancelled(
                                cancel.effectKey(),
                                cancel.claimToken(),
                                cancel.reason()));
                return;
            }

            if (message instanceof Shutdown) {
                if (!inFlight.isEmpty()) {
                    throw new IllegalStateException(
                            "activity actor cannot stop with unresolved "
                                    + "external effects " + inFlight.keySet());
                }
                emit(context, new Stopping(workerId));
                context.runtime().endCurrentActor();
                return;
            }

            throw new IllegalStateException(
                    "unsupported workflow activity actor message "
                            + message.getClass().getName());
        }

        private void requireConfigured() {
            if (workerId == null) {
                throw new IllegalStateException(
                        "Configure must be the first activity actor message");
            }
        }

        private static String effectKey(
                WorkflowActivityProtocolV1.Outcome outcome) {
            return switch (outcome) {
                case WorkflowActivityProtocolV1.Succeeded value ->
                        value.effectKey();
                case WorkflowActivityProtocolV1.KnownUnexecuted value ->
                        value.effectKey();
                case WorkflowActivityProtocolV1.Ambiguous value ->
                        value.effectKey();
            };
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
