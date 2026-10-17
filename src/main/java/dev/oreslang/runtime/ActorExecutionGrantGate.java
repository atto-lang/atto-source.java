package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

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
 * physical owner platform Thread. A retired/stale ticket can never release a successor
 * ticket, even if the same carrier is reused. No monitor is held during guest
 * execution, suspension or callbacks.
 */
public final class ActorExecutionGrantGate {
    // A physical thread may hold exactly one actor's execution grant at once,
    // even when it tries to enter another actor through a synchronous callback.
    // This protects against cross-actor nested guest execution in the prototype.
    private static final ThreadLocal<ActorExecutionGrantGate.Grant> CARRIER_GRANT =
            new ThreadLocal<>();

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
    /** Retiring this physical gate does NOT complete actor logical-task teardown. */
    public synchronized boolean isPhysicalGateRetired() { return retired; }

    /** Returns null when the bounded gate is full or sealed (no blocking). */
    public synchronized Grant tryAcquire() {
        if (sealed || retired) return null;
        Thread worker = Thread.currentThread();
        if (worker.isVirtual()) {
            throw new IllegalStateException(
                    "actor physical execution grants require platform carrier threads");
        }
        if (CARRIER_GRANT.get() != null) {
            throw new IllegalStateException(
                    "a carrier cannot execute two actors or claim nested execution grants");
        }
        if (owners.containsKey(worker)) {
            throw new IllegalStateException(
                    "a carrier cannot claim a second independent grant for the same actor");
        }
        if (active.size() >= maxSlots) return null;
        if (lastSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("actor grant sequence exhausted; do not wrap");
        }
        Grant grant = new Grant(++lastSequence, worker);
        // Initialize carrier ownership before publishing a ticket. If
        // publication fails, the caller must never retain a half-admitted
        // permit (the monotonic sequence is intentionally not reused).
        CARRIER_GRANT.set(grant);
        try {
            active.put(grant.sequence, grant);
            owners.put(worker, grant);
        } catch (RuntimeException | Error failure) {
            active.remove(grant.sequence);
            owners.remove(worker);
            CARRIER_GRANT.remove();
            throw failure;
        }
        return grant;
    }

    /** Fail closed on forged, stale, foreign, duplicate or wrong-owner release. */
    public synchronized void release(Grant grant) {
        if (grant == null || grant.gate != this || grant.incarnation != incarnation
                || active.get(grant.sequence) != grant) {
            throw new SecurityException("stale or foreign actor execution grant");
        }
        if (grant.owner != Thread.currentThread() || CARRIER_GRANT.get() != grant) {
            throw new SecurityException("actor execution grant belongs to another carrier");
        }
        if (owners.get(grant.owner) != grant) {
            throw new SecurityException("actor grant ledger inconsistency");
        }
        active.remove(grant.sequence);
        owners.remove(grant.owner);
        CARRIER_GRANT.remove();
        notifyAll();
    }

    /**
     * Prevents new claims immediately. Existing segments may unwind; teardown
     * must separately wait for outstanding logical tasks and protected loans.
     */
    public synchronized void seal() {
        sealed = true;
    }

    /**
     * Await physical execution-grant drainage after admission is sealed.
     * This is not a lifecycle await: suspended logical tasks, pending mailbox
     * work, foreign callbacks and outstanding state loans must be checked by
     * the supervising runtime separately. Never call while holding a grant.
     *
     * @return true if all physical grants drained; false on timeout.
     */
    public synchronized boolean awaitPhysicalDrain(long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("negative timeout");
        if (!sealed) throw new IllegalStateException("seal actor admission before waiting");
        if (CARRIER_GRANT.get() != null) {
            throw new IllegalStateException("a carrier cannot wait while holding an actor execution grant");
        }
        long budgetNanos = unit.toNanos(timeout);
        long started = System.nanoTime();
        while (!active.isEmpty()) {
            long remaining = budgetNanos - (System.nanoTime() - started);
            if (remaining <= 0) return false;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return true;
    }

    /** True exactly once, only after *physical* grants have quiesced. */
    public synchronized boolean tryRetirePhysicalGate() {
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
