package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local OTP-pg-style named/scoped facade over ActorRuntime.ActorGroup.
 *
 * <p>There is intentionally no second membership engine here. ActorGroup
 * remains the authority for bounded admission, actor retirement cleanup,
 * capability rotation, and separation of cooperation membership from lifecycle
 * ownership. Distributed membership is a later provider with explicitly
 * eventual semantics; this class is local-only.</p>
 */
public final class ProcessGroupRegistry implements AutoCloseable {
    private record Key(String scope, String name) { }

    private static final class Entry {
        private final ActorRuntime.ActorGroup group;
        private final ActorRuntime.ActorGroupJoinCapability join;

        private Entry(
                ActorRuntime.ActorGroup group,
                ActorRuntime.ActorGroupJoinCapability join) {
            this.group = group;
            this.join = join;
        }
    }

    public final class Membership<M> implements AutoCloseable {
        private final Key key;
        private final ActorRuntime.ActorId actorId;
        private final ActorRuntime.ActorGroup group;
        private final AtomicBoolean left = new AtomicBoolean();

        private Membership(
                Key key,
                ActorRuntime.ActorId actorId,
                ActorRuntime.ActorGroup group) {
            this.key = key;
            this.actorId = actorId;
            this.group = group;
        }

        public String scope() { return key.scope(); }
        public String name() { return key.name(); }
        public ActorRuntime.ActorId actorId() { return actorId; }
        public boolean left() { return left.get(); }

        public void leave() {
            if (!left.compareAndSet(false, true)) return;
            ActorRuntime.ActorId current = ActorRuntime.currentActorId().orElseThrow(() ->
                    new SecurityException("pg membership leave requires actor execution"));
            if (!current.equals(actorId)) {
                left.set(false);
                throw new SecurityException(
                        "pg membership may only be left by the actor that joined it");
            }
            try {
                leaveMembership(key, actorId, group);
            } catch (RuntimeException | Error failure) {
                left.set(false);
                throw failure;
            }
        }

        @Override
        public void close() {
            leave();
        }
    }

    private final ActorRuntime runtime;
    private final int maxGroups;
    private final int maxNameChars;
    private final Object lock = new Object();
    private final LinkedHashMap<Key, Entry> groups = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ProcessGroupRegistry(ActorRuntime runtime, int maxGroups) {
        this(runtime, maxGroups, 128);
    }

