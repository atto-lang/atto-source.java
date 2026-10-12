package dev.oreslang.runtime.accel;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opaque device-buffer ownership/fence bookkeeping for future GPU providers.
 * A descriptor is NOT device allocation; only an external trusted backend can
 * allocate/copy/dispatch. No raw GPU pointer or host fallback is exposed.
 */
public final class GpuBufferFence implements AutoCloseable {
    public enum Status { IDLE, IN_FLIGHT, POISONED, CLOSED }
    public record Identity(String tenant, String device, long allocationGeneration) {
        public Identity {
            if (tenant == null || tenant.isBlank() || device == null || device.isBlank()
                    || allocationGeneration < 0) throw new IllegalArgumentException("invalid device identity");
        }
    }

    /** Ties one submission to a precise buffer generation and sequence. */
    public record Ticket(long generation, long sequence) { }

    private static final AtomicLong NEXT_RANK = new AtomicLong();
    private static final class State {
        final long rank;
        final Identity identity;
        final long bytes;
        Status status = Status.IDLE;
        boolean backendQuiescent = true;
        long sequence;
        State(Identity identity, long bytes) {
            rank = NEXT_RANK.incrementAndGet();
            if (rank <= 0) throw new IllegalStateException("device rank exhausted");
            this.identity = Objects.requireNonNull(identity);
            if (bytes <= 0) throw new IllegalArgumentException("device byte size must be positive");
            this.bytes = bytes;
        }
    }

    private final State state;
    private boolean moved;

    public GpuBufferFence(Identity identity, long bytes) { this(new State(identity, bytes)); }
    private GpuBufferFence(State state) { this.state = state; }

    private void active() {
        if (moved || state.status == Status.CLOSED) {
            throw new IllegalStateException("device ownership moved or buffer closed");
        }
    }
    public synchronized Identity identity() {
        synchronized (state) { active(); return state.identity; 
        }
    }
    public synchronized long byteLength() {
        synchronized (state) { active(); return state.bytes; 
        }
    }
    public synchronized Status status() {
        synchronized (state) { active(); return state.status; 
        }
    }

    /** Immutable, per-buffer snapshot for typed DOP placement preflight only. */
    public record Snapshot(Identity identity, long byteLength, Status status) { }
    public synchronized Snapshot snapshot() {
        synchronized (state) {
        active();
        return new Snapshot(state.identity, state.bytes, state.status);
    
        }
    }

    public synchronized GpuBufferFence take() {
        synchronized (state) {
        active();
        if (state.status != Status.IDLE) {
            throw new IllegalStateException("cannot transfer device buffer with pending/failed fence");
        }
        var successor = new GpuBufferFence(state);
        moved = true;
        return successor;
    
        }
    }

    public synchronized Ticket beginCommand(Identity caller) {
        synchronized (state) {
        active();
        if (!state.identity.equals(caller)) throw new SecurityException("foreign device/tenant/generation");
        if (state.status != Status.IDLE) throw new IllegalStateException("device buffer not idle");
        if (state.sequence == Long.MAX_VALUE) throw new IllegalStateException("device fence sequence overflow");
        var ticket = new Ticket(state.identity.allocationGeneration(), state.sequence + 1);
        ++state.sequence;
        state.backendQuiescent = false;
        state.status = Status.IN_FLIGHT;
        return ticket;
    
        }
    }

    /** Called ONLY after the device reports completion for this exact ticket. */
    public synchronized void complete(Ticket ticket, boolean success) {
        synchronized (state) {
        active();
        if (state.status != Status.IN_FLIGHT || ticket == null
                || ticket.generation() != state.identity.allocationGeneration()
                || ticket.sequence() != state.sequence) {
            throw new IllegalStateException("stale or mismatched device event");
        }
        state.status = success ? Status.IDLE : Status.POISONED;
        state.backendQuiescent = true;
    
        }
    }

