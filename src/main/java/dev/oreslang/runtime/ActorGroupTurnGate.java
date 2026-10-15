package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Bounded nonparking physical-turn admission. This gate is deliberately NOT an
 * Executor, actor mailbox, OS-thread pool or group membership authority.
 *
 * <p>The only action taken on a granted permit is the caller's submission to
 * its existing domain-owned dispatcher. In particular, the callback MUST NOT
 * run guest actor code synchronously. A permit remains held until after the
 * entire physical turn has exited its host/Truffle boundary, not merely until
 * an actor's source continuation suspends.
 *
 * <p>Waiting permits and admission state are serialized under one monitor.
 * Callbacks run outside it. The queue is bounded by the owning ActorGroup's
 * maximum managed actor count. One actor scheduling ticket corresponds to at
 * most one gate ticket; cancellations are idempotent.
 */
final class ActorGroupTurnGate implements AutoCloseable {
    private enum State { WAITING, GRANTED, CANCELLED, RELEASED }

    private final Object lock = new Object();
    private final int maxParallelTurns;
    private final int maxQueuedTurns;
    private final ArrayDeque<Ticket> waiting = new ArrayDeque<>();
    private int grantedTurns;
    private boolean closed;

    ActorGroupTurnGate(int maxParallelTurns, int maxQueuedTurns) {
        if (maxParallelTurns <= 0 || maxQueuedTurns <= 0) {
            throw new IllegalArgumentException("turn and queue capacity must be positive");
        }
        this.maxParallelTurns = maxParallelTurns;
        this.maxQueuedTurns = maxQueuedTurns;
    }

    /**
     * Return a ticket and submit work immediately or enqueue it in FIFO order.
     * A full queue fails before any queued reservation is published.
     */
    Ticket submit(Consumer<Ticket> submitToDispatcher, Runnable onCancelled) {
        Objects.requireNonNull(submitToDispatcher, "submitToDispatcher");
        Objects.requireNonNull(onCancelled, "onCancelled");
        Ticket ticket = new Ticket(submitToDispatcher, onCancelled);
        boolean granted;
        synchronized (lock) {
            if (closed) throw new RejectedExecutionException("ActorGroup turn gate closed");
            if (grantedTurns < maxParallelTurns && waiting.isEmpty()) {
                grantedTurns++;
                ticket.state = State.GRANTED;
                granted = true;
            } else {
                if (waiting.size() >= maxQueuedTurns) {
                    throw new RejectedExecutionException("ActorGroup turn gate queue exhausted");
                }
                waiting.addLast(ticket);
                granted = false;
            }
        }
        if (granted) dispatch(ticket);
        return ticket;
    }

    int activeTurns() {
        synchronized (lock) { return grantedTurns; }
    }

    int queuedTurns() {
        synchronized (lock) { return waiting.size(); }
    }

    boolean isClosed() {
        synchronized (lock) { return closed; }
    }

    /**
     * Stop accepting work and withdraw queued tickets; already granted
     * physical turns finish and return their permits independently.
     */
    @Override public void close() {
        List<Ticket> cancelled = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            closed = true;
            while (!waiting.isEmpty()) {
                Ticket ticket = waiting.removeFirst();
                ticket.state = State.CANCELLED;
                cancelled.add(ticket);
            }
        }
        Throwable callbackFailure = null;
        for (Ticket ticket : cancelled) {
            try {
                ticket.onCancelled.run();
            } catch (RuntimeException | Error failure) {
                // One broken observer cannot strand other cancelled turns.
                if (callbackFailure == null) callbackFailure = failure;
                else callbackFailure.addSuppressed(failure);
            }
        }
        if (callbackFailure instanceof Error fatal) throw fatal;
        if (callbackFailure instanceof RuntimeException failure) throw failure;
    }

    private void dispatch(Ticket ticket) {
        try {
            ticket.submitToDispatcher.accept(ticket);
        } catch (RuntimeException | Error failure) {
            // A rejected downstream dispatcher cannot strand the group permit.
            ticket.release();
            throw failure;
        }
    }

    final class Ticket {
        private final Consumer<Ticket> submitToDispatcher;
        private final Runnable onCancelled;
        // All transitions protected by ActorGroupTurnGate.lock.
        private State state = State.WAITING;

        private Ticket(Consumer<Ticket> submitToDispatcher, Runnable onCancelled) {
            this.submitToDispatcher = submitToDispatcher;
            this.onCancelled = onCancelled;
        }

        /**
         * Returns true only if queued work was actually withdrawn. A granted
         * turn cannot be revoked here; actor cancellation still fences entry.
         */
        boolean cancelWaiting() {
            synchronized (lock) {
                if (state != State.WAITING) return false;
                if (!waiting.remove(this)) {
                    throw new IllegalStateException("queued turn ticket missing");
                }
                state = State.CANCELLED;
            }
            onCancelled.run();
            return true;
        }

        /**
         * Idempotent release after a full physical turn or failed dispatch.
         * Never runs while the actor lifecycle monitor is held.
         */
        boolean release() {
            Ticket next = null;
            synchronized (lock) {
                if (state != State.GRANTED) return false;
                state = State.RELEASED;
                if (--grantedTurns < 0) {
                    throw new IllegalStateException("ActorGroup physical-turn accounting underflow");
                }
                if (!closed && !waiting.isEmpty()) {
                    next = waiting.removeFirst();
                    next.state = State.GRANTED;
                    grantedTurns++;
                }
            }
            if (next != null) dispatch(next);
            return true;
        }
    }
}
