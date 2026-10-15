package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Opt-in, bounded diagnostic monitor for actor execution and explicitly instrumented accesses.
 *
 * <p>Never reads guest values or grants actor admission. The runtime calls enter/exit
 * at its existing physical lane boundary. Compiler/native memory probes must call
 * read/write on the actual backing-memory locations; without those probes this is
 * NOT a complete data-race detector and MUST NOT report a race-clean program.</p>
 *
 * <p>Uses a happens-before vector-clock model. Actor turn release/acquire is a
 * synchronization edge for the same actor incarnation. External synchronization
 * must use acquire/release; omitting real edges can produce false positives.
 * The same lock protects monitor metadata, but is NOT a synchronization edge
 * between guest threads. No writes to guest memory happen here.</p>
 */
public final class ActorRaceInstrumentation {
    private static final int MAX_LOCATIONS = 100_000;
    private static final int MAX_TRACKED_THREADS = 4_096;
    private static final int MAX_LIVE_ACTORS = 100_000;
    private static final int MAX_SYNC_DOMAINS = 100_000;
    private static final long MAX_ACCESS_EPOCHS = 1_000_000L;
    private static final ActorRaceInstrumentation RUNTIME = new ActorRaceInstrumentation();
    // The public test CLI sets ORES_RACE_REQUIRED=1 for its child backend.
    // Activation does not itself imply that all guest memory accesses are probed.
    private static final boolean ENABLED = Boolean.getBoolean("ores.test.race")
            || "1".equals(System.getenv("ORES_RACE_REQUIRED"));

    public record Snapshot(long accesses, long memoryRaces,
                           long actorChecks, long actorOverlapViolations,
                           long checkedCommitAttempts, long rejectedStaleCommits) { }

    private static final class Clock {
        private final IdentityHashMap<Thread, Long> ticks = new IdentityHashMap<>();
        private void merge(Clock other) {
            other.ticks.forEach((thread, time) -> ticks.merge(thread, time, Math::max));
        }
        private long time(Thread thread) { return ticks.getOrDefault(thread, 0L); }
        private void tick(Thread thread) {
            long current = time(thread);
            if (current == Long.MAX_VALUE) throw new IllegalStateException("race clock overflow");
            ticks.put(thread, current + 1);
        }
     }

    private static final class Location {
        private final IdentityHashMap<Thread, Long> reads = new IdentityHashMap<>();
        private final IdentityHashMap<Thread, Long> writes = new IdentityHashMap<>();
    }

    private static final class ActorLane {
        private Thread executing;
        private final Clock released = new Clock();
    }

    private final IdentityHashMap<Thread, Clock> clocks = new IdentityHashMap<>();
    private final IdentityHashMap<Object, ActorLane> lanes = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Map<String, Location>> memory = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Clock> synchronization = new IdentityHashMap<>();
    private int locations;
    private long trackedAccessEpochs;
    private long accesses;
    private long races;
    private long actorChecks;
    private long overlaps;
    private long checkedCommitAttempts;
    private long rejectedStaleCommits;

    public static boolean enabled() { return ENABLED; }
    public static ActorRaceInstrumentation runtime() { return RUNTIME; }

    /** Independent opt-in monitor for deterministic host-level conformance tests. */
    public static ActorRaceInstrumentation forTesting() {
        return new ActorRaceInstrumentation();
    }

    private Clock currentClock() {
        Thread thread = Thread.currentThread();
        Clock existing = clocks.get(thread);
        if (existing != null) return existing;
        if (clocks.size() == MAX_TRACKED_THREADS) {
            throw new IllegalStateException("race instrumentation thread limit exhausted");
        }
        Clock created = new Clock();
        clocks.put(thread, created);
        return created;
    }

    /** A host-owned actor key must be an incarnation-specific identity, not a name. */
    public synchronized void enterActor(Object actorKey) {
        Objects.requireNonNull(actorKey, "actor incarnation");
        actorChecks++;
        ActorLane lane = lanes.get(actorKey);
        if (lane == null) {
            if (lanes.size() == MAX_LIVE_ACTORS) {
                throw new IllegalStateException("race instrumentation actor limit exhausted");
            }
            lane = new ActorLane();
            lanes.put(actorKey, lane);
        }
        if (lane.executing != null) {
            overlaps++;
            throw new IllegalStateException("actor has overlapping physical execution");
        }
        Clock clock = currentClock();
        clock.merge(lane.released);
        clock.tick(Thread.currentThread());
        lane.executing = Thread.currentThread();
    }

