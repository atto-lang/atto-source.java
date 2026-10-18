package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ephemeral fan-out actor for immutable durable-workflow projections.
 *
 * <p>The underlying workflow projection is authoritative; subscriptions are
 * intentionally not durable. Each view-actor lifetime owns a random epoch and
 * monotonic local revision. A client that observes a new epoch treats the next
 * update as a full snapshot reset, so browser reconnect/restart never changes
 * workflow semantics.</p>
 */
public final class WorkflowViewActorV1 {

    public sealed interface Message permits Configure, Publish,
            Subscribe, Unsubscribe, Shutdown { }

    public record Configure(String runId) implements Message {
        public Configure {
            runId = requireText(runId, "runId");
        }
    }

    public record Publish(
            WorkflowRunProjectionV1 projection) implements Message {
        public Publish {
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record Subscribe(
            String subscriptionId,
            ActorRuntime.ActorRef<Object> subscriber)
            implements Message {
        public Subscribe {
            subscriptionId = requireText(
                    subscriptionId, "subscriptionId");
            subscriber = Objects.requireNonNull(
                    subscriber, "subscriber");
        }
    }

    public record Unsubscribe(String subscriptionId)
            implements Message {
        public Unsubscribe {
            subscriptionId = requireText(
                    subscriptionId, "subscriptionId");
        }
    }

    public record Shutdown() implements Message { }

    public sealed interface Output permits Ready, Published,
            SubscriberAdded, SubscriberRemoved, Stopping { }

    public record Ready(String runId, String epoch) implements Output {
        public Ready {
            runId = requireText(runId, "runId");
            epoch = requireText(epoch, "epoch");
        }
    }

    public record Published(
            String epoch,
            long viewRevision,
            int subscriberCount) implements Output { }

    public record SubscriberAdded(
            String subscriptionId,
            int subscriberCount) implements Output { }

    public record SubscriberRemoved(
            String subscriptionId,
            int subscriberCount) implements Output { }

    public record Stopping(String runId) implements Output { }

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

    private WorkflowViewActorV1() { }

    public static Ref spawn(ActorRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return new Ref(runtime.spawnShared(
                WorkflowViewActorV1::createBehavior));
    }

    public static Output decodeOutput(Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return switch (WorkflowActorWireV1.string(map, "type")) {
            case "ready" -> new Ready(
                    WorkflowActorWireV1.string(map, "runId"),
                    WorkflowActorWireV1.string(map, "epoch"));
            case "published" -> new Published(
                    WorkflowActorWireV1.string(map, "epoch"),
                    WorkflowActorWireV1.longValue(
                            map, "viewRevision"),
                    WorkflowActorWireV1.integer(
                            map, "subscriberCount"));
            case "subscriber_added" -> new SubscriberAdded(
                    WorkflowActorWireV1.string(
                            map, "subscriptionId"),
                    WorkflowActorWireV1.integer(
                            map, "subscriberCount"));
            case "subscriber_removed" -> new SubscriberRemoved(
                    WorkflowActorWireV1.string(
                            map, "subscriptionId"),
                    WorkflowActorWireV1.integer(
                            map, "subscriberCount"));
            case "stopping" -> new Stopping(
                    WorkflowActorWireV1.string(map, "runId"));
            default -> throw new IllegalArgumentException(
                    "unknown workflow view output type "
                            + WorkflowActorWireV1.string(map, "type"));
        };
    }

    /** Decode the data-only message delivered to a UI/subscriber actor. */
    public static UiUpdate decodeUiUpdate(Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        if (!"workflow_projection".equals(
                WorkflowActorWireV1.string(map, "type"))) {
            throw new IllegalArgumentException(
                    "not a workflow projection UI update");
        }
        return new UiUpdate(
                WorkflowActorWireV1.string(map, "runId"),
                WorkflowActorWireV1.string(map, "subscriptionId"),
                WorkflowActorWireV1.string(map, "epoch"),
                WorkflowActorWireV1.longValue(map, "viewRevision"),
                WorkflowActorWireV1.projection(
                        map.get("projection")));
    }

    public record UiUpdate(
            String runId,
            String subscriptionId,
            String epoch,
            long viewRevision,
            WorkflowRunProjectionV1 projection) {
        public UiUpdate {
            runId = requireText(runId, "runId");
            subscriptionId = requireText(
                    subscriptionId, "subscriptionId");
            epoch = requireText(epoch, "epoch");
            if (viewRevision <= 0) {
                throw new IllegalArgumentException(
                        "viewRevision must be positive");
            }
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    private static ActorRuntime.Behavior<Object> createBehavior(
            ActorRuntime.ActorContext<Object> ignored) {
        return new Behavior();
    }

    private static final class Behavior
            implements ActorRuntime.Behavior<Object> {
        private final String epoch = UUID.randomUUID().toString();
        private final LinkedHashMap<
                String, ActorRuntime.ActorRef<Object>> subscribers =
                new LinkedHashMap<>();

        private String runId;
        private long viewRevision;
        private WorkflowRunProjectionV1 latest;

        @Override
        public void onMessage(
                Object wire,
                ActorRuntime.ActorContext<Object> context) {
            Message message = decodeMessage(wire);

            if (message instanceof Configure configure) {
                if (runId != null) {
                    throw new IllegalStateException(
                            "workflow view actor is already configured");
                }
                runId = configure.runId();
                emit(context, new Ready(runId, epoch));
                return;
            }

            requireConfigured();

            if (message instanceof Subscribe subscribe) {
                var prior = subscribers.putIfAbsent(
                        subscribe.subscriptionId(),
                        subscribe.subscriber());
                if (prior != null
                        && prior != subscribe.subscriber()) {
                    throw new IllegalStateException(
                            "subscription id already belongs to another actor");
                }
                emit(
                        context,
                        new SubscriberAdded(
                                subscribe.subscriptionId(),
                                subscribers.size()));
                if (latest != null
                        && !sendUpdate(
                                subscribe.subscriptionId(),
                                subscribe.subscriber(),
                                latest,
                                viewRevision)) {
                    subscribers.remove(subscribe.subscriptionId());
                    emit(
                            context,
                            new SubscriberRemoved(
                                    subscribe.subscriptionId(),
                                    subscribers.size()));
                }
                return;
            }

            if (message instanceof Unsubscribe unsubscribe) {
                subscribers.remove(unsubscribe.subscriptionId());
                emit(
                        context,
                        new SubscriberRemoved(
                                unsubscribe.subscriptionId(),
                                subscribers.size()));
                return;
            }

            if (message instanceof Publish publish) {
                if (!runId.equals(publish.projection().runId())) {
                    throw new IllegalArgumentException(
                            "view actor cannot mix workflow run ids");
                }
                long nextRevision =
                        Math.addExact(viewRevision, 1L);
                latest = publish.projection();
                viewRevision = nextRevision;
                var iterator = subscribers.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (!sendUpdate(
                            entry.getKey(),
                            entry.getValue(),
                            latest,
                            viewRevision)) {
                        iterator.remove();
                    }
                }
                emit(
                        context,
                        new Published(
                                epoch,
                                viewRevision,
                                subscribers.size()));
                return;
            }

            if (message instanceof Shutdown) {
                emit(context, new Stopping(runId));
                subscribers.clear();
                latest = null;
                context.runtime().endCurrentActor();
                return;
            }

            throw new IllegalStateException(
                    "unsupported workflow view actor message "
                            + message.getClass().getName());
        }

        private boolean sendUpdate(
                String subscriptionId,
                ActorRuntime.ActorRef<Object> subscriber,
                WorkflowRunProjectionV1 projection,
                long revision) {
            try {
                subscriber.send(WorkflowActorWireV1.map(
                        "type", "workflow_projection",
                        "runId", runId,
                        "subscriptionId", subscriptionId,
                        "epoch", epoch,
                        "viewRevision", revision,
                        "projection",
                                WorkflowActorWireV1.projection(
                                        projection)));
                return true;
            } catch (RuntimeException unavailable) {
                // UI subscribers are ephemeral projections, never workflow
                // authority. A stopped/sealed/full subscriber is removed
                // instead of taking down fan-out for healthy subscribers.
                return false;
            }
        }

        private void requireConfigured() {
            if (runId == null) {
                throw new IllegalStateException(
                        "Configure must be the first view actor message");
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
                    "runId", value.runId());
        }
        if (message instanceof Publish value) {
            return WorkflowActorWireV1.map(
                    "type", "publish",
                    "projection",
                            WorkflowActorWireV1.projection(
                                    value.projection()));
        }
        if (message instanceof Subscribe value) {
            return WorkflowActorWireV1.map(
                    "type", "subscribe",
                    "subscriptionId", value.subscriptionId(),
                    "subscriber", value.subscriber());
        }
        if (message instanceof Unsubscribe value) {
            return WorkflowActorWireV1.map(
                    "type", "unsubscribe",
                    "subscriptionId", value.subscriptionId());
        }
        if (message instanceof Shutdown) {
            return WorkflowActorWireV1.map("type", "shutdown");
        }
        throw new IllegalArgumentException(
                "unsupported workflow view message "
                        + message.getClass().getName());
    }

    private static Message decodeMessage(Object wire) {
        Map<String, Object> map =
                WorkflowActorWireV1.objectMap(wire);
        return switch (WorkflowActorWireV1.string(map, "type")) {
            case "configure" -> new Configure(
                    WorkflowActorWireV1.string(map, "runId"));
            case "publish" -> new Publish(
                    WorkflowActorWireV1.projection(
                            map.get("projection")));
            case "subscribe" -> {
                Object raw = map.get("subscriber");
                if (!(raw instanceof ActorRuntime.ActorRef<?> ref)) {
                    throw new IllegalArgumentException(
                            "subscriber must be an ActorRef capability");
                }
                @SuppressWarnings("unchecked")
                ActorRuntime.ActorRef<Object> subscriber =
                        (ActorRuntime.ActorRef<Object>) ref;
                yield new Subscribe(
                        WorkflowActorWireV1.string(
                                map, "subscriptionId"),
                        subscriber);
            }
            case "unsubscribe" -> new Unsubscribe(
                    WorkflowActorWireV1.string(
                            map, "subscriptionId"));
            case "shutdown" -> new Shutdown();
            default -> throw new IllegalArgumentException(
                    "unknown workflow view message type "
                            + WorkflowActorWireV1.string(map, "type"));
        };
    }

    private static Map<String, Object> encodeOutput(Output output) {
        if (output instanceof Ready value) {
            return WorkflowActorWireV1.map(
                    "type", "ready",
                    "runId", value.runId(),
                    "epoch", value.epoch());
        }
        if (output instanceof Published value) {
            return WorkflowActorWireV1.map(
                    "type", "published",
                    "epoch", value.epoch(),
                    "viewRevision", value.viewRevision(),
                    "subscriberCount", value.subscriberCount());
        }
        if (output instanceof SubscriberAdded value) {
            return WorkflowActorWireV1.map(
                    "type", "subscriber_added",
                    "subscriptionId", value.subscriptionId(),
                    "subscriberCount", value.subscriberCount());
        }
        if (output instanceof SubscriberRemoved value) {
            return WorkflowActorWireV1.map(
                    "type", "subscriber_removed",
                    "subscriptionId", value.subscriptionId(),
                    "subscriberCount", value.subscriberCount());
        }
        if (output instanceof Stopping value) {
            return WorkflowActorWireV1.map(
                    "type", "stopping",
                    "runId", value.runId());
        }
        throw new IllegalArgumentException(
                "unsupported workflow view output "
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