    /** Cancel requires device synchronization; do not reuse or free prematurely. */
    public synchronized void cancelPending(Ticket ticket) {
        synchronized (state) {
        active();
        if (state.status != Status.IN_FLIGHT || ticket == null
                || ticket.generation() != state.identity.allocationGeneration()
                || ticket.sequence() != state.sequence) {
            throw new IllegalStateException("stale cancellation");
        }
        state.status = Status.POISONED;
    
        }
    }

    /** A cancelled kernel may still be running until its device fence completes. */
    public synchronized void acknowledgeCancelledDeviceEvent(Ticket ticket, Identity caller) {
        synchronized (state) {
        active();
        if (!state.identity.equals(caller) || ticket == null
                || ticket.generation() != state.identity.allocationGeneration()
                || ticket.sequence() != state.sequence || state.status != Status.POISONED
                || state.backendQuiescent) {
            throw new IllegalStateException("invalid cancelled-device completion");
        }
        state.backendQuiescent = true;
    
        }
    }

    /** After backend confirms device quiescence and releases the opaque handle. */
    public synchronized void retireAfterBackendRelease(Identity caller) {
        synchronized (state) {
        active();
        if (!state.identity.equals(caller)) throw new SecurityException("foreign device/tenant/generation");
        if (state.status == Status.IN_FLIGHT || !state.backendQuiescent)
            throw new IllegalStateException("device command still in flight or awaiting cancelled fence");
        state.status = Status.CLOSED;
    
        }
    }

    @Override public synchronized void close() {
        synchronized (state) {
        if (moved) return; // No ownership remains on the transferred wrapper.
        if (state.status != Status.CLOSED) {
            throw new IllegalStateException("backend must quiesce and release before owner closes");
        }
    
        }
    }
    /** Atomic paired admission for trusted device providers (not authorization). */
    public record PairTickets(Identity input, Ticket inputTicket,
                              Identity output, Ticket outputTicket) { }

    public static PairTickets claimPair(
            GpuBufferFence input, GpuBufferFence output, long expectedBytes) {
        Objects.requireNonNull(input, "input owner");
        Objects.requireNonNull(output, "output owner");
        if (input == output || input.state == output.state || expectedBytes <= 0)
            throw new IllegalArgumentException("two distinct nonempty device buffers required");
        State a = input.state, b = output.state;
        State first = a.rank < b.rank ? a : b;
        State second = a.rank < b.rank ? b : a;
        synchronized (first) {
            synchronized (second) {
                input.active();
                output.active();
                if (a.status != Status.IDLE || b.status != Status.IDLE
                        || !a.backendQuiescent || !b.backendQuiescent)
                    throw new IllegalStateException("device buffer already claimed or poisoned");
                if (a.bytes != expectedBytes || b.bytes != expectedBytes)
                    throw new IllegalArgumentException("device bytes differ from DOP layout");
                if (a.identity.allocationGeneration() <= 0
                        || b.identity.allocationGeneration() <= 0
                        || a.identity.allocationGeneration() == b.identity.allocationGeneration()
                        || !a.identity.tenant().equals(b.identity.tenant())
                        || !a.identity.device().equals(b.identity.device()))
                    throw new IllegalArgumentException("foreign/aliased GPU allocations");
                if (a.sequence == Long.MAX_VALUE || b.sequence == Long.MAX_VALUE)
                    throw new IllegalStateException("device ticket sequence exhausted");
                // No mutations occur until all preconditions and allocations succeed.
                var tickets = new PairTickets(a.identity,
                        new Ticket(a.identity.allocationGeneration(), a.sequence + 1),
                        b.identity,
                        new Ticket(b.identity.allocationGeneration(), b.sequence + 1));
                ++a.sequence;
                ++b.sequence;
                a.backendQuiescent = b.backendQuiescent = false;
                a.status = b.status = Status.IN_FLIGHT;
                return tickets;
            }
        }
    }

}
