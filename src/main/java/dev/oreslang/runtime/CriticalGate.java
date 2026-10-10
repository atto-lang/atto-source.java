package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * FIFO, non-thread-affine critical section for one object or one code unit.
 *
 * <p>Tickets are linear logical-invocation capabilities, not JVM locks. An
 * async owner keeps its ticket across await/carrier migration and releases at
 * task completion (including cancellation). Completion callbacks only settle
 * runtime Futures; they never run user code.</p>
 */
public final class CriticalGate {
    public static final int MAX_WAITERS = 8192;
    private final ArrayDeque<Ticket> waiters = new ArrayDeque<>();
    private Ticket owner;

    public final class Ticket implements AutoCloseable {
        private final OresFuture<Void> admitted = new OresFuture<>();
        private boolean requested;
        private boolean granted;
        private boolean closed;
        private Thread synchronousOwner;

        private Ticket() { }

        /** Nonblocking FIFO admission, safe on actor carrier threads. */
        public OresFuture<Void> enterAsync() {
            boolean completeNow = false;
            synchronized (CriticalGate.this) {
                if (closed || requested) {
                    throw new IllegalStateException("critical ticket already used or released");
                }
                requested = true;
                if (owner == null && waiters.isEmpty()) {
                    owner = this;
                    granted = true;
                    completeNow = true;
                } else {
                    if (waiters.size() >= MAX_WAITERS) {
                        requested = false;
                        throw new IllegalStateException("critical wait queue is full");
                    }
                    waiters.addLast(this);
                }
            }
            if (completeNow) admitted.completeFromRuntime(null);
            return admitted;
        }

        /** Synchronous callers cannot suspend; never park an actor or ROOT carrier. */
        public void enterSync() {
            synchronized (CriticalGate.this) {
                if (owner != null && owner.synchronousOwner == Thread.currentThread()) {
                    throw new IllegalStateException("recursive synchronous critical acquisition would deadlock");
                }
            }
            OresFuture<Void> ready = enterAsync();
            if (ready.isDone()) {
                synchronized (CriticalGate.this) {
                    synchronousOwner = Thread.currentThread();
                }
                return;
            }
            if (ActorRuntime.inActorExecution() || OresScheduler.current() != null) {
                close();
                throw new IllegalStateException(
                        "contended synchronous critical call would block an actor/scheduler carrier; declare suspend critical and await it");
            }
            synchronized (CriticalGate.this) {
                while (!granted && !closed) {
                    try {
                        CriticalGate.this.wait();
                    } catch (InterruptedException interrupted) {
                        close();
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "interrupted while waiting for critical section", interrupted);
                    }
                }
                if (closed && !granted) throw new CancellationException("critical ticket was cancelled");
                synchronousOwner = Thread.currentThread();
            }
        }

        /** Attach after scheduler start; already-settled completions run this too. */
        public void releaseOnCompletion(OresFuture<?> completion) {
            Objects.requireNonNull(completion, "completion")
                    .whenCompleteRuntime((value, failure) -> close());
        }

        public boolean isHeld() {
            synchronized (CriticalGate.this) {
                return granted && !closed;
            }
        }

        @Override
        public void close() {
            Ticket wake = null;
            synchronized (CriticalGate.this) {
                if (closed) return;
                closed = true;
                synchronousOwner = null;
                if (owner == this) {
                    owner = null;
                    while (!waiters.isEmpty()) {
                        Ticket candidate = waiters.removeFirst();
                        if (candidate.closed) continue;
                        owner = candidate;
                        candidate.granted = true;
                        wake = candidate;
                        break;
                    }
                } else {
                    waiters.remove(this);
                }
                CriticalGate.this.notifyAll();
            }
            if (!admitted.isDone()) {
                admitted.failFromRuntime(new CancellationException("critical ticket released"));
            }
            if (wake != null) wake.admitted.completeFromRuntime(null);
        }
    }

    public Ticket newTicket() { return new Ticket(); }

    public synchronized int queuedCount() { return waiters.size(); }
    public synchronized boolean held() { return owner != null; }
}
