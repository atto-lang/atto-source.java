package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Nonparking, incarnation-scoped physical carrier admission for actor guest
 * segments (receive, method, Task, and continuation). SOURCE HOST SUBSTRATE.
 * This class does not by itself wire compiler-produced guest turns to a pool.
 *
 * Worker membership is supplied by a trusted pool manager, not an actor.
 * A worker ID together with its generation identifies one carrier incarnation.
 * Receives have a separate logical admission gate; suspension releases this
 * physical lease, never the logical receive permit or a child Task census.
 */
public final class ActorSchedulingAdmissionV1 implements AutoCloseable {
    public enum Affinity { NONE, STICKY, PINNED, DEDICATED }
    public static final int POOL_SIZE = -1;

    private final long incarnation;
    private final int requestedMax;
    private final int actorLimit;
    private final int tenantLimit;
    private final int globalLimit;
    private final Affinity affinity;
    private final boolean dedicatedReserved;
    private final Map<Integer, Long> healthy = new HashMap<>();
    private final Set<Integer> occupiedLanes = new HashSet<>();
    private final Set<Lease> active = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private int homeLane = -1;
    private long homeGeneration;
    private long nextTicket;
    private int peak;
    private boolean closed;

    public ActorSchedulingAdmissionV1(long incarnation, int requestedMax,
            boolean serial, Affinity affinity, boolean dedicatedReserved,
            int actorLimit, int tenantLimit, int globalLimit) {
        if (incarnation <= 0 || (requestedMax != POOL_SIZE && requestedMax < 1)
                || actorLimit < 1 || tenantLimit < 1 || globalLimit < 1)
            throw new IllegalArgumentException("E_ACTOR_PARALLELISM_CAP");
        this.affinity = Objects.requireNonNull(affinity);
        if (serial && requestedMax != 1) throw new IllegalArgumentException("E_SERIAL_PARALLELISM");
        if ((affinity == Affinity.PINNED || affinity == Affinity.DEDICATED) && requestedMax != 1)
            throw new IllegalArgumentException("E_AFFINITY_REQUIRES_ONE");
        this.incarnation = incarnation;
        this.requestedMax = requestedMax;
        this.dedicatedReserved = dedicatedReserved;
        this.actorLimit = actorLimit;
        this.tenantLimit = tenantLimit;
        this.globalLimit = globalLimit;
    }

    /** Trusted pool inventory snapshot. Outstanding leases drain across a shrink. */
    public synchronized void updateHealthyWorkers(Map<Integer, Long> workers) {
        if (closed) throw new IllegalStateException("actor admission retired");
        Objects.requireNonNull(workers);
        Map<Integer, Long> next = new HashMap<>();
        for (var entry : workers.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 0
                    || entry.getValue() == null || entry.getValue() <= 0)
                throw new IllegalArgumentException("invalid worker generation");
            next.put(entry.getKey(), entry.getValue());
        }
        healthy.clear();
        healthy.putAll(next);
        // PINNED never migrates while an old lease is physically outstanding.
        // A replacement lane is selected only by a new successful admission.
        if (active.isEmpty() && !Objects.equals(healthy.get(homeLane), homeGeneration)) {
            homeLane = -1;
            homeGeneration = 0;
        }
    }

    public synchronized int maximumNow() {
        int declared = requestedMax == POOL_SIZE ? healthy.size() : requestedMax;
        return Math.min(Math.min(declared, healthy.size()),
                Math.min(actorLimit, Math.min(tenantLimit, globalLimit)));
    }
    public synchronized int running() { return active.size(); }
    public synchronized int peak() { return peak; }
    public synchronized int homeLane() { return homeLane; } // placement hint only
    public synchronized boolean isClosed() { return closed; }

    /**
     * Nonblocking exact-worker admission. The caller must be the trusted
     * scheduler; acquiring this lease never grants actor-state authority.
     */
    public synchronized Lease tryEnter(long actorIncarnation, int lane, long workerGeneration) {
        if (actorIncarnation != incarnation) throw new SecurityException("stale actor incarnation");
        if (closed) return null;
        if (workerGeneration <= 0 || !Objects.equals(healthy.get(lane), workerGeneration)) return null;
        if (active.size() >= maximumNow() || occupiedLanes.contains(lane)) return null;
        if (affinity == Affinity.DEDICATED && !dedicatedReserved) return null;
        if ((affinity == Affinity.PINNED || affinity == Affinity.DEDICATED)
                && homeLane >= 0 && (homeLane != lane || homeGeneration != workerGeneration)) {
            // Only a genuinely retired worker can be replaced, and only after
            // all old physical leases drained; never repin because of load.
            if (!active.isEmpty() || Objects.equals(healthy.get(homeLane), homeGeneration))
                return null;
        }
        if (nextTicket == Long.MAX_VALUE) throw new IllegalStateException("physical lease ticket overflow");
        Lease lease = new Lease(++nextTicket, lane, workerGeneration);
        active.add(lease);
        occupiedLanes.add(lane);
        peak = Math.max(peak, active.size());
        if (homeLane < 0 || affinity == Affinity.PINNED || affinity == Affinity.DEDICATED) {
            homeLane = lane;
            homeGeneration = workerGeneration;
        }
        return lease;
    }

    public final class Lease implements AutoCloseable {
        private final long ticket;
        private final int lane;
        private final long generation;
        private boolean retired;
        private Lease(long ticket, int lane, long generation) {
            this.ticket = ticket;
            this.lane = lane;
            this.generation = generation;
        }
        public long ticket() { return ticket; }
        public int lane() { return lane; }
        public long workerGeneration() { return generation; }
        @Override public void close() {
            synchronized (ActorSchedulingAdmissionV1.this) {
                if (retired) return;
                retired = true;
                if (!active.remove(this)) throw new IllegalStateException("foreign actor lease");
                if (!occupiedLanes.remove(lane)) throw new IllegalStateException("physical lane underflow");
                if (active.isEmpty() && !Objects.equals(healthy.get(homeLane), homeGeneration)) {
                    homeLane = -1;
                    homeGeneration = 0;
                }
            }
        }
    }

    /** STOP closes new guest admission, but does not free a running lease. */
    @Override public synchronized void close() {
        closed = true;
        healthy.clear();
    }
}
