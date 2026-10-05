package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime-owned, group-scoped event bus for tightly coupled actor workloads.
 *
 * <p>The bus is deliberately not an actor. Publishing never routes through a
 * central mailbox or executes subscriber guest code inline. Each subscription
 * owns a bounded Oreslang channel, so reliable delivery can apply scheduler-
 * friendly backpressure while latest/lossy topics remain non-blocking.</p>
 *
 * <p>Event payloads are frozen once by {@link ActorRuntime} and the same
 * immutable value is fanned out to subscribers. Mutable shared state is never
 * exposed through this API.</p>
 */
public final class ActorEventBus implements AutoCloseable {
    public enum DeliveryPolicy {
        /** Every active subscriber receives every event; publishers may await backpressure. */
        RELIABLE,
        /** Each subscriber retains only the newest unread event. */
        LATEST,
        /** New events are dropped for subscribers whose bounded queue is full. */
        LOSSY
    }

    public enum DoubleReductionKind {
        MIN,
        MAX
    }

    public record Event<T>(
            String topic,
            long sequence,
            ActorRuntime.ActorId publisher,
            long publishedNanos,
            T value) {
        public Event {
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(value, "Oreslang events cannot carry null; use Option<T>");
        }

        public boolean systemPublished() {
            return publisher == null;
        }
    }

    /**
     * Publication metadata is available immediately. completion() resolves when
     * all RELIABLE subscriber writes have been admitted. LATEST/LOSSY complete
     * immediately.
     */
    public record PublishReceipt(
            int subscribers,
            int accepted,
            int dropped,
            int coalesced,
            OresFuture<List<Void>> completion) {
        public PublishReceipt {
            if (subscribers < 0 || accepted < 0 || dropped < 0 || coalesced < 0) {
                throw new IllegalArgumentException("publication counts cannot be negative");
            }
            Objects.requireNonNull(completion, "completion");
        }
    }

    public record DoubleReductionResult(
            double value,
            boolean changed,
            PublishReceipt publication) {
        public DoubleReductionResult {
            if (Double.isNaN(value)) throw new IllegalArgumentException("reduction value cannot be NaN");
            Objects.requireNonNull(publication, "publication");
        }
    }

    public record TopicInfo(
            String name,
            DeliveryPolicy policy,
            int capacity,
            int subscribers,
            long published,
            long dropped,
            long coalesced) { }

    private static final int MAX_TOPICS = 1_024;

