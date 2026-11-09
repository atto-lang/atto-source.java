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
        // Registry lock owns these epochs. Textual names or actor IDs alone
        // must not authorize a cached handle after leave and later rejoin.
        private final Map<ActorRuntime.ActorId, Long> memberEpochs = new LinkedHashMap<>();
        private long lastEpoch;

        private Entry(
                ActorRuntime.ActorGroup group,
                ActorRuntime.ActorGroupJoinCapability join) {
            this.group = group;
            this.join = join;
        }

        private void pruneEpochs() {
            memberEpochs.keySet().removeIf(actorId -> !group.runtimeContains(actorId));
        }

        private void requireEpochRoom(ActorRuntime.ActorId actorId, boolean wasMember) {
            if ((!wasMember || !memberEpochs.containsKey(actorId))
                    && lastEpoch == Long.MAX_VALUE) {
                throw new IllegalStateException("pg membership generation exhausted");
            }
        }

        private long epochForJoin(ActorRuntime.ActorId actorId, boolean wasMember) {
            if (wasMember) {
                Long current = memberEpochs.get(actorId);
                if (current != null) return current;
            }
            // requireEpochRoom ran before physical membership admission.
            long epoch = ++lastEpoch;
            memberEpochs.put(actorId, epoch);
            return epoch;
        }
    }

    public final class Membership<M> implements AutoCloseable {
        private final Key key;
        private final ActorRuntime.ActorId actorId;
        private final ActorRuntime.ActorGroup group;
        private final long membershipEpoch;
        private final AtomicBoolean left = new AtomicBoolean();

        private Membership(
                Key key,
                ActorRuntime.ActorId actorId,
                ActorRuntime.ActorGroup group,
                long membershipEpoch) {
            this.key = key;
            this.actorId = actorId;
            this.group = group;
            this.membershipEpoch = membershipEpoch;
        }

        public String scope() { return key.scope(); }
        public String name() { return key.name(); }
        public ActorRuntime.ActorId actorId() { return actorId; }
        public boolean left() { return left.get(); }

        public void leave() {
            if (!left.compareAndSet(false, true)) return;
            try {
                ActorRuntime.ActorId current = ActorRuntime.currentActorId().orElseThrow(() ->
                        new SecurityException("pg membership leave requires actor execution"));
                if (!current.equals(actorId)) {
                    throw new SecurityException(
                            "pg membership may only be left by the actor that joined it");
                }
                leaveMembership(key, actorId, group, membershipEpoch);
            } catch (RuntimeException | Error failure) {
                // Unauthorized callers must not consume a legitimate actor's
                // revocable membership handle merely by invoking leave().
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

    /*
     * Registry mutation is intentionally serialized, but group finalization
     * is not part of that critical section. runtimeClose() can settle async
     * reads, invoke cleanup hooks, and reenter the registry from a callback.
     * Collect exact group incarnations under the lock, and close them after
     * releasing it (even on the exceptional path).
     */
    @FunctionalInterface
    private interface LockedOperation<T> {
        T run(List<Entry> retirements);
    }

    private <T> T withRegistryLock(LockedOperation<T> operation) {
        List<Entry> retirements = new ArrayList<>();
        T result = null;
        Throwable failure = null;
        synchronized (lock) {
            try {
                result = operation.run(retirements);
            } catch (RuntimeException | Error problem) {
                failure = problem;
            }
        }
        try {
            closeRetired(retirements);
        } catch (RuntimeException | Error cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
        }
        if (failure instanceof RuntimeException problem) throw problem;
        if (failure instanceof Error problem) throw problem;
        return result;
    }

    private static void closeRetired(List<Entry> retirements) {
        Throwable first = null;
        for (Entry entry : retirements) {
            if (entry.group.closed()) continue;
            try {
                entry.group.runtimeClose();
            } catch (RuntimeException | Error failure) {
                if (first == null) first = failure;
                else if (first != failure) first.addSuppressed(failure);
            }
        }
        if (first instanceof RuntimeException problem) throw problem;
        if (first instanceof Error problem) throw problem;
    }

    /** Must be called with registry lock held; no callbacks run here. */
    private void retireLocked(Key key, Entry entry, List<Entry> retirements) {
        if (groups.remove(key, entry) && !entry.group.closed()) {
            retirements.add(entry);
        }
    }

    public <M> Membership<M> joinCurrent(String scope, String name) {
        Key key = key(scope, name);
        ActorRuntime.ActorId actorId = ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("pg.joinCurrent requires actor execution"));
        return withRegistryLock(retirements -> {
            Entry entry = entryForJoinLocked(key, retirements);
            try {
                boolean wasMember = entry.group.runtimeContains(actorId);
                entry.requireEpochRoom(actorId, wasMember);
                entry.group.joinCurrent(entry.join);
                long epoch = entry.epochForJoin(actorId, wasMember);
                return new Membership<M>(key, actorId, entry.group, epoch);
            } catch (RuntimeException | Error failure) {
                reclaimIfEmptyLocked(key, entry, retirements);
                throw failure;
            }
        });
    }

    /**
     * Host/supervisor admission helper. Actor code may only pass its own ref,
     * because ActorGroup enforces self-only joining for actor callers.
     */
    public <M> void join(String scope, String name, ActorRuntime.ActorRef<M> ref) {
        Key key = key(scope, name);
        withRegistryLock(retirements -> {
            Entry entry = entryForJoinLocked(key, retirements);
            try {
                ActorRuntime.ActorId actorId = ref.id();
                boolean wasMember = entry.group.runtimeContains(actorId);
                entry.requireEpochRoom(actorId, wasMember);
                entry.group.join(ref, entry.join);
                entry.epochForJoin(actorId, wasMember);
            } catch (RuntimeException | Error failure) {
                reclaimIfEmptyLocked(key, entry, retirements);
                throw failure;
            }
            return null;
        });
    }

    public void leaveCurrent(String scope, String name) {
        Key key = key(scope, name);
        ActorRuntime.ActorId actorId = ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("pg.leaveCurrent requires actor execution"));
        Entry entry = withRegistryLock(retirements -> {
            Entry found = existingLocked(key, retirements);
            if (found == null) throw new IllegalStateException("unknown pg group");
            // Logical revocation precedes the callback-capable physical leave.
            // Even a cached duplicate handle cannot cancel a later epoch.
            found.memberEpochs.remove(actorId);
            return found;
        });
        // ActorGroup.leaveCurrent may settle Future callbacks; do not hold
        // our registry monitor while entering its membership teardown.
        try {
            entry.group.leaveCurrent();
        } finally {
            reclaimIfEmpty(key, entry);
        }
    }

    @SuppressWarnings("unchecked")
    public <M> List<ActorRuntime.ActorRef<M>> members(String scope, String name) {
        Key key = key(scope, name);
        return withRegistryLock(retirements -> {
            Entry entry = existingLocked(key, retirements);
            if (entry == null) return List.of();
            List<ActorRuntime.ActorRef<M>> refs = new ArrayList<>();
            for (ActorRuntime.ActorId id : entry.group.memberIds()) {
                ActorRuntime.ActorRef<?> ref = runtime.resolveLocalActorRef(id);
                if (ref != null && ref.isAlive()) {
                    refs.add((ActorRuntime.ActorRef<M>) ref);
                }
            }
            return List.copyOf(refs);
        });
    }

    public <M> List<ActorRuntime.ActorRef<M>> localMembers(String scope, String name) {
        return members(scope, name);
    }

    public int memberCount(String scope, String name) {
        Key key = key(scope, name);
        return withRegistryLock(retirements -> {
            Entry entry = existingLocked(key, retirements);
            return entry == null ? 0 : entry.group.memberCount();
        });
    }

    public boolean contains(String scope, String name, ActorRuntime.ActorRef<?> ref) {
        Key key = key(scope, name);
        return withRegistryLock(retirements -> {
            Entry entry = existingLocked(key, retirements);
            return entry != null && entry.group.contains(ref);
        });
    }

    /** Host/supervisor deletion does not cancel ordinary group members. */
    public void delete(String scope, String name) {
        requireHostManager("delete pg groups");
        Key key = key(scope, name);
        withRegistryLock(retirements -> {
            requireOpen();
            Entry entry = groups.get(key);
            if (entry != null) retireLocked(key, entry, retirements);
            return null;
        });
    }

    public List<String> groupNames(String scope) {
        String normalizedScope = normalize("scope", scope);
        return withRegistryLock(retirements -> {
            requireOpen();
            pruneEmptyLocked(retirements);
            List<String> names = new ArrayList<>();
            for (Map.Entry<Key, Entry> item : groups.entrySet()) {
                if (item.getKey().scope().equals(normalizedScope)
                        && !item.getValue().group.closed()) {
                    names.add(item.getKey().name());
                }
            }
            return List.copyOf(names);
        });
    }

    public int groupCount() {
        return withRegistryLock(retirements -> {
            requireOpen();
            pruneEmptyLocked(retirements);
            return groups.size();
        });
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
        closeRetired(snapshot);
    }

    /** Registry lock required. Cleanup goes into the caller's retirement list. */
    private Entry entryForJoinLocked(Key key, List<Entry> retirements) {
        requireOpen();
        pruneEmptyLocked(retirements);
        Entry existing = groups.get(key);
        if (existing != null) {
            existing.pruneEpochs();
            return existing;
        }
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
            // A partly constructed group is not published; still close only
            // after the registry lock is dropped.
            retirements.add(new Entry(group, null));
            throw failure;
        }
    }

    private Entry existingLocked(Key key, List<Entry> retirements) {
        requireOpen();
        Entry entry = groups.get(key);
        if (entry == null) return null;
        if (entry.group.closed() || entry.group.runtimeMemberCount() == 0) {
            retireLocked(key, entry, retirements);
            return null;
        }
        return entry;
    }

    private void leaveMembership(
            Key key,
            ActorRuntime.ActorId actorId,
            ActorRuntime.ActorGroup group,
            long membershipEpoch) {
        ActorRuntime.ActorId current = ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("pg membership leave requires actor execution"));
        if (!current.equals(actorId)) {
            throw new SecurityException(
                    "pg membership may only be left by the actor that joined it");
        }
        Entry entry = withRegistryLock(retirements -> {
            requireOpen();
            Entry found = groups.get(key);
            if (found == null || found.group != group || !group.runtimeContains(actorId))
                return null; // stale duplicate handle: explicitly idempotent
            if (!found.memberEpochs.remove(actorId, membershipEpoch))
                return null; // a newer join owns a different epoch
            return found;
        });
        if (entry == null) return;
        try {
            group.leaveCurrent();
        } finally {
            reclaimIfEmpty(key, entry);
        }
    }

    private void reclaimIfEmpty(Key key, Entry entry) {
        withRegistryLock(retirements -> {
            // Exact entry identity makes old leave/rejoin handles incapable
            // of retiring a fresh group with the same human-readable name.
            reclaimIfEmptyLocked(key, entry, retirements);
            return null;
        });
    }

    private void reclaimIfEmptyLocked(
            Key key, Entry entry, List<Entry> retirements) {
        if (entry.group.closed() || entry.group.runtimeMemberCount() == 0) {
            retireLocked(key, entry, retirements);
        }
    }

    private void pruneEmptyLocked(List<Entry> retirements) {
        var iterator = groups.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Entry> item = iterator.next();
            ActorRuntime.ActorGroup group = item.getValue().group;
            if (group.closed()) {
                iterator.remove();
            } else if (group.runtimeMemberCount() == 0) {
                iterator.remove();
                retirements.add(item.getValue());
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
