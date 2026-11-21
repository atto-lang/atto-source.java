package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    // Independent of topic capacity: a single actor cannot allocate one
    // channel per topic across the group's 1024-topic definition budget.
    private static final int MAX_SUBSCRIPTIONS_PER_ACTOR_PER_GROUP = 64;
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

    public static final class EventBackpressureException extends IllegalStateException {
        private final String topic;
        private final ActorRuntime.ActorId subscriber;

        private EventBackpressureException(
                String topic,
                ActorRuntime.ActorId subscriber,
                int limit) {
            super("reliable ActorGroup event backlog is full for topic "
                    + topic + ", subscriber=" + subscriber + ", limit=" + limit);
            this.topic = topic;
            this.subscriber = subscriber;
        }

        public String topic() { return topic; }
        public ActorRuntime.ActorId subscriber() { return subscriber; }
    }

    public static final class Event<T> {
        private final String topic;
        private final long sequence;
        private final ActorRuntime.ActorId publisher;
        private final long publishedNanos;
        private final T value;
        private final ActorRuntime.ActorGroupEventReservation reservation;
        private final AtomicInteger retentionRefs = new AtomicInteger(1);

        private Event(
                String topic,
                long sequence,
                ActorRuntime.ActorId publisher,
                long publishedNanos,
                T value,
                ActorRuntime.ActorGroupEventReservation reservation) {
            this.topic = Objects.requireNonNull(topic, "topic");
            this.sequence = sequence;
            this.publisher = publisher;
            this.publishedNanos = publishedNanos;
            this.value = Objects.requireNonNull(
                    value, "Oreslang events cannot carry null; use Option<T>");
            this.reservation = Objects.requireNonNull(reservation, "reservation");
        }

        public String topic() { return topic; }
        public long sequence() { return sequence; }
        public ActorRuntime.ActorId publisher() { return publisher; }
        public long publishedNanos() { return publishedNanos; }
        public T value() { return value; }

        public boolean systemPublished() {
            return publisher == null;
        }

        private void retainDelivery() {
            while (true) {
                int current = retentionRefs.get();
                if (current <= 0) {
                    throw new IllegalStateException("cannot retain a released ActorGroup event");
                }
                if (current == Integer.MAX_VALUE) {
                    throw new IllegalStateException("ActorGroup event retention overflow");
                }
                if (retentionRefs.compareAndSet(current, current + 1)) return;
            }
        }

        private void releaseDelivery() {
            int remaining = retentionRefs.decrementAndGet();
            if (remaining < 0) {
                retentionRefs.incrementAndGet();
                throw new IllegalStateException("ActorGroup event retention underflow");
            }
            if (remaining == 0) reservation.close();
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
            OresFuture<List<Void>> completion,
            long sequence) {
        // Preserve the existing host constructor for non-publication paths,
        // including unchanged reduction results and third-party callers.
        public PublishReceipt(
                int subscribers, int accepted, int dropped, int coalesced,
                OresFuture<List<Void>> completion) {
            this(subscribers, accepted, dropped, coalesced, completion, 0L);
        }

        public PublishReceipt {
            if (subscribers < 0 || accepted < 0 || dropped < 0 || coalesced < 0) {
                throw new IllegalArgumentException("publication counts cannot be negative");
            }
            Objects.requireNonNull(completion, "completion");
        }

        /** Full unsigned 64-bit sequence as decimal text for guest/wire receipts. */
        public String sequenceUnsignedString() {
            return Long.toUnsignedString(sequence);
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

    /** Typed topic facade: no lifecycle authority is stored on this handle. */
    public final class Topic<T> {
        private final String name;
        private final DeliveryPolicy policy;
        private final int capacity;
        private Topic(String name, DeliveryPolicy policy, int capacity) {
            this.name = Objects.requireNonNull(name, "name");
            this.policy = Objects.requireNonNull(policy, "policy");
            this.capacity = capacity;
        }
        public String name() { return name; }
        public DeliveryPolicy policy() { return policy; }
        public int capacity() { return capacity; }
        public Subscription<T> subscribe() { return ActorEventBus.this.subscribe(name); }
        public PublishReceipt publish(T value) { return ActorEventBus.this.publish(name, value); }
        /** Host-only bounded RELIABLE admission, not portable guest try_publish. */
        public Optional<PublishReceipt> tryPublishReliable(T value) {
            return ActorEventBus.this.tryPublishReliable(name, value);
        }
    }

    private final ActorRuntime runtime;
    private final ActorRuntime.ActorGroup group;
    private final Map<String, TopicState> topics = new ConcurrentHashMap<>();
    private final Map<String, DoubleReductionState> doubleReductions = new ConcurrentHashMap<>();
    private final Object definitionLock = new Object();
    // Lock owns the cross-topic per-actor subscription budget.
    private final Object subscriptionCreditLock = new Object();
    private final Map<ActorRuntime.ActorId, Integer> subscriptionCredits =
            new java.util.HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    // A formerly single-topic subscription may now fan out into several
    // publisher-scoped leases. Bound this new multiplicative allocation path.
    private static final int MAX_SUBSCRIPTIONS_PER_OBSERVER_TOPIC = 64;

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

    /**
     * Define (idempotently) and return a typed topic facade. Topic identity is
     * still group-local and name-based at the runtime boundary; T is the
     * source/compiler type carried by the facade.
     */
    public <T> Topic<T> topic(String name, DeliveryPolicy policy, int capacity) {
        defineTopic(name, policy, capacity);
        return new Topic<>(normalizeTopicName(name), policy, capacity);
    }

    /** Return a typed facade for an already-defined topic. */
    public <T> Topic<T> topic(String name) {
        requireOpen();
        TopicState state = requireTopic(name);
        return new Topic<>(state.name, state.policy, state.capacity);
    }

    public void defineTopic(String name, DeliveryPolicy policy, int capacity) {
        group.requireManager("define ActorGroup event topics");
        String topicName = normalizeTopicName(name);
        Objects.requireNonNull(policy, "policy");
        validateCapacity(policy, capacity);

        synchronized (definitionLock) {
            requireOpen();
            int topicLimit = group.eventTopicLimit();
            if (topics.size() >= topicLimit && !topics.containsKey(topicName)) {
                throw new IllegalStateException(
                        "ActorGroup event topic limit exceeded: " + topicLimit);
            }

            TopicState created = new TopicState(topicName, policy, capacity);
            TopicState existing = topics.putIfAbsent(topicName, created);
            if (existing != null
                    && (existing.policy != policy || existing.capacity != capacity)) {
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
        synchronized (definitionLock) {
            requireOpen();
            DoubleReductionState existing = doubleReductions.get(topicName);
            if (existing != null) {
                if (!existing.sameDefinition(kind, initialValue)) {
                    throw new IllegalStateException(
                            "double reduction already defined with different semantics: " + topicName);
                }
                return;
            }
            if (topics.containsKey(topicName)) {
                throw new IllegalStateException(
                        "ordinary event topic cannot become a reduction: " + topicName);
            }
            int topicLimit = group.eventTopicLimit();
            if (topics.size() >= topicLimit) {
                throw new IllegalStateException(
                        "ActorGroup event topic limit exceeded: " + topicLimit);
            }
            doubleReductions.put(topicName,
                    new DoubleReductionState(topicName, kind, initialValue));
            topics.put(topicName, new TopicState(topicName, DeliveryPolicy.LATEST, 1, true));
        }
    }

    /**
     * Subscribe to one publisher on an existing ActorGroup topic. This is a
     * host/runtime substrate for ObservableActor, not its source-level grant
     * API. Group membership is required for both parties; source-level
     * observation grants must be checked by the eventual frontend adapter.
     * This method is intentionally PACKAGE-PRIVATE until that nonforgeable
     * grant boundary exists. A public ActorRef is not publisher authorization.
     *
     * Unlike a topic-wide subscription, an event from a different publisher
     * never consumes this subscription's quota or pending writes.
     */
    <T> Subscription<T> subscribePublisher(
            String name, ActorRuntime.ActorRef<?> publisher) {
        Objects.requireNonNull(publisher, "publisher");
        requireOpen();
        group.requireCurrentMember("subscribe to publisher ActorGroup events");
        if (!runtime.isAlive(publisher)
                || !runtime.isLiveActorAddress(publisher.address())
                || !group.isMember(publisher.id())) {
            throw new SecurityException("publisher must be a live member of this ActorGroup");
        }
        return subscribeInternal(name, publisher.address());
    }

    public <T> Subscription<T> subscribe(String name) {
        return subscribeInternal(name, null);
    }

    private <T> Subscription<T> subscribeInternal(
            String name, ActorRuntime.ActorAddress publisherScope) {
        requireOpen();
        ActorRuntime.ActorId subscriber =
                group.requireCurrentMember("subscribe to ActorGroup events");
        if (ActorRuntime.currentActorKind() != ActorRuntime.ActorKind.SHARED) {
            throw new SecurityException(
                    "private/isolated actors cannot subscribe to the zero-copy ActorGroup event bus yet; "
                            + "use actor mailboxes until isolated event delivery is available");
        }
        TopicState topic = requireTopic(name);
        SubscriptionKey key = new SubscriptionKey(subscriber, publisherScope);

        int actorLimit =
                Math.max(1, ActorRuntime.currentActorPolicy().maxMailboxMessages());
        int capacity = Math.min(topic.capacity, actorLimit);
        int subscriptionLimit = Math.min(
                MAX_SUBSCRIPTIONS_PER_ACTOR_PER_GROUP, actorLimit);

        synchronized (topic.deliveryLock) {
            requireOpen();
            // Publisher leave/stop can overtake initial authorization while
            // the observer is waiting for this topic's admission lock.
            if (publisherScope != null
                    && (!runtime.isLiveActorAddress(publisherScope)
                            || !group.isMember(publisherScope.actorId()))) {
                throw new IllegalStateException(
                        "publisher ended before scoped subscription admission");
            }
            if (topics.get(topic.name) != topic) {
                throw new IllegalStateException(
                        "ActorGroup event topic is no longer registered: " + topic.name);
            }

            for (;;) {
                @SuppressWarnings("unchecked")
                Subscription<T> existing =
                        (Subscription<T>) topic.subscriptions.get(key);
                if (existing != null) {
                    if (!existing.closed()) return existing;
                    topic.subscriptions.remove(key, existing);
                    continue;
                }

                // Existing leases are idempotent; only NEW scopes consume
                // the observer's finite per-topic subscription budget.
                int limit = Math.min(actorLimit, MAX_SUBSCRIPTIONS_PER_OBSERVER_TOPIC);
                long owned = topic.subscriptions.keySet().stream()
                        .filter(existingKey -> existingKey.subscriber().equals(subscriber))
                        .count();
                if (owned >= limit) {
                    throw new IllegalStateException(
                            "ActorGroup event subscription limit exceeded for observer "
                                    + subscriber + ": " + limit);
                }
                reserveSubscriptionCredit(subscriber, subscriptionLimit);
                Subscription<T> created;
                try {
                    created = new Subscription<>(topic, key, capacity);
                } catch (RuntimeException | Error failure) {
                    releaseSubscriptionCredit(subscriber);
                    throw failure;
                }
                Subscription<?> raced;
                try {
                    raced = topic.subscriptions.putIfAbsent(key, created);
                } catch (RuntimeException | Error failure) {
                    created.closeChannelOnly();
                    throw failure;
                }
                if (raced != null) {
                    created.closeChannelOnly();
                    if (raced.closed()) {
                        topic.subscriptions.remove(key, raced);
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Subscription<T> typed = (Subscription<T>) raced;
                    return typed;
                }

                if (!group.isMember(subscriber) || closed.get()
                        || (publisherScope != null
                            && (!runtime.isLiveActorAddress(publisherScope)
                                    || !group.isMember(publisherScope.actorId())))) {
                    topic.subscriptions.remove(key, created);
                    created.closeChannelOnly();
                    throw new IllegalStateException(
                            "ActorGroup membership ended during subscription");
                }
                return created;
            }
        }
    }

    public PublishReceipt publish(String name, Object value) {
        requireOpen();
        ActorRuntime.ActorId publisher = group.currentMemberOrSystem("publish ActorGroup events");
        return publishFrozen(
                requirePublishableTopic(name),
                runtime.freezeActorGroupEventPayload(value),
                publisher);
    }

    /**
     * Nonblocking, host/runtime admission primitive for RELIABLE topics only.
     * A present receipt means all current recipients passed the bounded
     * reservation preflight; it is NOT handler acknowledgment or proof that
     * the returned completion future has finished. Only a Full refusal maps
     * to Optional.empty(): revoked authority, closed topics, invalid payloads
     * and resource errors still fail explicitly and never mint a receipt.
     *
     * Guest Topic<T>.try_publish remains intentionally unlowered until the
     * typed Result<PublishReceipt, PublishError> and membership-grant ABI are
     * supported. Do not expose this Optional as portable-v1 success evidence.
     */
    public Optional<PublishReceipt> tryPublishReliable(String name, Object value) {
        requireOpen();
        ActorRuntime.ActorId publisher =
                group.currentMemberOrSystem("try publish ActorGroup events");
        return tryPublishReliableFrozen(name, value, publisher);
    }

    /** Supervisor-authenticated nonblocking RELIABLE admission. */
    public Optional<PublishReceipt> tryPublishReliableSystem(String name, Object value) {
        group.requireSupervisor("try publish system ActorGroup events");
        requireOpen();
        return tryPublishReliableFrozen(name, value, null);
    }

    private Optional<PublishReceipt> tryPublishReliableFrozen(
            String name, Object value, ActorRuntime.ActorId publisher) {
        TopicState topic = requirePublishableTopic(name);
        if (topic.policy != DeliveryPolicy.RELIABLE) {
            throw new IllegalArgumentException(
                    "tryPublishReliable requires a RELIABLE ActorGroup topic");
        }
        try {
            // Revalidates registered topic and membership at deliveryLock.
            // On Full, publishFrozen rolls back all staged reservations and
            // releases the frozen payload before returning to this caller.
            return Optional.of(publishFrozen(
                    topic, runtime.freezeActorGroupEventPayload(value), publisher));
        } catch (EventBackpressureException full) {
            return Optional.empty();
        }
    }

    /**
     * Host/supervisor publication path. Actor code cannot impersonate the
     * runtime/system publisher.
     */
    public PublishReceipt publishSystem(String name, Object value) {
        group.requireSupervisor("publish system ActorGroup events");
        requireOpen();
        return publishFrozen(
                requirePublishableTopic(name),
                runtime.freezeActorGroupEventPayload(value),
                null);
    }

    public DoubleReductionResult reduceDouble(String name, double candidate) {
        requireOpen();
        if (Double.isNaN(candidate)) {
            throw new IllegalArgumentException("double reduction candidate cannot be NaN");
        }
        ActorRuntime.ActorId publisher = group.currentMemberOrSystem("update ActorGroup reductions");
        DoubleReductionState reduction = requireDoubleReduction(name);
        TopicState topic = requireTopic(name);

        /*
         * Keep state mutation and its LATEST notification in one reduction-local
         * critical section. Without this, two concurrent improvements could
         * publish out of order (for example MIN: 90 after 80), briefly exposing
         * a stale bound even though the atomic state itself was correct.
         *
         * This lock is per reduction, not global to the bus, so unrelated topics
         * and reductions remain fully concurrent.
         */
        // Reduction value and its publication still commit under the same
        // reduction lock. Stale-subscriber channel/Future completion is
        // explicitly deferred until AFTER that lock as well.
        List<Subscription<?>> detached = new ArrayList<>();
        Throwable publicationFailure = null;
        try {
            synchronized (reduction) {
                double current = reduction.current();
                if (!reduction.improves(candidate, current)) {
                    return new DoubleReductionResult(
                            current,
                            false,
                            new PublishReceipt(
                                    topic.subscriptions.size(),
                                    0, 0, 0, OresFuture.completed(List.of())));
                }
                ActorRuntime.FrozenActorGroupEventPayload frozen =
                        runtime.freezeActorGroupEventPayload(candidate);
                PublishReceipt receipt = publishFrozen(
                        topic, frozen, publisher, () -> reduction.set(candidate),
                        detached);
                return new DoubleReductionResult(candidate, true, receipt);
            }
        } catch (RuntimeException | Error failure) {
            publicationFailure = failure;
            throw failure;
        } finally {
            try {
                finishDetachedSubscriptions(detached);
            } catch (RuntimeException | Error cleanupFailure) {
                if (publicationFailure != null) {
                    if (publicationFailure != cleanupFailure)
                        publicationFailure.addSuppressed(cleanupFailure);
                } else throw cleanupFailure;
            }
        }
    }

    public double readDoubleReduction(String name) {
        requireOpen();
        group.currentMemberOrSystem("read ActorGroup reductions");
        DoubleReductionState reduction = requireDoubleReduction(name);
        synchronized (reduction) {
            return reduction.current();
        }
    }

    private void reserveSubscriptionCredit(
            ActorRuntime.ActorId subscriber, int limit) {
        synchronized (subscriptionCreditLock) {
            int held = subscriptionCredits.getOrDefault(subscriber, 0);
            if (held >= limit) {
                throw new IllegalStateException(
                        "ActorGroup subscription limit exceeded: " + limit);
            }
            subscriptionCredits.put(subscriber, held + 1);
        }
    }

    private void releaseSubscriptionCredit(ActorRuntime.ActorId subscriber) {
        synchronized (subscriptionCreditLock) {
            int held = subscriptionCredits.getOrDefault(subscriber, 0);
            if (held <= 0) {
                throw new IllegalStateException(
                        "ActorGroup subscription credit underflow");
            }
            if (held == 1) subscriptionCredits.remove(subscriber);
            else subscriptionCredits.put(subscriber, held - 1);
        }
    }

    /**
     * Logically revoke every subscription owned by the departed subscriber or
     * scoped to the departed publisher, while groupMembershipLock is held.
     * Detach and mark closed BEFORE rejoin; the returned cleanup is the only
     * operation allowed to settle Channel/Future callbacks after the lock.
     */
    Runnable detachActorSubscriptions(ActorRuntime.ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId");
        List<Subscription<?>> detached = new ArrayList<>();
        for (TopicState topic : topics.values()) {
            synchronized (topic.deliveryLock) {
                for (Map.Entry<SubscriptionKey, Subscription<?>> entry
                        : List.copyOf(topic.subscriptions.entrySet())) {
                    SubscriptionKey key = entry.getKey();
                    if (key.subscriber().equals(actorId)
                            || (key.publisher() != null
                                    && key.publisher().actorId().equals(actorId))) {
                        Subscription<?> subscription = entry.getValue();
                        if (topic.subscriptions.remove(key, subscription)
                                && subscription.markClosedForTeardown()) {
                            detached.add(subscription);
                        }
                    }
                }
            }
        }
        return () -> finishDetachedSubscriptions(detached);
    }

    @Override
    public void close() {
        group.requireManager("close an ActorGroup event bus");
        closeFromGroup();
    }

    void closeFromGroup() {
        if (!closed.compareAndSet(false, true)) return;
        List<Subscription<?>> detached = new ArrayList<>();
        synchronized (definitionLock) {
            for (TopicState topic : topics.values()) {
                synchronized (topic.deliveryLock) {
                    for (Subscription<?> subscription
                            : List.copyOf(topic.subscriptions.values())) {
                        if (subscription.markClosedForTeardown()) detached.add(subscription);
                    }
                    topic.subscriptions.clear();
                }
            }
            topics.clear();
            doubleReductions.clear();
        }
        // Never run completion observers under definitionLock/deliveryLock.
        finishDetachedSubscriptions(detached);
    }

    private void finishDetachedSubscriptions(List<Subscription<?>> detached) {
        Throwable first = null;
        for (Subscription<?> subscription : detached) {
            try {
                subscription.finishMarkedClose();
            } catch (Throwable failure) {
                if (first == null) first = failure;
                else if (first != failure) first.addSuppressed(failure);
            }
        }
        if (first != null) rethrowTeardownFailure(first);
    }

    private static void rethrowTeardownFailure(Throwable failure) {
        if (failure instanceof RuntimeException problem) throw problem;
        if (failure instanceof Error problem) throw problem;
        throw new IllegalStateException("ActorGroup event cleanup failed", failure);
    }

    private PublishReceipt publishFrozen(
            TopicState topic,
            ActorRuntime.FrozenActorGroupEventPayload frozenPayload,
            ActorRuntime.ActorId publisher) {
        return publishFrozen(topic, frozenPayload, publisher, () -> { });
    }

    private PublishReceipt publishFrozen(
            TopicState topic,
            ActorRuntime.FrozenActorGroupEventPayload frozenPayload,
            ActorRuntime.ActorId publisher,
            Runnable commitState) {
        // Retain the four-argument admission boundary used by independent
        // exact-head reduction-commit regression tests.
        return publishFrozen(topic, frozenPayload, publisher, commitState, null);
    }

    private PublishReceipt publishFrozen(
            TopicState topic,
            ActorRuntime.FrozenActorGroupEventPayload frozenPayload,
            ActorRuntime.ActorId publisher,
            Runnable commitState,
            List<Subscription<?>> deferredCleanup) {
        Event<Object> event = null;
        List<Subscription<?>> detached = new ArrayList<>();
        Throwable publicationFailure = null;
        try {
            synchronized (topic.deliveryLock) {
                requireOpen();
                // A publisher may lose membership while its payload is being
                // frozen. Revalidate at the delivery admission boundary so
                // no post-revocation publication can commit to subscribers.
                // System publications have a separately checked authority.
                if (publisher != null && !group.isMember(publisher)) {
                    throw new SecurityException(
                            "ActorGroup membership revoked before event publication");
                }
                if (topics.get(topic.name) != topic) {
                    throw new IllegalStateException(
                            "ActorGroup event topic is no longer registered: " + topic.name);
                }

                List<Subscription<?>> snapshot =
                        List.copyOf(topic.subscriptions.values());

                List<Subscription<?>> active =
                        new ArrayList<>(snapshot.size());
                for (Subscription<?> subscription : snapshot) {
                    if (subscription.closed()
                            || !group.isMember(subscription.subscriber)
                            || (subscription.publisherScope != null
                                    && (!group.isMember(
                                            subscription.publisherScope.actorId())
                                            || !runtime.isLiveActorAddress(
                                                    subscription.publisherScope)))) {
                        if (topic.subscriptions.remove(subscription.key, subscription)
                                && subscription.markClosedForTeardown()) {
                            detached.add(subscription);
                        }
                    } else if (subscription.publisherScope == null
                            || subscription.publisherScope.actorId().equals(publisher)) {
                        // Filter before reliable reservation and per-observer
                        // credit accounting. A system event only reaches
                        // legacy, unscoped topic subscriptions.
                        active.add(subscription);
                    }
                }
                // Reliable admission must reserve every participating credit
                // BEFORE assigning a committed sequence, creating an event,
                // or mutating reduction state. A rejected publication cannot
                // create a phantom event or sequence gap.
                List<Subscription<?>> reserved = null;
                if (topic.policy == DeliveryPolicy.RELIABLE) {
                    reserved = new ArrayList<>(active.size());
                    try {
                        for (Subscription<?> subscription : active) {
                            if (!subscription.reserveReliableEvent()) {
                                throw new EventBackpressureException(
                                        topic.name,
                                        subscription.subscriber,
                                        subscription.reliableOutstandingLimit);
                            }
                            reserved.add(subscription);
                        }
                    } catch (Throwable failure) {
                        for (Subscription<?> admitted : reserved) {
                            admitted.releaseReliableEvent();
                        }
                        throw failure;
                    }
                }

                long sequence;
                try {
                    // Keep mainline RELIABLE reservation-before-sequence;
                    // retain its fail-closed signed-overflow policy until
                    // a dedicated unsigned-u64 conformance suite is executed.
                    sequence = Math.addExact(topic.sequence.get(), 1L);
                    Event<Object> created = new Event<>(
                            topic.name,
                            sequence,
                            publisher,
                            System.nanoTime(),
                            frozenPayload.value(),
                            frozenPayload.reservation());
                    event = created;
                    // Reduction state and event sequence commit before fanout.
                    commitState.run();
                    topic.sequence.set(sequence);
                } catch (Throwable failure) {
                    // If event allocation or the commit hook fails after the
                    // successful preflight, no write has started yet.
                    if (reserved != null) {
                        for (Subscription<?> admitted : reserved) {
                            admitted.releaseReliableEvent();
                        }
                    }
                    throw failure;
                }

                if (active.isEmpty()) {
                    topic.published.incrementAndGet();
                    return new PublishReceipt(
                            0, 0, 0, 0, OresFuture.completed(List.of()), sequence);
                }

                if (topic.policy == DeliveryPolicy.RELIABLE) {
                    List<OresFuture<Void>> writes =
                            new ArrayList<>(reserved.size());
                    int started = 0;
                    try {
                        for (; started < reserved.size(); started++) {
                            @SuppressWarnings("unchecked")
                            Subscription<Object> subscription =
                                    (Subscription<Object>) reserved.get(started);
                            writes.add(
                                    subscription.writeReliableReserved(event));
                        }
                    } catch (Throwable failure) {
                        for (OresFuture<Void> write : writes) {
                            if (!write.isDone()) write.cancel(false);
                        }
                        for (int i = started + 1; i < reserved.size(); i++) {
                            reserved.get(i).releaseReliableEvent();
                        }
                        throw failure;
                    }

                    topic.published.incrementAndGet();
                    return new PublishReceipt(
                            reserved.size(),
                            reserved.size(),
                            0,
                            0,
                            combineReliableWrites(writes),
                            sequence);
                }

                // LATEST/LOSSY have already committed exactly once above,
                // inside the delivery lock and after allocating the event.
                // Repeating commitState would run reduction callbacks twice.
                int accepted = 0;
                int dropped = 0;
                int coalesced = 0;
                for (Subscription<?> raw : active) {
                    @SuppressWarnings("unchecked")
                    Subscription<Object> subscription =
                            (Subscription<Object>) raw;
                    switch (topic.policy) {
                        case LATEST -> {
                            int replaced = subscription.writeLatest(event);
                            if (replaced >= 0) {
                                accepted++;
                                coalesced += replaced;
                            } else {
                                dropped++;
                            }
                        }
                        case LOSSY -> {
                            if (subscription.writeLossy(event)) accepted++;
                            else dropped++;
                        }
                        case RELIABLE ->
                                throw new AssertionError(
                                        "reliable publication handled above");
                    }
                }

                topic.published.incrementAndGet();
                topic.dropped.addAndGet(dropped);
                topic.coalesced.addAndGet(coalesced);
                return new PublishReceipt(
                        active.size(),
                        accepted,
                        dropped,
                        coalesced,
                        OresFuture.completed(List.of()),
                        sequence);
            }
        } catch (RuntimeException | Error failure) {
            publicationFailure = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = null;
            try {
                if (event == null) {
                    frozenPayload.reservation().close();
                } else {
                    // Drop the publisher's temporary hold. Queued subscribers
                    // retain their own references until read/coalesce/close.
                    event.releaseDelivery();
                }
            } catch (Throwable failure) {
                cleanupFailure = failure;
            }
            try {
                // Detach happened under deliveryLock. A reduction caller
                // delegates cleanup until its own reduction lock is released.
                if (deferredCleanup == null) finishDetachedSubscriptions(detached);
                else deferredCleanup.addAll(detached);
            } catch (Throwable failure) {
                if (cleanupFailure == null) cleanupFailure = failure;
                else if (cleanupFailure != failure) cleanupFailure.addSuppressed(failure);
            }
            if (cleanupFailure != null) {
                if (publicationFailure != null) {
                    if (publicationFailure != cleanupFailure)
                        publicationFailure.addSuppressed(cleanupFailure);
                } else {
                    rethrowTeardownFailure(cleanupFailure);
                }
            }
        }
    }

    private OresFuture<List<Void>> combineReliableWrites(
            List<OresFuture<Void>> writes) {
        /*
         * These write Futures are private children owned by this publication,
         * not borrowed inputs. Cancelling the publication therefore revokes
         * every still-pending channel admission. Already-committed writes are
         * not retracted: cancellation is an admission fence, not a recall of
         * events a subscriber can already observe.
         *
         * Do not use OresFutures.all() as the returned Future directly:
         * general combinators intentionally do NOT cancel borrowed children.
         */
        OresFuture<List<Void>> observed = OresFutures.<Void>all(writes);
        OresFuture<List<Void>> owned = new OresFuture<>(() -> {
            for (OresFuture<Void> write : writes) {
                if (!write.isDone()) write.cancel(false);
            }
        });
        observed.whenCompleteRuntime((values, failure) -> {
            if (owned.isDone()) return;
            if (failure == null) {
                owned.completeFromRuntime(values);
            } else if (observed.isCancelled()) {
                owned.cancel(false);
            } else {
                owned.failFromRuntime(OresFuture.unwrap(failure));
            }
        });
        return owned;
    }

    private TopicState requireTopic(String name) {
        String topicName = normalizeTopicName(name);
        TopicState topic = topics.get(topicName);
        if (topic == null) throw new IllegalArgumentException("unknown ActorGroup event topic: " + topicName);
        return topic;
    }

    private TopicState requirePublishableTopic(String name) {
        TopicState topic = requireTopic(name);
        if (topic.reductionOwned) {
            throw new IllegalStateException(
                    "reduction notifications require reduceDouble: " + topic.name);
        }
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

    // Null publisher means an existing topic-wide subscription.
    private record SubscriptionKey(
            ActorRuntime.ActorId subscriber, ActorRuntime.ActorAddress publisher) {
        private SubscriptionKey {
            Objects.requireNonNull(subscriber, "subscriber");
        }
    }

    private final class TopicState {
        private final String name;
        private final DeliveryPolicy policy;
        private final int capacity;
        private final boolean reductionOwned;
        private final Map<SubscriptionKey, Subscription<?>> subscriptions =
                new ConcurrentHashMap<>();
        private final Object deliveryLock = new Object();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong published = new AtomicLong();
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicLong coalesced = new AtomicLong();

        private TopicState(String name, DeliveryPolicy policy, int capacity) {
            this(name, policy, capacity, false);
        }

        private TopicState(String name, DeliveryPolicy policy, int capacity, boolean reductionOwned) {
            this.name = name;
            this.policy = policy;
            this.capacity = capacity;
            this.reductionOwned = reductionOwned;
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
        private final long initialBits;
        private final AtomicLong bits;

        private DoubleReductionState(
                String name,
                DoubleReductionKind kind,
                double initialValue) {
            this.name = name;
            this.kind = kind;
            this.initialBits = Double.doubleToRawLongBits(initialValue);
            this.bits = new AtomicLong(initialBits);
        }

        private double current() {
            return Double.longBitsToDouble(bits.get());
        }

        private boolean sameDefinition(
                DoubleReductionKind requestedKind,
                double requestedInitialValue) {
            return kind == requestedKind
                    && initialBits == Double.doubleToRawLongBits(requestedInitialValue);
        }

        private boolean improves(double candidate, double observed) {
            return switch (kind) {
                case MIN -> Double.compare(candidate, observed) < 0;
                case MAX -> Double.compare(candidate, observed) > 0;
            };
        }

        private void set(double candidate) {
            bits.set(Double.doubleToRawLongBits(candidate));
        }
    }

    public final class Subscription<T> implements AutoCloseable {
        private final TopicState topic;
        private final SubscriptionKey key;
        private final ActorRuntime.ActorId subscriber;
        private final ActorRuntime.ActorAddress publisherScope;
        private final ChannelRuntime.Channel<Event<T>> channel;
        private final Object latestLock = new Object();
        private final AtomicInteger reliableOutstanding = new AtomicInteger();
        private final int reliableOutstandingLimit;
        private final AtomicInteger pendingReads = new AtomicInteger();
        private final int pendingReadLimit;
        private final AtomicBoolean subscriptionClosed = new AtomicBoolean();

        private Subscription(
                TopicState topic,
                SubscriptionKey key,
                int capacity) {
            this.topic = topic;
            this.key = key;
            this.subscriber = key.subscriber();
            this.publisherScope = key.publisher();
            this.channel = new ChannelRuntime.Channel<>(capacity);
            this.pendingReadLimit = ActorRuntime.currentActorPolicy().maxMailboxMessages();
            long bounded = Math.max((long) capacity + 1L, (long) capacity * 2L);
            this.reliableOutstandingLimit = (int) Math.min(Integer.MAX_VALUE, bounded);
        }

        public String topic() {
            return topic.name;
        }

        public ActorRuntime.ActorId subscriber() {
            return subscriber;
        }

        /** Null indicates a legacy subscription to every topic publisher. */
        public Optional<ActorRuntime.ActorAddress> publisherScope() {
            return Optional.ofNullable(publisherScope);
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
            Optional<Event<T>> event = channel.tryRead();
            if (event.isPresent()) {
                if (topic.policy == DeliveryPolicy.RELIABLE) {
                    releaseReliableEvent();
                }
                event.get().releaseDelivery();
            }
            return event;
        }

        public OresFuture<Event<T>> readAsync() {
            requireReadable();
            while (true) {
                int current = pendingReads.get();
                if (current >= pendingReadLimit) {
                    throw new IllegalStateException("event subscription pending read limit exceeded: "
                            + pendingReadLimit);
                }
                if (pendingReads.compareAndSet(current, current + 1)) break;
            }
            final OresFuture<Event<T>> read;
            try {
                read = channel.readAsync();
            } catch (RuntimeException | Error failure) {
                pendingReads.decrementAndGet();
                throw failure;
            }
            read.whenCompleteRuntime((value, failure) -> {
                pendingReads.decrementAndGet();
                if (failure == null) {
                    if (topic.policy == DeliveryPolicy.RELIABLE) {
                        releaseReliableEvent();
                    }
                    value.releaseDelivery();
                }
            });
            return read;
        }

        private boolean reserveReliableEvent() {
            if (subscriptionClosed.get()) return false;
            while (true) {
                int current = reliableOutstanding.get();
                if (current >= reliableOutstandingLimit) return false;
                if (reliableOutstanding.compareAndSet(current, current + 1)) {
                    if (!subscriptionClosed.get()) return true;
                    releaseReliableEvent();
                    return false;
                }
            }
        }

        private void releaseReliableEvent() {
            while (true) {
                int current = reliableOutstanding.get();
                if (current == 0) return;
                if (reliableOutstanding.compareAndSet(current, current - 1)) return;
            }
        }

        private OresFuture<Void> writeReliableReserved(Event<T> event) {
            event.retainDelivery();
            try {
                if (subscriptionClosed.get()) {
                    event.releaseDelivery();
                    releaseReliableEvent();
                    return OresFuture.failed(
                            new IllegalStateException("event subscription is closed"));
                }
                OresFuture<Void> write = channel.writeAsync(event);
                write.whenCompleteRuntime((ignored, failure) -> {
                    if (failure != null) {
                        event.releaseDelivery();
                        releaseReliableEvent();
                    }
                });
                return write;
            } catch (Throwable failure) {
                event.releaseDelivery();
                releaseReliableEvent();
                throw failure;
            }
        }

        private boolean writeLossy(Event<T> event) {
            if (subscriptionClosed.get()) return false;
            event.retainDelivery();
            try {
                boolean admitted = channel.tryWrite(event);
                if (!admitted) event.releaseDelivery();
                return admitted;
            } catch (ChannelRuntime.ChannelClosedException closedChannel) {
                event.releaseDelivery();
                return false;
            } catch (Throwable failure) {
                event.releaseDelivery();
                throw failure;
            }
        }

        /**
         * Returns 1 when an older unread event was coalesced, 0 when admitted
         * without replacement, and -1 when a close race prevented admission.
         */
        private int writeLatest(Event<T> event) {
            if (subscriptionClosed.get()) return -1;
            synchronized (latestLock) {
                event.retainDelivery();
                boolean retained = true;
                try {
                    if (channel.tryWrite(event)) return 0;

                    Optional<Event<T>> previous = channel.tryRead();
                    if (previous.isPresent()) {
                        previous.get().releaseDelivery();
                    }

                    if (channel.tryWrite(event)) {
                        return previous.isPresent() ? 1 : 0;
                    }

                    event.releaseDelivery();
                    retained = false;
                    if (subscriptionClosed.get()) return -1;
                    throw new IllegalStateException(
                            "LATEST event subscription failed to admit replacement value");
                } catch (ChannelRuntime.ChannelClosedException closedChannel) {
                    if (retained) event.releaseDelivery();
                    return -1;
                } catch (Throwable failure) {
                    if (retained) event.releaseDelivery();
                    throw failure;
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
            ActorRuntime.ActorId current =
                    group.requireCurrentMember(
                            "close ActorGroup event subscriptions");
            if (!current.equals(subscriber)) {
                throw new SecurityException(
                        "an actor may only close its own ActorGroup event subscription");
            }
            boolean detached = false;
            synchronized (topic.deliveryLock) {
                if (topic.subscriptions.remove(key, this)) {
                    detached = markClosedForTeardown();
                }
            }
            if (detached) finishMarkedClose();
        }

        private boolean markClosedForTeardown() {
            return subscriptionClosed.compareAndSet(false, true);
        }

        private void finishMarkedClose() {
            Throwable first = null;
            try {
                channel.close();
            } catch (Throwable failure) {
                first = failure;
            }
            try {
                // No group/definition/delivery monitor may be held here.
                while (channel.drainOne(buffered -> {
                    if (topic.policy == DeliveryPolicy.RELIABLE) {
                        releaseReliableEvent();
                    }
                    buffered.releaseDelivery();
                })) {
                    // Drain previously queued immutable deliveries.
                }
            } catch (Throwable failure) {
                if (first == null) first = failure;
                else if (first != failure) first.addSuppressed(failure);
            }
            try {
                // Exactly one mark winner settles this subscription's credit.
                releaseSubscriptionCredit(subscriber);
            } catch (Throwable failure) {
                if (first == null) first = failure;
                else if (first != failure) first.addSuppressed(failure);
            }
            if (first != null) rethrowTeardownFailure(first);
        }

        private void closeChannelOnly() {
            // Only newly constructed, unpublished subscription candidates
            // use this legacy path during admission. Exposed subscriptions
            // must detach under the topic lock and finish after releasing it.
            if (!markClosedForTeardown()) return;
            finishMarkedClose();
        }
    }
}