    public ProcessGroupRegistry(
            ActorRuntime runtime,
            int maxGroups,
            int maxNameChars) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (maxGroups <= 0) throw new IllegalArgumentException("maxGroups must be > 0");
        if (maxNameChars <= 0) throw new IllegalArgumentException("maxNameChars must be > 0");
        this.maxGroups = maxGroups;
        this.maxNameChars = maxNameChars;
    }

    public <M> Membership<M> joinCurrent(String scope, String name) {
        Key key = key(scope, name);
        ActorRuntime.ActorId actorId = ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("pg.joinCurrent requires actor execution"));
        synchronized (lock) {
            Entry entry = entryForJoinLocked(key);
            try {
                entry.group.joinCurrent(entry.join);
                return new Membership<>(key, actorId, entry.group);
            } catch (RuntimeException | Error failure) {
                reclaimIfEmptyLocked(key, entry);
                throw failure;
            }
        }
    }

    /**
     * Host/supervisor admission helper. Actor code may only pass its own ref,
     * because ActorGroup enforces self-only joining for actor callers.
     */
    public <M> void join(
            String scope,
            String name,
            ActorRuntime.ActorRef<M> ref) {
        Key key = key(scope, name);
        synchronized (lock) {
            Entry entry = entryForJoinLocked(key);
            try {
                entry.group.join(ref, entry.join);
            } catch (RuntimeException | Error failure) {
                reclaimIfEmptyLocked(key, entry);
                throw failure;
            }
        }
    }

    public void leaveCurrent(String scope, String name) {
        Key key = key(scope, name);
        ActorRuntime.ActorId actorId = ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("pg.leaveCurrent requires actor execution"));
        synchronized (lock) {
            Entry entry = existingLocked(key);
            if (entry == null) throw new IllegalStateException("unknown pg group");
            leaveMembershipLocked(key, actorId, entry.group, false);
        }
    }

    @SuppressWarnings("unchecked")
    public <M> List<ActorRuntime.ActorRef<M>> members(String scope, String name) {
        Key key = key(scope, name);
        synchronized (lock) {
            Entry entry = existingLocked(key);
            if (entry == null) return List.of();
            List<ActorRuntime.ActorRef<M>> refs = new ArrayList<>();
            for (ActorRuntime.ActorId id : entry.group.memberIds()) {
                ActorRuntime.ActorRef<?> ref = runtime.resolveLocalActorRef(id);
                if (ref != null && ref.isAlive()) {
                    refs.add((ActorRuntime.ActorRef<M>) ref);
                }
            }
            return List.copyOf(refs);
        }
    }

    public <M> List<ActorRuntime.ActorRef<M>> localMembers(String scope, String name) {
        return members(scope, name);
    }

    public int memberCount(String scope, String name) {
        Key key = key(scope, name);
        synchronized (lock) {
            Entry entry = existingLocked(key);
            return entry == null ? 0 : entry.group.memberCount();
        }
    }

    public boolean contains(
            String scope,
            String name,
            ActorRuntime.ActorRef<?> ref) {
        Key key = key(scope, name);
        synchronized (lock) {
            Entry entry = existingLocked(key);
            return entry != null && entry.group.contains(ref);
        }
    }

    /**
     * Explicit group removal. ActorGroup itself enforces creator/host manager
     * authority, so ordinary members cannot delete a shared pg group.
     */
    public void delete(String scope, String name) {
        requireHostManager("delete pg groups");
        Key key = key(scope, name);
        synchronized (lock) {
            Entry entry = groups.get(key);
            if (entry == null) return;
            entry.group.runtimeClose();
            groups.remove(key, entry);
        }
    }

    public List<String> groupNames(String scope) {
        String normalizedScope = normalize("scope", scope);
        synchronized (lock) {
            requireOpen();
            pruneEmptyLocked();
            List<String> names = new ArrayList<>();
            for (Map.Entry<Key, Entry> item : groups.entrySet()) {
                if (item.getKey().scope().equals(normalizedScope)
                        && !item.getValue().group.closed()) {
                    names.add(item.getKey().name());
                }
            }
            return List.copyOf(names);
        }
    }

    public int groupCount() {
        synchronized (lock) {
            requireOpen();
            pruneEmptyLocked();
            return groups.size();
        }
    }

    @Override
    public void close() {
        requireHostManager("close pg registry");
        if (!closed.compareAndSet(false, true)) return;
        List<Entry> snapshot;
        synchronized (lock) {
            snapshot = List.copyOf(groups.values());
            groups.clear();
        }
        RuntimeException first = null;
        for (Entry entry : snapshot) {
            if (entry.group.closed()) continue;
            try {
                entry.group.runtimeClose();
            } catch (RuntimeException failure) {
                if (first == null) first = failure;
                else first.addSuppressed(failure);
            }
        }
        if (first != null) throw first;
    }

    private Entry entryForJoinLocked(Key key) {
        requireOpen();
        pruneEmptyLocked();
        Entry existing = groups.get(key);
        if (existing != null) return existing;
        if (groups.size() >= maxGroups) {
            throw new IllegalStateException(
                    "pg group limit exceeded: maximum " + maxGroups);
        }
        ActorRuntime.ActorGroup group = runtime.createRuntimeOwnedActorGroup();
        try {
            Entry created = new Entry(group, group.runtimeJoinCapability());
            groups.put(key, created);
            return created;
        } catch (RuntimeException | Error failure) {
            try {
                group.runtimeClose();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private Entry existingLocked(Key key) {
        requireOpen();
        Entry entry = groups.get(key);
        if (entry == null) return null;
        if (entry.group.closed() || entry.group.runtimeMemberCount() == 0) {
            groups.remove(key, entry);
            if (!entry.group.closed()) entry.group.runtimeClose();
            return null;
        }
        return entry;
    }

    private void leaveMembership(
            Key key,
            ActorRuntime.ActorId actorId,
            ActorRuntime.ActorGroup group) {
        synchronized (lock) {
            leaveMembershipLocked(key, actorId, group, true);
        }
    }

    private void leaveMembershipLocked(
            Key key,
            ActorRuntime.ActorId actorId,
            ActorRuntime.ActorGroup group,
            boolean idempotentHandle) {
        requireOpen();
        ActorRuntime.ActorId current = ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("pg membership leave requires actor execution"));
        if (!current.equals(actorId)) {
            throw new SecurityException(
                    "pg membership may only be left by the actor that joined it");
        }
        Entry entry = groups.get(key);
        if (entry == null || entry.group != group) {
            if (idempotentHandle) return;
            throw new IllegalStateException("stale pg membership");
        }
        if (!group.runtimeContains(actorId)) {
            if (idempotentHandle) return;
            throw new IllegalStateException("actor is not a member of pg group");
        }
        group.leaveCurrent();
        if (group.runtimeMemberCount() == 0) {
            groups.remove(key, entry);
            group.runtimeClose();
        }
    }

    private void reclaimIfEmptyLocked(Key key, Entry entry) {
        if (entry.group.closed()) {
            groups.remove(key, entry);
            return;
        }
        if (entry.group.runtimeMemberCount() == 0) {
            groups.remove(key, entry);
            entry.group.runtimeClose();
        }
    }

    private void pruneEmptyLocked() {
        var iterator = groups.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Entry> item = iterator.next();
            ActorRuntime.ActorGroup group = item.getValue().group;
            if (group.closed()) {
                iterator.remove();
                continue;
            }
            if (group.runtimeMemberCount() == 0) {
                iterator.remove();
                group.runtimeClose();
            }
        }
    }

    private Key key(String scope, String name) {
        return new Key(normalize("scope", scope), normalize("name", name));
    }

    private String normalize(String field, String value) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("pg " + field + " cannot be empty");
        }
        if (normalized.length() > maxNameChars) {
            throw new IllegalArgumentException(
                    "pg " + field + " exceeds " + maxNameChars + " characters");
        }
        return normalized;
    }

    private void pruneClosed() {
        groups.entrySet().removeIf(entry -> entry.getValue().group.closed());
    }

    private void requireOpen() {
        ActorRuntime current = ActorRuntime.currentActorRuntime();
        if (current != null && current != runtime) {
            throw new SecurityException(
                    "pg registry cannot cross ActorRuntime boundaries");
        }
        if (closed.get()) throw new IllegalStateException("pg registry is closed");
    }

    private void requireHostManager(String operation) {
        ActorRuntime current = ActorRuntime.currentActorRuntime();
        if (current != null) {
            if (current != runtime) {
                throw new SecurityException(
                        operation + " cannot cross ActorRuntime boundaries");
            }
            throw new SecurityException(operation + " requires host/supervisor authority");
        }
        if (closed.get() && !"close pg registry".equals(operation)) {
            throw new IllegalStateException("pg registry is closed");
        }
    }
}