    private final ActorRuntime runtime;
    private final ActorRuntime.ActorGroup group;
    private final Map<String, TopicState> topics = new ConcurrentHashMap<>();
    private final Map<String, DoubleReductionState> doubleReductions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    ActorEventBus(ActorRuntime runtime, ActorRuntime.ActorGroup group) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.group = Objects.requireNonNull(group, "group");
    }

    public boolean closed() {
        return closed.get();
    }

    public int topicCount() {
        return topics.size();
    }

    public List<TopicInfo> topics() {
        return topics.values().stream()
                .map(TopicState::info)
                .sorted((left, right) -> left.name().compareTo(right.name()))
                .toList();
    }

    public void defineTopic(String name, DeliveryPolicy policy, int capacity) {
        group.requireManager("define ActorGroup event topics");
        requireOpen();
        String topicName = normalizeTopicName(name);
        Objects.requireNonNull(policy, "policy");
        validateCapacity(policy, capacity);

        if (topics.size() >= MAX_TOPICS && !topics.containsKey(topicName)) {
            throw new IllegalStateException("ActorGroup event topic limit exceeded: " + MAX_TOPICS);
        }

        TopicState created = new TopicState(topicName, policy, capacity);
        TopicState existing = topics.putIfAbsent(topicName, created);
        if (existing != null) {
            if (existing.policy != policy || existing.capacity != capacity) {
                throw new IllegalStateException(
                        "event topic already defined with different policy/capacity: " + topicName);
            }
        }
    }

    /**
     * Defines a monotonic runtime-owned double reduction and a coalescing LATEST
     * notification topic with the same name.
     */
    public void defineDoubleReduction(
            String name,
            DoubleReductionKind kind,
            double initialValue) {
        group.requireManager("define ActorGroup reductions");
        Objects.requireNonNull(kind, "kind");
        if (Double.isNaN(initialValue)) {
            throw new IllegalArgumentException("double reduction initial value cannot be NaN");
        }

        String topicName = normalizeTopicName(name);
        defineTopic(topicName, DeliveryPolicy.LATEST, 1);
        DoubleReductionState created = new DoubleReductionState(topicName, kind, initialValue);
        DoubleReductionState existing = doubleReductions.putIfAbsent(topicName, created);
        if (existing != null
                && (existing.kind != kind
                || Double.doubleToRawLongBits(existing.current())
                != Double.doubleToRawLongBits(initialValue))) {
            throw new IllegalStateException(
                    "double reduction already defined with different semantics: " + topicName);
        }
    }

    public <T> Subscription<T> subscribe(String name) {
        requireOpen();
        ActorRuntime.ActorId subscriber = group.requireCurrentMember("subscribe to ActorGroup events");
        TopicState topic = requireTopic(name);

        @SuppressWarnings("unchecked")
        Subscription<T> existing = (Subscription<T>) topic.subscriptions.get(subscriber);
        if (existing != null && !existing.closed()) return existing;

        int actorLimit = Math.max(1, ActorRuntime.currentActorPolicy().maxMailboxMessages());
        int capacity = Math.min(topic.capacity, actorLimit);
        Subscription<T> created = new Subscription<>(topic, subscriber, capacity);
        Subscription<?> raced = topic.subscriptions.putIfAbsent(subscriber, created);
        if (raced != null) {
            created.closeChannelOnly();
            @SuppressWarnings("unchecked")
            Subscription<T> typed = (Subscription<T>) raced;
            return typed;
        }

        if (!group.isMember(subscriber) || closed.get()) {
            topic.subscriptions.remove(subscriber, created);
            created.closeChannelOnly();
            throw new IllegalStateException("ActorGroup membership ended during subscription");
        }
        return created;
    }

    public PublishReceipt publish(String name, Object value) {
        requireOpen();
        ActorRuntime.ActorId publisher = group.currentMemberOrSystem("publish ActorGroup events");
        return publishFrozen(requireTopic(name), runtime.freezeActorGroupEventPayload(value), publisher);
    }

    /**
     * Host/supervisor publication path. Actor code cannot impersonate the
     * runtime/system publisher.
     */
    public PublishReceipt publishSystem(String name, Object value) {
        group.requireSupervisor("publish system ActorGroup events");
        requireOpen();
        return publishFrozen(requireTopic(name), runtime.freezeActorGroupEventPayload(value), null);
    }

    public DoubleReductionResult reduceDouble(String name, double candidate) {
        requireOpen();
        if (Double.isNaN(candidate)) {
            throw new IllegalArgumentException("double reduction candidate cannot be NaN");
        }
        ActorRuntime.ActorId publisher = group.currentMemberOrSystem("update ActorGroup reductions");
        DoubleReductionState reduction = requireDoubleReduction(name);
        double current = reduction.reduce(candidate);
        boolean changed = Double.doubleToRawLongBits(current)
                == Double.doubleToRawLongBits(candidate);

        // A candidate equal to the existing value is not an improvement.
        if (changed && reduction.wasLastMutation(candidate)) {
            PublishReceipt receipt = publishFrozen(
                    requireTopic(name),
                    runtime.freezeActorGroupEventPayload(current),
                    publisher);
            return new DoubleReductionResult(current, true, receipt);
        }

        TopicState topic = requireTopic(name);
        return new DoubleReductionResult(
                current,
                false,
                new PublishReceipt(
                        topic.subscriptions.size(),
                        0,
                        0,
                        0,
                        OresFuture.completed(List.of())));
    }

    public double readDoubleReduction(String name) {
        requireOpen();
        group.currentMemberOrSystem("read ActorGroup reductions");
        return requireDoubleReduction(name).current();
    }

    void removeActor(ActorRuntime.ActorId actorId) {
        for (TopicState topic : topics.values()) {
            Subscription<?> subscription = topic.subscriptions.remove(actorId);
            if (subscription != null) subscription.closeChannelOnly();
        }
    }

    @Override
    public void close() {
        group.requireManager("close an ActorGroup event bus");
        closeFromGroup();
    }

    void closeFromGroup() {
        if (!closed.compareAndSet(false, true)) return;
        for (TopicState topic : topics.values()) {
            for (Subscription<?> subscription : List.copyOf(topic.subscriptions.values())) {
                subscription.closeChannelOnly();
            }
            topic.subscriptions.clear();
        }
        topics.clear();
        doubleReductions.clear();
    }

    private PublishReceipt publishFrozen(
            TopicState topic,
            Object frozenValue,
            ActorRuntime.ActorId publisher) {
        long sequence = topic.sequence.incrementAndGet();
        topic.published.incrementAndGet();
        @SuppressWarnings("unchecked")
        Event<Object> event = new Event<>(
                topic.name,
                sequence,
                publisher,
                System.nanoTime(),
                frozenValue);

        List<Subscription<?>> subscriptions = List.copyOf(topic.subscriptions.values());
        if (subscriptions.isEmpty()) {
            return new PublishReceipt(0, 0, 0, 0, OresFuture.completed(List.of()));
        }

        int accepted = 0;
        int dropped = 0;
        int coalesced = 0;
        List<OresFuture<Void>> reliableWrites =
                topic.policy == DeliveryPolicy.RELIABLE
                        ? new ArrayList<>(subscriptions.size())
                        : List.of();

        for (Subscription<?> raw : subscriptions) {
            if (raw.closed() || !group.isMember(raw.subscriber)) {
                topic.subscriptions.remove(raw.subscriber, raw);
                raw.closeChannelOnly();
                continue;
            }

            @SuppressWarnings("unchecked")
            Subscription<Object> subscription = (Subscription<Object>) raw;
            switch (topic.policy) {
                case RELIABLE -> {
                    OresFuture<Void> write = subscription.writeReliable(event);
                    reliableWrites.add(write);
                    accepted++;
                }
                case LATEST -> {
                    int replaced = subscription.writeLatest(event);
                    accepted++;
                    coalesced += replaced;
                }
                case LOSSY -> {
                    if (subscription.writeLossy(event)) accepted++;
                    else dropped++;
                }
            }
        }

        topic.dropped.addAndGet(dropped);
        topic.coalesced.addAndGet(coalesced);
        OresFuture<List<Void>> completion =
                topic.policy == DeliveryPolicy.RELIABLE
                        ? OresFutures.<Void>all(reliableWrites)
                        : OresFuture.completed(List.of());
        return new PublishReceipt(subscriptions.size(), accepted, dropped, coalesced, completion);
    }

    private TopicState requireTopic(String name) {
        String topicName = normalizeTopicName(name);
        TopicState topic = topics.get(topicName);
        if (topic == null) throw new IllegalArgumentException("unknown ActorGroup event topic: " + topicName);
        return topic;
    }

    private DoubleReductionState requireDoubleReduction(String name) {
        String topicName = normalizeTopicName(name);
        DoubleReductionState reduction = doubleReductions.get(topicName);
        if (reduction == null) {
            throw new IllegalArgumentException("unknown ActorGroup double reduction: " + topicName);
        }
        return reduction;
    }

    private void requireOpen() {
        if (closed.get() || group.closed()) {
            throw new IllegalStateException("ActorGroup event bus is closed");
        }
    }

    private static String normalizeTopicName(String name) {
        Objects.requireNonNull(name, "name");
        String normalized = name.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("event topic name cannot be empty");
        if (normalized.length() > 256) throw new IllegalArgumentException("event topic name exceeds 256 characters");
        return normalized;
    }

    private static void validateCapacity(DeliveryPolicy policy, int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("event topic capacity must be > 0");
        if (policy == DeliveryPolicy.LATEST && capacity != 1) {
            throw new IllegalArgumentException("LATEST event topics require capacity=1");
        }
    }

    private final class TopicState {
        private final String name;
        private final DeliveryPolicy policy;
        private final int capacity;
        private final Map<ActorRuntime.ActorId, Subscription<?>> subscriptions = new ConcurrentHashMap<>();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong published = new AtomicLong();
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicLong coalesced = new AtomicLong();

        private TopicState(String name, DeliveryPolicy policy, int capacity) {
            this.name = name;
            this.policy = policy;
            this.capacity = capacity;
        }

        private TopicInfo info() {
            return new TopicInfo(
                    name,
                    policy,
                    capacity,
                    subscriptions.size(),
                    published.get(),
                    dropped.get(),
                    coalesced.get());
        }
    }

    private static final class DoubleReductionState {
        private final String name;
        private final DoubleReductionKind kind;
        private final AtomicLong bits;
        private final AtomicLong mutationSequence = new AtomicLong();
        private final ThreadLocal<Long> callerMutation = new ThreadLocal<>();

        private DoubleReductionState(
                String name,
                DoubleReductionKind kind,
                double initialValue) {
            this.name = name;
            this.kind = kind;
            this.bits = new AtomicLong(Double.doubleToRawLongBits(initialValue));
        }

        private double current() {
            return Double.longBitsToDouble(bits.get());
        }

        private double reduce(double candidate) {
            callerMutation.remove();
            while (true) {
                long observedBits = bits.get();
                double observed = Double.longBitsToDouble(observedBits);
                boolean improves = switch (kind) {
                    case MIN -> Double.compare(candidate, observed) < 0;
                    case MAX -> Double.compare(candidate, observed) > 0;
                };
                if (!improves) return observed;

                long candidateBits = Double.doubleToRawLongBits(candidate);
                if (bits.compareAndSet(observedBits, candidateBits)) {
                    long sequence = mutationSequence.incrementAndGet();
                    callerMutation.set(sequence);
                    return candidate;
                }
            }
        }

        private boolean wasLastMutation(double candidate) {
            Long sequence = callerMutation.get();
            callerMutation.remove();
            return sequence != null
                    && Double.doubleToRawLongBits(current())
                    == Double.doubleToRawLongBits(candidate);
        }
    }

    public final class Subscription<T> implements AutoCloseable {
        private final TopicState topic;
        private final ActorRuntime.ActorId subscriber;
        private final ChannelRuntime.Channel<Event<T>> channel;
        private final Object latestLock = new Object();
        private final AtomicBoolean subscriptionClosed = new AtomicBoolean();

        private Subscription(
                TopicState topic,
                ActorRuntime.ActorId subscriber,
                int capacity) {
            this.topic = topic;
            this.subscriber = subscriber;
            this.channel = new ChannelRuntime.Channel<>(capacity);
        }

        public String topic() {
            return topic.name;
        }

        public ActorRuntime.ActorId subscriber() {
            return subscriber;
        }

        public DeliveryPolicy policy() {
            return topic.policy;
        }

        public int capacity() {
            return channel.capacity();
        }

        public int pending() {
            return channel.size();
        }

        public boolean closed() {
            return subscriptionClosed.get();
        }

        public Optional<Event<T>> tryRead() {
            requireReadable();
            return channel.tryRead();
        }

        public OresFuture<Event<T>> readAsync() {
            requireReadable();
            return channel.readAsync();
        }

        private OresFuture<Void> writeReliable(Event<T> event) {
            if (subscriptionClosed.get()) {
                return OresFuture.failed(new IllegalStateException("event subscription is closed"));
            }
            return channel.writeAsync(event);
        }

        private boolean writeLossy(Event<T> event) {
            if (subscriptionClosed.get()) return false;
            try {
                return channel.tryWrite(event);
            } catch (ChannelRuntime.ChannelClosedException closedChannel) {
                return false;
            }
        }

        /**
         * Returns 1 when an older unread event was coalesced, otherwise 0.
         */
        private int writeLatest(Event<T> event) {
            if (subscriptionClosed.get()) return 0;
            synchronized (latestLock) {
                try {
                    if (channel.tryWrite(event)) return 0;
                    Optional<Event<T>> previous = channel.tryRead();
                    if (previous.isEmpty()) {
                        return channel.tryWrite(event) ? 0 : 0;
                    }
                    if (!channel.tryWrite(event)) {
                        throw new IllegalStateException(
                                "LATEST event subscription failed to admit replacement value");
                    }
                    return 1;
                } catch (ChannelRuntime.ChannelClosedException closedChannel) {
                    return 0;
                }
            }
        }

        private void requireReadable() {
            if (subscriptionClosed.get()) throw new IllegalStateException("event subscription is closed");
            ActorRuntime.ActorId current = group.requireCurrentMember("read ActorGroup events");
            if (!current.equals(subscriber)) {
                throw new SecurityException("an actor may only read its own ActorGroup event subscription");
            }
        }

        @Override
        public void close() {
            ActorRuntime.ActorId current = group.requireCurrentMember("close ActorGroup event subscriptions");
            if (!current.equals(subscriber)) {
                throw new SecurityException("an actor may only close its own ActorGroup event subscription");
            }
            if (topic.subscriptions.remove(subscriber, this)) closeChannelOnly();
        }

        private void closeChannelOnly() {
            if (!subscriptionClosed.compareAndSet(false, true)) return;
            channel.close();
            while (channel.tryRead().isPresent()) {
                // Drain buffered immutable event references so group teardown
                // does not retain payloads after the subscription is gone.
            }
        }
    }
}
