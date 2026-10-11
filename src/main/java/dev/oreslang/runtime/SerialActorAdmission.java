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
    /** Guards iterative delivery when CompletableFuture callbacks finish inline. */
    private boolean delivering;
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
        private final SerialActorAdmission issuer = SerialActorAdmission.this;
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
        /** A transport adapter must call this before guest execution. A
         * suspended lease owns admission but is NOT runnable. */
        public void requireActive() {
            synchronized (lock) {
                requireCurrent();
                if (state != State.RUNNING) {
                    throw new IllegalStateException("suspended serial handler is not runnable");
                }
            }
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

    /** Bounded non-blocking FIFO admission; never runs user callbacks under lock.
     * Completion callbacks can themselves finish synchronously, so delivery is
     * trampolined through one iterative drainer rather than recursively nested. */
    public CompletableFuture<Lease> submit(Kind kind) {
        Objects.requireNonNull(kind, "handler kind");
        CompletableFuture<Lease> future = new CompletableFuture<>();
        Waiter waiter = new Waiter(kind, future);
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("serial actor admission is closed");
            }
            // The first queued event is immediately eligible; subsequent
            // reservations count against the bounded waiting-mail capacity.
            if ((active != null || !pending.isEmpty()) && pending.size() >= capacity) {
                throw new IllegalStateException("serial actor admission queue full");
            }
            pending.addLast(waiter);
        }
        future.whenComplete((ignored, error) -> {
            if (future.isCancelled()) {
                synchronized (lock) { pending.remove(waiter); }
            }
        });
        drain();
        return future;
    }

    private void finish(Lease completed) {
        synchronized (lock) {
            completed.requireCurrent();
            completed.state = State.FINISHED;
            active = null;
        }
        drain();
    }

    /**
     * Only one drainer may deliver futures; a dependent stage is allowed to
     * close its lease inline without recursively completing the next future.
     * If cancellation races promotion, retire that lease and keep draining.
     */
    private void drain() {
        synchronized (lock) {
            if (delivering) return;
            delivering = true;
        }
        while (true) {
            Waiter next = null;
            Lease lease = null;
            List<CompletableFuture<Lease>> exhausted = null;
            synchronized (lock) {
                if (closed || active != null) {
                    delivering = false;
                    return;
                }
                while (!pending.isEmpty()) {
                    Waiter candidate = pending.removeFirst();
                    if (!candidate.future().isDone()) {
                        next = candidate;
                        break;
                    }
                }
                if (next == null) {
                    delivering = false;
                    return;
                }
                if (nextGeneration == Long.MAX_VALUE) {
                    closed = true; // Fail closed; no generation-token wrap.
                    exhausted = new ArrayList<>();
                    exhausted.add(next.future());
                    while (!pending.isEmpty()) {
                        exhausted.add(pending.removeFirst().future());
                    }
                    delivering = false;
                } else {
                    lease = new Lease(next.kind(), ++nextGeneration);
                    active = lease;
                }
            }
            if (exhausted != null) {
                for (CompletableFuture<Lease> rejected : exhausted) {
                    rejected.completeExceptionally(
                            new IllegalStateException("serial actor admission generation exhausted"));
                }
                return;
            }
            // CompletableFuture.complete invokes dependent actions synchronously.
            // The delivering flag prevents a nested close() from recursing.
            if (!next.future().complete(lease)) {
                synchronized (lock) {
                    if (active == lease) {
                        lease.state = State.FINISHED;
                        active = null;
                    }
                }
            }
        }
    }

    /** Package-private provenance check; a lease can only authorize its
     * exact issuing actor, even if another actor has the same generation. */
    boolean issued(Lease lease) {
        return lease != null && lease.issuer == this;
    }

    /** Unowned callbacks may queue only while this incarnation is open
     * and idle; observe both properties under the same admission monitor. */
    boolean canQueueUnownedContinuation() {
        synchronized (lock) { return !closed && active == null; }
    }

    boolean acceptsActorScopedCallbacks() {
        synchronized (lock) { return !closed; }
    }

    void requireIdleActorScopedCallback() {
        synchronized (lock) {
            if (closed || active != null) {
                throw new IllegalStateException(
                        "actor-scoped continuation is stopped or another serial handler is active");
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
