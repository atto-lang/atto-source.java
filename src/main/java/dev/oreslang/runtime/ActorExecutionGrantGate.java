package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Host-side foundation for the proposed v2 actor execution admission protocol.
 *
 * <p>This is NOT connected to {@link ActorRuntime} or guest-code dispatch.
 * The production runtime continues to enforce the v1 single-carrier invariant.
 * The host may construct an isolated gate to test bounded physical admission;
 * annotations and user code cannot mint actor execution authority.
 *
 * <p>All transitions are linearized under one private monitor. Grants bind an
 * actor incarnation, unique monotonically increasing grant sequence, and
 * physical owner Thread. A retired/stale ticket can never release a successor
 * ticket, even if the same carrier is reused. No monitor is held during guest
 * execution, suspension or callbacks.
 */
public final class ActorExecutionGrantGate {
    private final long incarnation;
    private final int maxSlots;
    private final Map<Long, Grant> active = new HashMap<>();
    private final IdentityHashMap<Thread, Grant> owners = new IdentityHashMap<>();
    private long lastSequence;
    private boolean sealed;
    private boolean retired;

    public ActorExecutionGrantGate(long incarnation, int maxSlots) {
        if (incarnation <= 0) {
            throw new IllegalArgumentException("actor incarnation must be positive");
        }
        if (maxSlots < 1) {
            throw new IllegalArgumentException("actor execution max slots must be positive");
        }
        this.incarnation = incarnation;
        this.maxSlots = maxSlots;
    }

    public synchronized int capacity() { return maxSlots; }
    public synchronized int activeSlots() { return active.size(); }
    public synchronized long incarnation() { return incarnation; }
    public synchronized boolean isSealed() { return sealed; }
    public synchronized boolean isRetired() { return retired; }

    /** Returns null when the bounded gate is full or sealed (no blocking). */
    public synchronized Grant tryAcquire() {
        if (sealed || retired) return null;
        Thread worker = Thread.currentThread();
        if (owners.containsKey(worker)) {
            throw new IllegalStateException(
                    "a carrier cannot claim a second independent grant for the same actor");
        }
        if (active.size() >= maxSlots) return null;
        if (lastSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("actor grant sequence exhausted; do not wrap");
        }
        Grant grant = new Grant(++lastSequence, worker);
        active.put(grant.sequence, grant);
        owners.put(worker, grant);
        return grant;
    }

    /** Fail closed on forged, stale, foreign, duplicate or wrong-owner release. */
    public synchronized void release(Grant grant) {
        if (grant == null || grant.gate != this || grant.incarnation != incarnation
                || active.get(grant.sequence) != grant) {
            throw new SecurityException("stale or foreign actor execution grant");
        }
        if (grant.owner != Thread.currentThread()) {
            throw new SecurityException("actor execution grant belongs to another carrier");
        }
        active.remove(grant.sequence);
        owners.remove(grant.owner);
    }

    /**
     * Prevents new claims immediately. Existing segments may unwind; teardown
     * must separately wait for outstanding logical tasks and protected loans.
     */
    public synchronized void seal() {
        sealed = true;
    }

    /** True exactly once, only after physical grants have quiesced. */
    public synchronized boolean tryRetire() {
        if (!sealed || retired || !active.isEmpty()) return false;
        retired = true;
        return true;
    }

    /**
     * A single physical admission: never persist across an await/yield.
     * Closing on a different thread is forbidden. Runtime consumers must not
     * expose this ticket to guest actors or to untrusted FFI.
     */
    public final class Grant implements AutoCloseable {
        private final ActorExecutionGrantGate gate = ActorExecutionGrantGate.this;
        private final long incarnation = ActorExecutionGrantGate.this.incarnation;
        private final long sequence;
        private final Thread owner;

        private Grant(long sequence, Thread owner) {
            this.sequence = sequence;
            this.owner = owner;
        }

        public long sequence() { return sequence; }
        public long incarnation() { return incarnation; }

        @Override public void close() { release(this); }
    }
}
