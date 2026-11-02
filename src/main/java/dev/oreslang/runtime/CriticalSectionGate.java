package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Host-only substrate for a future Oreslang {@code critical {}} lowering.
 *
 * <p>This is a logical capacity-one gate: a contending continuation is queued,
 * not parked on an OS thread. The gate does not execute guest code and does not
 * keep any JVM monitor while a protected callback runs. The source compiler
 * must separately prove the callback cannot suspend or escape state loans.
 * This class is NOT wired into source lowering or ActorRuntime.</p>
 *
 * <p>Each gate belongs to one actor generation. The scheduler supplies opaque,
 * identity-distinct continuation objects. A retired gate never admits a new
 * body; a body which already started must finish before quiescence.</p>
 */
public final class CriticalSectionGate {
    private enum State { WAITING, GRANTED, RUNNING, ENDED }

    /** Runtime-owned logical invocation permit; never tied to a carrier thread. */
    public final class Ticket {
        private final Object continuation;
        private final CompletableFuture<Ticket> ready = new CompletableFuture<>();
        private State state;
        private boolean cancellationRequested;
        private CancellationException terminalCancellation;

        private Ticket(Object continuation, State state) {
            this.continuation = continuation;
            this.state = state;
        }

        /** Observing this stage cannot grant a permit to another continuation. */
        public CompletionStage<Ticket> whenReady() { return ready.minimalCompletionStage(); }

        /**
         * Cancel an unstarted request. A running body cannot be preemptively
         * released: a supervisor must wait for its physical completion.
         */
        public boolean cancel() {
            Ticket successor = null;
            boolean changed;
            synchronized (CriticalSectionGate.this) {
                if (state == State.ENDED) return false;
                if (state == State.RUNNING) {
                    cancellationRequested = true;
                    return false;
                }
                changed = true;
                if (state == State.WAITING) {
                    waiters.remove(this);
                } else if (owner == this) {
                    owner = null;
                    successor = transfer();
                }
                state = State.ENDED;
                terminalCancellation = new CancellationException("critical admission cancelled");
                live.remove(continuation);
            }
            if (changed) ready.completeExceptionally(terminalCancellation);
            publish(successor);
            return true;
        }

        /**
         * Execute an already-admitted, statically non-suspending body. Physical
         * completion and FIFO handoff occur even when the callback traps.
         * A runtime cancellation request does not reclaim an executing body.
         */
        public <R> R execute(Supplier<R> body) {
            Objects.requireNonNull(body, "body");
            synchronized (CriticalSectionGate.this) {
                // A granted stage may already have completed before retirement
                // or cancellation. Delivery of that stage is NOT permission to
                // execute after the ticket has been revoked.
                if (terminalCancellation != null) throw terminalCancellation;
                if (retired) throw new CancellationException("critical gate retired before body entry");
                if (owner != this || state != State.GRANTED)
                    throw new IllegalStateException("critical body requires live admitted ticket");
                state = State.RUNNING;
            }
            try {
                return body.get();
            } finally {
                completePhysicalTurn();
            }
        }

        public void execute(Runnable body) {
            Objects.requireNonNull(body, "body");
            execute(() -> { body.run(); return null; });
        }

        public boolean cancellationRequested() {
            synchronized (CriticalSectionGate.this) { return cancellationRequested; }
        }

        private void completePhysicalTurn() {
            Ticket successor;
            synchronized (CriticalSectionGate.this) {
                if (state != State.RUNNING || owner != this)
                    throw new IllegalStateException("critical physical-turn state corrupted");
                state = State.ENDED;
                live.remove(continuation);
                owner = null;
                successor = transfer();
            }
            publish(successor);
        }
    }

    private final int capacity;
    private final long generation;
    private final ArrayDeque<Ticket> waiters = new ArrayDeque<>();
    private final IdentityHashMap<Object, Ticket> live = new IdentityHashMap<>();
    private Ticket owner;
    private boolean retired;

    public CriticalSectionGate(long generation, int maximumWaiters) {
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        if (maximumWaiters < 1 || maximumWaiters > 8192)
            throw new IllegalArgumentException("bounded waiter capacity must be 1..8192");
        this.generation = generation;
        this.capacity = maximumWaiters;
    }

    private void validate(Object continuation, long expectedGeneration) {
        Objects.requireNonNull(continuation, "scheduler continuation");
        if (expectedGeneration != generation)
            throw new IllegalStateException("stale critical gate generation");
        if (retired) throw new RejectedExecutionException("critical gate retired");
        if (live.containsKey(continuation))
            throw new IllegalStateException("recursive or duplicate critical continuation");
    }

    /** Immediate, nonqueueing acquisition; no thread is parked on a held gate. */
    public Optional<Ticket> tryEnter(Object continuation, long expectedGeneration) {
        Ticket ticket;
        synchronized (this) {
            validate(continuation, expectedGeneration);
            if (owner != null || !waiters.isEmpty()) return Optional.empty();
            ticket = new Ticket(continuation, State.GRANTED);
            owner = ticket;
            live.put(continuation, ticket);
        }
        ticket.ready.complete(ticket);
        return Optional.of(ticket);
    }

    /**
     * Schedule a continuation without running it. The returned stage becomes
     * ready after admission; the scheduler must invoke execute on its carrier.
     * No future callback ever runs inside the gate's monitor.
     */
    public Ticket enter(Object continuation, long expectedGeneration) {
        Ticket ticket;
        boolean acquired;
        synchronized (this) {
            validate(continuation, expectedGeneration);
            acquired = owner == null && waiters.isEmpty();
            if (!acquired && waiters.size() >= capacity)
                throw new RejectedExecutionException("critical waiter capacity exhausted");
            ticket = new Ticket(continuation, acquired ? State.GRANTED : State.WAITING);
            live.put(continuation, ticket);
            if (acquired) owner = ticket;
            else waiters.addLast(ticket);
        }
        if (acquired) ticket.ready.complete(ticket);
        return ticket;
    }

    /** Transfer an available logical permit under the gate monitor. */
    private Ticket transfer() {
        if (retired || owner != null || waiters.isEmpty()) return null;
        Ticket next = waiters.removeFirst();
        if (next.state != State.WAITING) throw new IllegalStateException("invalid FIFO successor");
        next.state = State.GRANTED;
        owner = next;
        return next;
    }

    private static void publish(Ticket ticket) {
        if (ticket != null) ticket.ready.complete(ticket);
    }

    /**
     * Revoke all pending continuations, but do not reclaim an executing body.
     * An already granted but unstarted owner is revoked immediately.
     */
    public void retire() {
        List<Ticket> rejected = new ArrayList<>();
        synchronized (this) {
            if (retired) return;
            retired = true;
            while (!waiters.isEmpty()) {
                Ticket pending = waiters.removeFirst();
                pending.state = State.ENDED;
                pending.terminalCancellation = new CancellationException("critical gate retired");
                live.remove(pending.continuation);
                rejected.add(pending);
            }
            if (owner != null && owner.state == State.GRANTED) {
                Ticket granted = owner;
                granted.state = State.ENDED;
                granted.terminalCancellation = new CancellationException("critical gate retired");
                live.remove(granted.continuation);
                owner = null;
                rejected.add(granted);
            } else if (owner != null) {
                owner.cancellationRequested = true;
            }
        }
        for (Ticket item : rejected)
            item.ready.completeExceptionally(item.terminalCancellation);
    }

    public synchronized int queued() { return waiters.size(); }
    public synchronized boolean retired() { return retired; }
    public synchronized boolean quiescent() { return retired && owner == null && waiters.isEmpty(); }
    public long generation() { return generation; }
}
