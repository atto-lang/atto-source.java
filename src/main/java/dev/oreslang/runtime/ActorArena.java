package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Actor-confined native scalar arena, backed by bounded FFM regions.
 *
 * Persistent slots survive mailbox turns. A scratch turn recycles native slots
 * by generation-checked drop before the caller yields/suspends. No raw
 * MemorySegment escapes. Java wrappers remain JVM GC managed.
 *
 * This first host API is i64-only: arbitrary source structs/classes and
 * automatic suspension/cancellation unwinding are not implemented.
 */
public final class ActorArena implements AutoCloseable {
    private final ActorRuntime.ActorMemorySlice owner;
    private final ExplicitNativeI64Region persistent;
    private final ExplicitNativeI64Region scratch;
    private volatile ScratchTurn activeTurn;

    private ActorArena(ActorRuntime.ActorMemorySlice owner,
                       ExplicitNativeI64Region persistent,
                       ExplicitNativeI64Region scratch) {
        this.owner = owner;
        this.persistent = persistent;
        this.scratch = scratch;
    }

    static ActorArena open(ActorRuntime.ActorMemorySlice owner,
                           int persistentSlots, int scratchSlots) {
        Objects.requireNonNull(owner, "actor memory");
        if (persistentSlots < 1 || persistentSlots > 65536
                || scratchSlots < 1 || scratchSlots > 65536) {
            throw new IllegalArgumentException("actor arena slots must be 1..65536 per region");
        }
        ExplicitNativeI64Region durable = owner.newExplicitNativeI64Region(persistentSlots);
        try {
            var ephemeral = owner.newExplicitNativeI64Region(scratchSlots);
            return new ActorArena(owner, durable, ephemeral);
        } catch (RuntimeException | Error failure) {
            try { durable.close(); }
            catch (RuntimeException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    /** Slots are opaque, owner-checked, generation-checked native handles. */
    public ExplicitNativeI64Region.Slot allocatePersistent(long value) {
        requireOwner();
        return persistent.allocate(value);
    }

    public long readPersistent(ExplicitNativeI64Region.Slot slot) {
        requireOwner();
        return persistent.read(slot);
    }

    public void writePersistent(ExplicitNativeI64Region.Slot slot, long value) {
        requireOwner();
        persistent.write(slot, value);
    }

    public ExplicitNativeI64Region.Loan borrowPersistent(ExplicitNativeI64Region.Slot slot) {
        requireOwner();
        return persistent.borrow(slot);
    }

    /**
     * Register lifetime-only authority for work that may complete after this
     * actor turn. The returned pin cannot dereference arena memory.
     */
    public ExplicitNativeI64Region.LifetimePin pinExternal() {
        requireOwner();
        return persistent.pinLifetime();
    }

    /**
     * Register an asynchronous completion before yielding the actor turn and
     * release its lifetime pin exactly once when that completion settles.
     */
    public <T> CompletableFuture<T> trackExternalCompletion(CompletableFuture<T> future) {
        Objects.requireNonNull(future, "future");
        var pin = pinExternal();
        try {
            return future.whenComplete((value, failure) -> pin.close());
        } catch (RuntimeException | Error failure) {
            pin.close();
            throw failure;
        }
    }

    public void dropPersistent(ExplicitNativeI64Region.Slot slot) {
        requireOwner();
        persistent.drop(slot);
    }

    /** A scope must be closed before returning/yielding from the actor turn. */
    public synchronized ScratchTurn beginTurn() {
        requireOwner();
        if (closed()) throw new IllegalStateException("actor arena retired");
        if (activeTurn != null) throw new IllegalStateException("actor scratch turn already active");
        ScratchTurn turn = new ScratchTurn();
        activeTurn = turn;
        return turn;
    }

    public final class ScratchTurn implements AutoCloseable {
        private final ArrayDeque<ExplicitNativeI64Region.Slot> slots = new ArrayDeque<>();
        private volatile boolean done;

        private ScratchTurn() { }

        private void requireActive() {
            requireOwner();
            if (done || activeTurn != this || closed()) {
                throw new IllegalStateException("stale or inactive actor scratch turn");
            }
        }

        public synchronized ExplicitNativeI64Region.Slot allocate(long value) {
            requireActive();
            var slot = scratch.allocate(value);
            slots.push(slot);
            return slot;
        }

        public synchronized long read(ExplicitNativeI64Region.Slot slot) {
            requireActive();
            return scratch.read(slot);
        }

        public synchronized void write(ExplicitNativeI64Region.Slot slot, long value) {
            requireActive();
            scratch.write(slot, value);
        }

        /** Reverse-order deterministic scratch reclamation; no scratch borrows. */
        @Override public synchronized void close() {
            if (done) return;
            requireActive();
            while (!slots.isEmpty()) {
                scratch.drop(slots.peek());
                slots.pop(); // Keep slot if drop fails so cleanup can be retried.
            }
            done = true;
            activeTurn = null;
        }

        public boolean done() { return done; }
    }

    private void requireOwner() {
        // No temporary MemoryReservation object on the hot read/write path.
        owner.requireOwnerActive();
    }

    boolean hasActiveScratchTurn() { return activeTurn != null; }

    public boolean closed() { return persistent.closed() || scratch.closed(); }
    public boolean physicallyClosed() {
        return persistent.physicallyClosed() && scratch.physicallyClosed();
    }
    public int outstandingLoans() { return persistent.activeLoans(); }
    public int outstandingExternalPins() { return persistent.activeLifetimePins(); }

    @Override public synchronized void close() {
        requireOwner();
        if (activeTurn != null) {
            throw new IllegalStateException("close the scratch turn before actor arena retirement");
        }
        // Revoke both FFM regions even if the first physical close fails.
        // Failed native release is retryable and must not be misreported as
        // completed; do not unregister this arena on incomplete cleanup.
        Throwable failure = null;
        try { scratch.close(); }
        catch (RuntimeException | Error error) { failure = error; }
        try { persistent.close(); }
        catch (RuntimeException | Error error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        owner.unregisterArena(this);
    }
}