    public synchronized void exitActor(Object actorKey) {
        Objects.requireNonNull(actorKey, "actor incarnation");
        actorChecks++;
        ActorLane lane = lanes.get(actorKey);
        if (lane == null || lane.executing != Thread.currentThread()) {
            overlaps++;
            throw new IllegalStateException("actor exited without its physical execution lease");
        }
        Clock clock = currentClock();
        clock.tick(Thread.currentThread());
        lane.released.merge(clock);
        lane.executing = null;
    }

    /** Retire a completed actor incarnation without retaining its identity forever. */
    public synchronized void retireActor(Object actorKey) {
        Objects.requireNonNull(actorKey, "actor incarnation");
        ActorLane lane = lanes.get(actorKey);
        if (lane != null && lane.executing != null) {
            overlaps++;
            throw new IllegalStateException("cannot retire an executing actor");
        }
        lanes.remove(actorKey);
    }

    /** Remove an abandoned instrumented guest memory domain after teardown. */
    public synchronized void retireMemoryDomain(Object memoryOwner) {
        Objects.requireNonNull(memoryOwner, "memory owner identity");
        Map<String, Location> fields = memory.remove(memoryOwner);
        if (fields != null) {
            locations -= fields.size();
            for (Location location : fields.values()) {
                trackedAccessEpochs -= location.reads.size() + location.writes.size();
            }
        }
    }

    /** Host synchronization release. Caller must guarantee real external synchronization. */
    public synchronized void release(Object synchronizationKey) {
        Objects.requireNonNull(synchronizationKey, "synchronization key");
        Clock clock = currentClock();
        clock.tick(Thread.currentThread());
        Clock published = synchronization.get(synchronizationKey);
        if (published == null) {
            if (synchronization.size() == MAX_SYNC_DOMAINS) {
                throw new IllegalStateException("race instrumentation synchronization limit exhausted");
            }
            published = new Clock();
            synchronization.put(synchronizationKey, published);
        }
        published.merge(clock);
    }

    /** Host synchronization acquire. Never infer this edge from a plain guest callback. */
    public synchronized void acquire(Object synchronizationKey) {
        Objects.requireNonNull(synchronizationKey, "synchronization key");
        Clock published = synchronization.get(synchronizationKey);
        Clock clock = currentClock();
        if (published != null) clock.merge(published);
        clock.tick(Thread.currentThread());
    }

    public void read(Object memoryOwner, String slot) { access(memoryOwner, slot, false); }
    public void write(Object memoryOwner, String slot) { access(memoryOwner, slot, true); }

    private synchronized void access(Object memoryOwner, String slot, boolean write) {
        Objects.requireNonNull(memoryOwner, "memory owner identity");
        Objects.requireNonNull(slot, "memory slot");
        Map<String, Location> fields = memory.computeIfAbsent(memoryOwner, ignored -> new HashMap<>());
        Location location = fields.get(slot);
        if (location == null) {
            if (locations == MAX_LOCATIONS) {
                throw new IllegalStateException("race instrumentation memory limit exhausted");
            }
            location = new Location();
            fields.put(slot, location);
            locations++;
        }
        Thread thread = Thread.currentThread();
        Clock clock = currentClock();
        clock.tick(thread);
        accesses++;
        boolean conflict = false;
        for (Map.Entry<Thread, Long> prior : location.writes.entrySet()) {
            if (prior.getKey() != thread && prior.getValue() > clock.time(prior.getKey())) {
                conflict = true;
            }
        }
        if (write) {
            for (Map.Entry<Thread, Long> prior : location.reads.entrySet()) {
                if (prior.getKey() != thread && prior.getValue() > clock.time(prior.getKey())) {
                    conflict = true;
                }
            }
        }
        boolean firstEpoch = write
                ? !location.writes.containsKey(thread)
                : !location.reads.containsKey(thread);
        if (firstEpoch && trackedAccessEpochs >= MAX_ACCESS_EPOCHS) {
            throw new IllegalStateException("race monitor access epoch capacity exceeded");
        }
        if (firstEpoch) trackedAccessEpochs++;
        if (conflict) races++;
        if (write) {
            location.writes.put(thread, clock.time(thread));
            // Retain concurrent reader epochs for subsequent writer comparisons.
        } else {
            location.reads.put(thread, clock.time(thread));
        }
    }

    /** Count atomic optimistic-commit outcomes without treating rejected
     * stale writes as actual stale state mutations. */
    public synchronized void observeCheckedCommit(boolean staleRejected) {
        checkedCommitAttempts++;
        if (staleRejected) rejectedStaleCommits++;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(accesses, races, actorChecks, overlaps,
                checkedCommitAttempts, rejectedStaleCommits);
    }
}
