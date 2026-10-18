package dev.oreslang.runtime;

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

    private static ActorRuntime.Behavior<Object> createBehavior(
            ActorRuntime.ActorContext<Object> ignored) {
        return new Behavior();
    }

    private static final class Behavior
            implements ActorRuntime.Behavior<Object> {
        private String workerId;
        private WorkflowActivityProtocolV1.Kind kind;
        private Set<WorkflowActivityProtocolV1.Capability> capabilities;
        private final Map<String, WorkflowJournalV1.Claim> inFlight =
                new LinkedHashMap<>();

        @Override
        public void onMessage(
                Object wire,
                ActorRuntime.ActorContext<Object> context) {
            Message message = decodeMessage(wire);

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
                if (claim.workerId() != null
                        && !workerId.equals(claim.workerId())) {
                    throw new SecurityException(
                            "activity worker "
                                    + workerId
                                    + " cannot execute claim leased to "
                                    + claim.workerId());
                }
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
