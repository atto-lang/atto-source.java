package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nonparking, bounded physical-turn admission for a future explicitly parallel actor.
 *
 * <p>This gate is a host-only scheduling primitive. It does NOT make the existing
 * ActorRuntime actor cell, source interpreter, heap, or Truffle context safe for
 * simultaneous execution. ActorRuntime remains single-flight until those components
 * are changed and proven thread-safe. Logical in-flight message limits and
 * ActorGroup-wide turn limits are separate.</p>
 *
 * <p>A contended acquisition returns null immediately. The owning dispatcher
 * must arrange a bounded wakeup rather than spin on an actor carrier.</p>
 */
public final class ActorPhysicalTurnCredits implements AutoCloseable {
    public static final int DEFAULT_MAX_TURNS = 1;
    public static final int MAX_SUPPORTED_TURNS = 3;
    private static final int CLOSED = 4;
    private static final int ACTIVE_MASK = 3;

    private final int maximum;
    private final AtomicInteger state = new AtomicInteger();

    public ActorPhysicalTurnCredits() { this(DEFAULT_MAX_TURNS); }

    public ActorPhysicalTurnCredits(int maximum) {
        if (maximum < 1 || maximum > MAX_SUPPORTED_TURNS)
            throw new IllegalArgumentException("actor physical turns must be between 1 and 3");
        this.maximum = maximum;
    }

    public int maximum() { return maximum; }
    public int active() { return state.get() & ACTIVE_MASK; }
    public boolean isClosed() { return (state.get() & CLOSED) != 0; }

    /**
     * Single-attempt CAS. A collision returns null rather than spinning on a
     * carrier; the eventual runtime integration owns retry notification.
     */
    public Permit tryAcquire() {
        int previous = state.get();
        if ((previous & CLOSED) != 0 || (previous & ACTIVE_MASK) >= maximum)
            return null;
        if (!state.compareAndSet(previous, previous + 1))
            return null;
        // Claiming a credit precedes allocating its host permit. If that
        // allocation fails (for example under memory pressure), restore the
        // credit before propagating the failure; otherwise the actor can be
        // left permanently saturated with no permit capable of releasing it.
        Permit acquired = null;
        try {
            acquired = new Permit();
            return acquired;
        } finally {
            if (acquired == null) state.getAndDecrement();
        }
    }

    /** Stop new admissions; already-issued permits must still be retired. */
    @Override public void close() {
        state.getAndUpdate(previous -> previous | CLOSED);
    }

    public final class Permit implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit() { }

        @Override public void close() {
            if (!released.compareAndSet(false, true)) return;
            int before = state.getAndDecrement();
            if ((before & ACTIVE_MASK) == 0)
                throw new IllegalStateException("physical actor turn credit underflow");
        }
    }
}
