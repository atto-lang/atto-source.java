package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime-only routing boundary for independent, configuration-versioned actor
 * generations. A supervisor owns staging, validation, and readiness; the router
 * only makes the serving-target handoff and admission pins atomic.
 *
 * <p>This is NOT a stateful actor migration mechanism. Routing leases must be
 * attached to the physical request/connection lifecycle, including suspended
 * work. It is unsafe to release one when only an observer Future settles. The
 * retirement owner must separately prove all actor, Task, FFI, native I/O and
 * code-image loans are quiescent before acknowledging retirement.</p>
 *
 * <p>No actor callbacks, destruction, or guest code execute under its lock.</p>
 */
public final class ActorRollingGenerationRoute<T> implements AutoCloseable {
    public record Snapshot(long logicalId, long activeEpoch, int liveGenerations,
                           int drainingGenerations, boolean closed) { }

    private enum Phase { ACTIVE, DRAINING }

    private final class Generation {
        final long epoch;
        final T target;
        Phase phase;
        int liveAdmissions;

        Generation(long epoch, T target) {
            this.epoch = epoch;
            this.target = target;
            this.phase = Phase.ACTIVE;
        }
    }

    /** A lease captures the exact target and epoch at the admission cutover. */
    public final class Lease implements AutoCloseable {
        private final Generation generation;
        private boolean released;

        private Lease(Generation generation) { this.generation = generation; }

        public T target() {
            synchronized (ActorRollingGenerationRoute.this) {
                if (released) throw new IllegalStateException("routing lease released");
                return generation.target;
            }
        }
        public long epoch() { return generation.epoch; }

        public boolean released() {
            synchronized (ActorRollingGenerationRoute.this) { return released; }
        }

        @Override public void close() {
            synchronized (ActorRollingGenerationRoute.this) {
                if (released) return;
                if (generation.liveAdmissions <= 0)
                    throw new IllegalStateException("generation admission pin underflow");
                generation.liveAdmissions--;
                released = true;
            }
        }
    }

    private final long logicalId;
    private final int maxLiveGenerations;
    private final Map<Long, Generation> generations = new LinkedHashMap<>();
    private Generation active;
    private boolean closed;

    public ActorRollingGenerationRoute(long logicalId, T initialReadyTarget,
                                       int maxLiveGenerations) {
        if (logicalId <= 0 || maxLiveGenerations < 2 || maxLiveGenerations > 1024)
            throw new IllegalArgumentException("invalid generation routing limits");
        this.logicalId = logicalId;
        this.maxLiveGenerations = maxLiveGenerations;
        this.active = new Generation(1L, Objects.requireNonNull(initialReadyTarget));
        generations.put(1L, active);
    }

    /**
     * Linearizes target lookup with admission. A caller must hold this lease
     * throughout the transport/actor work that depends on that target.
     */
    public synchronized Lease acquire() {
        requireOpen();
        if (active.liveAdmissions == Integer.MAX_VALUE)
            throw new IllegalStateException("generation admission pin limit exhausted");
        // Allocate before mutating the pin counter so allocation failure is safe.
        Lease lease = new Lease(active);
        active.liveAdmissions++;
        return lease;
    }

    /**
     * Publish a *previously validated and ready* physical actor target.
     * Returns false on a stale expected epoch without changing any route.
     * No old generation is stopped, and no existing admission is interrupted.
     * The supervisor MUST prove compatibility/readiness before calling this.
     */
    public synchronized boolean publishReady(long expectedEpoch, T readyTarget) {
        requireOpen();
        Objects.requireNonNull(readyTarget, "readyTarget");
        if (active.epoch != expectedEpoch) return false;
        if (generations.size() >= maxLiveGenerations)
            throw new IllegalStateException("live actor generation cap reached");
        if (active.epoch == Long.MAX_VALUE)
            throw new IllegalStateException("actor route epoch exhausted");
        for (Generation generation : generations.values()) {
            if (generation.target == readyTarget)
                throw new IllegalArgumentException(
                        "replacement must not reuse a retained physical actor");
        }
        Generation next = new Generation(active.epoch + 1L, readyTarget);
        // No code runs between the old->draining and new->active assignments.
        active.phase = Phase.DRAINING;
        generations.put(next.epoch, next);
        active = next;
        return true;
    }

    /** Used by trusted staging to avoid publishing an active or retained pool again. */
    synchronized boolean containsTarget(T target) {
        for (Generation generation : generations.values())
            if (generation.target == target) return true;
        return false;
    }

    /** Trusted retirement owner may inspect only a DRAINING target. */
    synchronized T drainingTarget(long epoch) {
        Generation generation = generations.get(epoch);
        return generation != null && generation.phase == Phase.DRAINING
                ? generation.target : null;
    }

    /** Traffic-level drain only; NOT proof that all physical loans retired. */
    public synchronized boolean admissionsDrained(long epoch) {
        Generation g = generations.get(epoch);
        return g != null && g.phase == Phase.DRAINING && g.liveAdmissions == 0;
    }

    /**
     * Fail closed until an authenticated generation/actor-specific retirement
     * receipt can be issued by physical Task, callback, native I/O, FFI,
     * arena and code-image lifetime authorities. Neither traffic completion
     * nor an actor's observer Future is such a receipt.
     *
     * <p>Do NOT remove the old generation from the registry based on a
     * CONTROL caller's assertion alone; this is an ABA/use-after-free risk.</p>
     */
    @Deprecated(forRemoval = true)
    public synchronized boolean acknowledgePhysicallyRetired(long epoch) {
        throw new IllegalStateException(
                "physical-retirement certificate unavailable: generation retained");
    }

    public synchronized List<Long> drainingEpochs() {
        List<Long> result = new ArrayList<>();
        for (Generation g : generations.values()) {
            if (g.phase == Phase.DRAINING) result.add(g.epoch);
        }
        return List.copyOf(result);
    }

    public synchronized Snapshot snapshot() {
        int draining = 0;
        for (Generation generation : generations.values())
            if (generation.phase == Phase.DRAINING) draining++;
        return new Snapshot(logicalId, active.epoch, generations.size(), draining, closed);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("generation route closed");
    }

    /** Fence new admission without prematurely destroying pinned targets. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        active.phase = Phase.DRAINING;
    }
}
