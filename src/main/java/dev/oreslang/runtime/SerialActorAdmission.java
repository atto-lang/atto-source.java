package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A single logical lifetime lane shared by HTTP requests and actor messages.
 *
 * This is trusted transport/scheduler infrastructure, NOT a guest API.
 * Only one handler lifetime (RUNNING or SUSPENDED) can be admitted at once.
 * Await releases the physical carrier, never the lifetime reservation.
 * The current source HTTP adapter does not yet attach to this gate.
 */
public final class SerialActorAdmission implements AutoCloseable {
    public enum Kind { HTTP, MESSAGE }
    public enum State { RUNNING, SUSPENDED, FINISHED }

    private final Object lock = new Object();
    private final ArrayDeque<Waiter> pending = new ArrayDeque<>();
    private final int capacity;
    private long nextGeneration;
    private boolean closed;
    private Lease active;

    public SerialActorAdmission(int pendingCapacity) {
        if (pendingCapacity < 1 || pendingCapacity > 4096) {
            throw new IllegalArgumentException("serial actor pending capacity must be 1..4096");
        }
        capacity = pendingCapacity;
    }

    private record Waiter(Kind kind, CompletableFuture<Lease> future) { }

    public final class Lease implements AutoCloseable {
        private final Kind kind;
        private final long generation;
        private State state = State.RUNNING;
        private Lease(Kind kind, long generation) {
            this.kind = kind;
            this.generation = generation;
        }
        public Kind kind() { return kind; }
        public long generation() { return generation; }

        private void requireCurrent() {
            if (closed || active != this || state == State.FINISHED) {
                throw new IllegalStateException("stale serial actor admission lease");
            }
        }
        public State state() {
            synchronized (lock) { return state; }
        }
        public void suspend() {
            synchronized (lock) {
                requireCurrent();
                if (state != State.RUNNING) {
                    throw new IllegalStateException("serial handler is not running");
                }
                state = State.SUSPENDED;
            }
        }
        public void resume() {
            synchronized (lock) {
                requireCurrent();
                if (state != State.SUSPENDED) {
                    throw new IllegalStateException("serial handler is not suspended");
                }
                state = State.RUNNING;
            }
        }
        /** Call once after normal completion, cancellation or supervised unwind. */
        @Override public void close() { finish(this); }
    }

    /** Non-blocking FIFO admission, safe for callbacks on pooled native carriers. */
    public CompletableFuture<Lease> submit(Kind kind) {
        Objects.requireNonNull(kind, "handler kind");
        CompletableFuture<Lease> future = new CompletableFuture<>();
        Lease now = null;
        Waiter waiter = null;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("serial actor admission is closed");
            }
            if (active == null && pending.isEmpty()) {
                now = newLease(kind);
                active = now;
            } else {
                if (pending.size() >= capacity) {
                    throw new IllegalStateException("serial actor admission queue full");
                }
                waiter = new Waiter(kind, future);
                pending.addLast(waiter);
            }
        }
        if (waiter != null) {
            // Canceled pending admissions must not exhaust the bounded queue.
            Waiter queued = waiter;
            future.whenComplete((ignored, error) -> {
                if (future.isCancelled()) {
                    synchronized (lock) { pending.remove(queued); }
                }
            });
        }
        if (now != null) future.complete(now); // Never run callbacks under lock.
        return future;
    }

    private Lease newLease(Kind kind) {
        if (nextGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("serial actor generation exhausted");
        }
        return new Lease(kind, ++nextGeneration);
    }

    private void finish(Lease completed) {
        CompletableFuture<Lease> toWake = null;
        Lease next = null;
        synchronized (lock) {
            completed.requireCurrent();
            completed.state = State.FINISHED;
            active = null;
            if (!closed) {
                while (!pending.isEmpty()) {
                    Waiter entry = pending.removeFirst();
                    if (entry.future().isDone()) continue;
                    next = newLease(entry.kind());
                    active = next;
                    toWake = entry.future();
                    break;
                }
            }
        }
        // Never wake a guest continuation inside the admission bookkeeping lock.
        while (toWake != null && !toWake.complete(next)) {
            // Canceled before completion raced promotion: retire and advance.
            synchronized (lock) {
                if (active == next) {
                    next.state = State.FINISHED;
                    active = null;
                }
                toWake = null;
                next = null;
                if (!closed) {
                    while (!pending.isEmpty()) {
                        Waiter entry = pending.removeFirst();
                        if (entry.future().isDone()) continue;
                        next = newLease(entry.kind());
                        active = next;
                        toWake = entry.future();
                        break;
                    }
                }
            }
        }
    }

    public boolean busy() {
        synchronized (lock) { return active != null; }
    }

    public int queued() {
        synchronized (lock) { return pending.size(); }
    }

    @Override public void close() {
        List<CompletableFuture<Lease>> reject = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (active != null) {
                active.state = State.FINISHED;
                active = null;
            }
            while (!pending.isEmpty()) reject.add(pending.removeFirst().future());
        }
        for (CompletableFuture<Lease> future : reject) {
            future.completeExceptionally(
                    new IllegalStateException("serial actor stopped before admission"));
        }
    }
}
