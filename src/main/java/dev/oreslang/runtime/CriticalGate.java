package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
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
    // A short, process-local coordinator serializes queue metadata and the
    // critical wait-for graph. No guest code or scheduler turn runs under it.
    // This must never be confused with a permit held across await.
    private static final Object COORDINATOR = new Object();
    private static final IdentityHashMap<Object, CriticalGate> WAITING_ON =
            new IdentityHashMap<>();
    public enum Mode { READ, WRITE }
    public static final int MAX_ACTIVE_READERS = MAX_WAITERS;
    private final ArrayDeque<Ticket> waiters = new ArrayDeque<>();
    private final IdentityHashMap<Ticket, Boolean> readers = new IdentityHashMap<>();
    private Ticket owner;
    private boolean domainClosed;

    public final class Ticket implements AutoCloseable {
        // Cancellation of a pending admission retracts its queue slot. A granted
        // admission is already settled and therefore cannot be cancelled.
        private final OresFuture<Void> admitted = new OresFuture<>(this::close);
        private final Mode mode;
        private boolean requested;
        private boolean granted;
        private boolean closed;
        private Thread synchronousOwner;
        private Object logicalOwner;
        // Only host/embedder synchronous callers may wait on a physical thread.
        // Per-ticket signaling avoids waking every queued host thread.
        private java.util.concurrent.CountDownLatch synchronousWaiter;

        private Ticket(Mode mode) { this.mode = Objects.requireNonNull(mode, "mode"); }
        public Mode mode() { return mode; }

        /**
         * Nonblocking admission. Each scheduler task has a stable logical id
         * across await/carrier migration; detached host async invocations use
         * their own ticket rather than inheriting the creating host thread.
         */
        public OresFuture<Void> enterAsync() {
            return request(false);
        }

        private OresFuture<Void> request(boolean synchronous) {
            Object domain = OresScheduler.currentTaskDomain();
            if (domain == null) domain = ActorRuntime.currentActorExecutionDomain();
            if (domain == null) domain = synchronous ? Thread.currentThread() : this;
            boolean completeNow = false;
            synchronized (COORDINATOR) {
                if (closed || requested || domainClosed) {
                    throw new IllegalStateException("critical ticket already used or released");
                }
                requested = true;
                logicalOwner = domain;
                if (heldByDomain(domain)) {
                    requested = false;
                    throw new IllegalStateException(
                            "recursive logical critical acquisition would deadlock");
                }
                boolean canEnter = waiters.isEmpty() && owner == null
                        && (mode == Mode.READ
                                ? readers.size() < MAX_ACTIVE_READERS : readers.isEmpty());
                if (canEnter) {
                    grantLocked(this);
                    completeNow = true;
                } else {
                    if (waiters.size() >= MAX_WAITERS) {
                        requested = false;
                        throw new IllegalStateException("critical wait queue is full");
                    }
                    CriticalGate priorWait = WAITING_ON.get(domain);
                    if (priorWait != null) {
                        requested = false;
                        throw new IllegalStateException(
                                "logical caller cannot wait on multiple critical gates simultaneously");
                    }
                    if (wouldCreateCycle(domain)) {
                        requested = false;
                        throw new IllegalStateException(
                                "cross-gate critical acquisition would create a deadlock cycle");
                    }
                    WAITING_ON.put(domain, CriticalGate.this);
                    waiters.addLast(this);
                }
            }
            // Completing the Future may notify runtime callbacks; always do so
            // after releasing the metadata coordinator.
            if (completeNow) admitted.completeFromRuntime(null);
            return admitted;
        }

        /** Synchronous callers cannot suspend; never park an actor or ROOT carrier. */
        public void enterSync() {
            OresFuture<Void> ready = request(true);
            if (ActorRuntime.inActorExecution() || OresScheduler.current() != null) {
                // Never park a scheduler/actor worker. Release and fail outside
                // the monitor, because Future settlement runs callbacks.
                boolean admittedNow;
                synchronized (COORDINATOR) {
                    admittedNow = granted && !closed;
                    if (admittedNow) synchronousOwner = Thread.currentThread();
                }
                if (!admittedNow) {
                    close();
                    throw new IllegalStateException(
                            "contended synchronous critical call would block an actor/scheduler carrier; declare suspend critical and await it");
                }
                return;
            }
            java.util.concurrent.CountDownLatch signal = null;
            synchronized (COORDINATOR) {
                if (!granted && !closed) {
                    synchronousWaiter = new java.util.concurrent.CountDownLatch(1);
                    signal = synchronousWaiter;
                }
            }
            if (signal != null) {
                try {
                    signal.await();
                } catch (InterruptedException interrupted) {
                    close();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "interrupted while waiting for critical section", interrupted);
                }
            }
            synchronized (COORDINATOR) {
                if (closed || !granted) {
                    throw new CancellationException("critical ticket was cancelled");
                }
                synchronousOwner = Thread.currentThread();
            }
        }

        /** Attach after scheduler start; already-settled completions run this too. */
        public void releaseOnCompletion(OresFuture<?> completion) {
            Objects.requireNonNull(completion, "completion")
                    .whenCompleteRuntime((value, failure) -> close());
        }

        public boolean isHeld() {
            synchronized (COORDINATOR) {
                return granted && !closed;
            }
        }

        @Override
        public void close() {
            Ticket wake = null;
            java.util.concurrent.CountDownLatch cancelledWaiter = null;
            synchronized (COORDINATOR) {
                if (closed) return;
                closed = true;
                synchronousOwner = null;
                cancelledWaiter = synchronousWaiter;
                if (owner == this) {
                    owner = null;
                    while (!waiters.isEmpty()) {
                        Ticket candidate = waiters.removeFirst();
                        WAITING_ON.remove(candidate.logicalOwner, CriticalGate.this);
                        if (candidate.closed) continue;
                        owner = candidate;
                        candidate.granted = true;
                        wake = candidate;
                        break;
                    }
                } else {
                    if (waiters.remove(this)) {
                        WAITING_ON.remove(logicalOwner, CriticalGate.this);
                    }
                }
            }
            if (cancelledWaiter != null) cancelledWaiter.countDown();
            if (!admitted.isDone()) {
                admitted.failFromRuntime(new CancellationException("critical ticket released"));
            }
            if (wake != null) {
                wake.admitted.completeFromRuntime(null);
                java.util.concurrent.CountDownLatch signal;
                synchronized (COORDINATOR) {
                    signal = wake.synchronousWaiter;
                }
                if (signal != null) signal.countDown();
            }
        }
    }

    /**
     * Follow the waiting dependency chain: requesting this gate would close
     * a cycle if the current owner is (transitively) waiting on requester.
     * Call only under COORDINATOR.
     */
    private boolean wouldCreateCycle(Object requester) {
        Ticket current = owner;
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        while (current != null) {
            Object ownerDomain = current.logicalOwner;
            if (ownerDomain == requester) return true;
            if (visited.put(ownerDomain, Boolean.TRUE) != null) return false;
            CriticalGate waiting = WAITING_ON.get(ownerDomain);
            if (waiting == null) return false;
            current = waiting.owner;
        }
        return false;
    }

    public Ticket newTicket() { return newTicket(Mode.WRITE); }
    public Ticket newTicket(Mode mode) {
        synchronized (COORDINATOR) {
            if (domainClosed) throw new IllegalStateException("critical gate closed");
        }
        return new Ticket(mode);
    }

    public int activeReaderCount() {
        synchronized (COORDINATOR) { return readers.size(); }
    }

    public int queuedCount() {
        synchronized (COORDINATOR) { return waiters.size(); }
    }

    public boolean held() {
        synchronized (COORDINATOR) { return owner != null || !readers.isEmpty(); }
    }

    /** Runtime diagnostic; identifies queued logical invocations, not threads. */
    public static int waitingLogicalDomains() {
        synchronized (COORDINATOR) { return WAITING_ON.size(); }
    }
}
