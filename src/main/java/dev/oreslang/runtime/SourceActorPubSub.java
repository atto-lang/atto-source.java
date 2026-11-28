package dev.oreslang.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Source-language facade for the in-memory ActorGroup pub/sub runtime.
 *
 * <p>Names are discovery keys, never authorization capabilities. Root/host code
 * creates groups and explicitly admits actors. Actor code may resolve a named
 * group only after it is already a member; resolving the name can therefore
 * never bootstrap membership or lifecycle authority.</p>
 */
public final class SourceActorPubSub implements AutoCloseable {
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MAX_TOPIC_NAME_BYTES = 128;

    private final ActorRuntime runtime;
    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SourceActorPubSub(ActorRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * Root code creates a group on first use. Actor code can only resolve an
     * existing group to which the current actor already belongs.
     */
    public Group group(String name) {
        requireOpen();
        String normalized = normalizeName(name, "ActorGroup");
        ActorRuntime.ActorId current = ActorRuntime.currentActorId().orElse(null);
        if (current != null) {
            requireCurrentRuntime("resolve a named ActorGroup");
            Group existing = groups.get(normalized);
            if (existing == null || existing.raw.closed()) {
                throw new IllegalStateException(
                        "named ActorGroup does not exist or is closed: " + normalized);
            }
            if (!existing.raw.isMember(current)) {
                throw new SecurityException(
                        "ActorGroup name lookup does not grant membership: " + normalized);
            }
            return existing;
        }

        synchronized (groups) {
            requireOpen();
            Group existing = groups.get(normalized);
            if (existing != null && !existing.raw.closed()) return existing;
            if (existing != null) groups.remove(normalized, existing);

            ActorRuntime.ActorGroup raw = runtime.createActorGroup();
            Group created = new Group(
                    normalized,
                    raw,
                    raw.joinCapability());
            groups.put(normalized, created);
            return created;
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("SourceActorPubSub is closed");
        }
    }

    private void requireCurrentRuntime(String operation) {
        ActorRuntime current = ActorRuntime.currentActorRuntime();
        if (current != null && current != runtime) {
            throw new SecurityException(operation + " crossed ActorRuntime boundary");
        }
    }

    private static String normalizeName(String value, String kind) {
        Objects.requireNonNull(value, kind + " name");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH ||
                normalized.codePoints().allMatch(cp ->
                        Character.isWhitespace(cp) || Character.isSpaceChar(cp))) {
            throw new IllegalArgumentException(
                    kind + " name must contain 1.." + MAX_NAME_LENGTH + " characters");
        }
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isISOControl(ch)) {
                throw new IllegalArgumentException(kind + " name contains a control character");
            }
        }
        return normalized;
    }

    /**
     * Portable v1 topic names are exact ASCII identifiers, not human labels.
     * Do not trim, case-fold, or normalize them: doing so can alias two
     * independently authorized topics. ASCII makes Java char count equal
     * to the contract's UTF-8 byte limit.
     */
    private static String normalizeTopicName(String name) {
        Objects.requireNonNull(name, "Topic name");
        if (name.isEmpty() || name.length() > MAX_TOPIC_NAME_BYTES) {
            throw new IllegalArgumentException("Topic name must be 1..128 ASCII bytes");
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean letter = (ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z');
            boolean permitted = letter || (i > 0
                    && ((ch >= '0' && ch <= '9')
                    || ch == '.' || ch == '_' || ch == '/' || ch == '-'));
            if (!permitted) {
                throw new IllegalArgumentException(
                        "Topic name must match [A-Za-z][A-Za-z0-9._/-]{0,127}");
            }
        }
        return name;
    }

    private static ActorEventBus.DeliveryPolicy parsePolicy(String value) {
        String normalized = normalizeName(value, "Topic policy")
                .toUpperCase(Locale.ROOT);
        try {
            return ActorEventBus.DeliveryPolicy.valueOf(normalized);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "unknown Topic policy '" + value
                            + "'; expected reliable, latest, or lossy");
        }
    }

    public final class Group {
        private final String name;
        private final ActorRuntime.ActorGroup raw;
        private final ActorRuntime.ActorGroupJoinCapability joinCapability;

        private Group(
                String name,
                ActorRuntime.ActorGroup raw,
                ActorRuntime.ActorGroupJoinCapability joinCapability) {
            this.name = name;
            this.raw = raw;
            this.joinCapability = joinCapability;
        }

        public String name() {
            return name;
        }

        /**
         * Explicit root/host admission. Actor code cannot use a named handle to
         * self-authorize; actors receive membership only from an existing
         * authority boundary.
         */
        public void join(ActorRuntime.ActorRef<?> actor) {
            if (ActorRuntime.currentActorId().isPresent()) {
                throw new SecurityException(
                        "actor code cannot use ActorGroup.join; membership must be granted externally");
            }
            raw.join(Objects.requireNonNull(actor, "actor"), joinCapability);
        }

        /** Root/host revocation of one cooperating membership. */
        public void leave(ActorRuntime.ActorRef<?> actor) {
            if (ActorRuntime.currentActorId().isPresent()) {
                throw new SecurityException(
                        "actor code cannot remove another ActorGroup member");
            }
            raw.leave(Objects.requireNonNull(actor, "actor"));
        }

        public boolean contains(ActorRuntime.ActorRef<?> actor) {
            requireActorMembershipIfExecuting("inspect ActorGroup membership");
            return raw.contains(Objects.requireNonNull(actor, "actor"));
        }

        public int memberCount() {
            requireActorMembershipIfExecuting("inspect ActorGroup membership");
            return raw.memberCount();
        }

        private void requireActorMembershipIfExecuting(String operation) {
            if (ActorRuntime.currentActorId().isPresent()) {
                requireCurrentRuntime(operation);
                raw.requireCurrentMember(operation);
            }
        }

        /** Define a topic. This remains manager-only in ActorRuntime. */
        public Topic topic(
                String topicName,
                String policy,
                int capacity) {
            String normalized = normalizeTopicName(topicName);
            ActorEventBus.Topic<Object> topic =
                    raw.events().topic(
                            normalized,
                            parsePolicy(policy),
                            capacity);
            return new Topic(this, topic);
        }

        /** Resolve an already-defined topic. Membership is checked by events(). */
        public Topic topic(String topicName) {
            requireActorMembershipIfExecuting("resolve an ActorGroup topic");
            String normalized = normalizeTopicName(topicName);
            ActorEventBus.Topic<Object> topic =
                    raw.events().topic(normalized);
            return new Topic(this, topic);
        }

        public void close() {
            raw.close();
            groups.remove(name, this);
        }
    }

    public final class Topic {
        private final Group group;
        private final ActorEventBus.Topic<Object> raw;

        private Topic(Group group, ActorEventBus.Topic<Object> raw) {
            this.group = Objects.requireNonNull(group, "group");
            this.raw = Objects.requireNonNull(raw, "topic");
        }

        public String name() {
            return raw.name();
        }

        public Group group() {
            return group;
        }

        /**
         * Awaitable publication admission. Completion means bounded fanout
         * admission, never handler execution or business acknowledgement.
         */
        public OresFuture<Void> publish(Object value) {
            ActorEventBus.PublishReceipt receipt = raw.publish(value);
            return asVoidFuture(receipt.completion());
        }

        /** Subscription belongs to the currently executing shared actor. */
        public Subscription subscribe() {
            return new Subscription(raw.subscribe());
        }
    }

    public final class Subscription {
        private final ActorEventBus.Subscription<Object> raw;

        private Subscription(ActorEventBus.Subscription<Object> raw) {
            this.raw = Objects.requireNonNull(raw, "subscription");
        }

        public Optional<Object> tryRead() {
            return raw.tryRead().map(ActorEventBus.Event::value);
        }

        public OresFuture<Object> next() {
            OresFuture<ActorEventBus.Event<Object>> source = raw.readAsync();
            OresFuture<Object> mapped =
                    new OresFuture<>(() -> source.cancel(false));
            source.whenCompleteRuntime((event, failure) -> {
                if (failure == null) {
                    mapped.completeFromRuntime(event.value());
                } else if (source.isCancelled()) {
                    mapped.cancel(false);
                } else {
                    mapped.failFromRuntime(OresFuture.unwrap(failure));
                }
            });
            return mapped;
        }

        public boolean closed() {
            return raw.closed();
        }

        public void close() {
            raw.close();
        }
    }

    private static OresFuture<Void> asVoidFuture(OresFuture<?> source) {
        OresFuture<Void> mapped = new OresFuture<>(() -> source.cancel(false));
        source.whenCompleteRuntime((ignored, failure) -> {
            if (failure == null) {
                mapped.completeFromRuntime(null);
            } else if (source.isCancelled()) {
                mapped.cancel(false);
            } else {
                mapped.failFromRuntime(OresFuture.unwrap(failure));
            }
        });
        return mapped;
    }

    /**
     * Revocation must close the actual group lifetimes, not merely remove
     * their names from the directory: cached handles and queued event leases
     * otherwise remain usable after the facade has been closed.
     *
     * <p>Mark/claim under the registry monitor, but close each ActorGroup
     * outside it because channel completion may invoke runtime callbacks.
     * Attempt every close even if one fails.</p>
     */
    @Override
    public void close() {
        requireCurrentRuntime("close actor PubSub");
        if (ActorRuntime.currentActorId().isPresent()) {
            throw new SecurityException("actors cannot close the runtime PubSub directory");
        }
        final List<Group> retired;
        synchronized (groups) {
            if (!closed.compareAndSet(false, true)) return;
            retired = List.copyOf(groups.values());
            groups.clear();
        }

        RuntimeException failure = null;
        for (Group group : retired) {
            try {
                group.raw.close();
            } catch (RuntimeException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        if (failure != null) throw failure;
    }
}
