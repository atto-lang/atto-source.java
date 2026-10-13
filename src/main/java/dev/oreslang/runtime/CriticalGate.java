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
    private CriticalRwGate rwFacade;

    /** One RW facade per domain; legacy and explicit modes share this core. */
    public synchronized CriticalRwGate rwGate() {
        if (rwFacade == null) rwFacade = new CriticalRwGate(this);
        return rwFacade;
    }

    public final class Ticket implements AutoCloseable {
        // Cancellation of a pending admission retracts its queue slot. A granted
        // admission is already settled and therefore cannot be cancelled.
        private final OresFuture<Void> admitted = new OresFuture<>(this::close);
        private final Mode mode;
        private boolean requested;
        private boolean granted;
        private boolean closed;
        private boolean completionAttached;
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

        /** Admission observation is valid only once requested or revoked. */
        public OresFuture<Void> admission() {
            synchronized (COORDINATOR) {
                if (!requested && !closed)
                    throw new IllegalStateException("critical admission was not requested");
            }
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
            Objects.requireNonNull(completion, "completion");
            synchronized (COORDINATOR) {
                if (closed) {
                    throw new IllegalStateException(
                            "cannot bind a released critical ticket");
                }
                if (completionAttached) {
                    throw new IllegalStateException(
                            "critical ticket completion already bound");
                }
                // A ticket has one terminal owner. Attaching multiple unrelated
                // completions could release it while its admitted task is live.
                completionAttached = true;
            }
            completion.whenCompleteRuntime((value, failure) -> close());
        }

        public boolean isHeld() {
            synchronized (COORDINATOR) {
                return granted && !closed;
            }
        }

        @Override
        public void close() {
            java.util.List<Ticket> wake;
            java.util.concurrent.CountDownLatch cancelledWaiter = null;
            synchronized (COORDINATOR) {
                if (closed) return;
                closed = true;
                synchronousOwner = null;
                cancelledWaiter = synchronousWaiter;
                if (owner == this) {
                    owner = null;
                } else if (mode == Mode.READ) {
                    readers.remove(this);
                }
                if (waiters.remove(this)) {
                    WAITING_ON.remove(logicalOwner, CriticalGate.this);
                }
                wake = drainLocked();
            }
            if (cancelledWaiter != null) cancelledWaiter.countDown();
            if (!admitted.isDone()) {
                admitted.failFromRuntime(new CancellationException("critical ticket released"));
            }
            for (Ticket candidate : wake) {
                if (candidate.isHeld()) candidate.admitted.completeFromRuntime(null);
                java.util.concurrent.CountDownLatch signal;
                synchronized (COORDINATOR) {
                    signal = candidate.synchronousWaiter;
                }
                if (signal != null) signal.countDown();
            }
        }
    }

    /** Caller owns the monitor. */
    private boolean heldByDomain(Object domain) {
        if (owner != null && owner.logicalOwner == domain) return true;
        for (Ticket reader : readers.keySet()) {
            if (reader.logicalOwner == domain) return true;
        }
        return false;
    }

    /** Caller owns the monitor. */
    private void grantLocked(Ticket ticket) {
        if (owner != null) throw new IllegalStateException("reader/writer overlap");
        if (ticket.mode == Mode.WRITE) {
            if (!readers.isEmpty()) throw new IllegalStateException("writer/reader overlap");
            owner = ticket;
        } else {
            if (readers.size() >= MAX_ACTIVE_READERS) {
                throw new IllegalStateException("active critical readers full");
            }
            readers.put(ticket, Boolean.TRUE);
        }
        ticket.granted = true;
    }

    /** Drain exactly one writer or one FIFO cohort of readers. Monitor held. */
    private java.util.List<Ticket> drainLocked() {
        if (owner != null || waiters.isEmpty()) return java.util.List.of();
        if (waiters.peekFirst().mode == Mode.WRITE && !readers.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<Ticket> wake = new java.util.ArrayList<>();
        while (!waiters.isEmpty()) {
            Ticket candidate = waiters.peekFirst();
            if (candidate.mode == Mode.WRITE && !readers.isEmpty()) break;
            if (candidate.mode == Mode.WRITE && !wake.isEmpty()) break;
            if (candidate.mode == Mode.READ && readers.size() >= MAX_ACTIVE_READERS) break;
            waiters.removeFirst();
            WAITING_ON.remove(candidate.logicalOwner, this);
            if (candidate.closed) continue;
            grantLocked(candidate);
            wake.add(candidate);
            if (candidate.mode == Mode.WRITE) break;
        }
        return wake;
    }

    /**
     * Follow the waiting dependency chain: requesting this gate would close
     * a cycle if the current owner is (transitively) waiting on requester.
     * Call only under COORDINATOR.
     */
    private boolean wouldCreateCycle(Object requester) {
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<Object> pending = new ArrayDeque<>();
        appendOwners(pending);
        while (!pending.isEmpty()) {
            Object ownerDomain = pending.removeFirst();
            if (ownerDomain == requester) return true;
            if (visited.put(ownerDomain, Boolean.TRUE) != null) continue;
            CriticalGate waiting = WAITING_ON.get(ownerDomain);
            if (waiting != null) waiting.appendOwners(pending);
        }
        return false;
    }

    /** Caller owns global COORDINATOR. Include every admitted reader. */
    private void appendOwners(ArrayDeque<Object> pending) {
        if (owner != null) pending.addLast(owner.logicalOwner);
        for (Ticket reader : readers.keySet()) {
            pending.addLast(reader.logicalOwner);
        }
    }

    public Ticket newTicket() { return newTicket(Mode.WRITE); }
    public Ticket newTicket(Mode mode) {
        synchronized (COORDINATOR) {
            if (domainClosed) throw new IllegalStateException("critical gate closed");
        }
        return new Ticket(mode);
    }

    /** Permanently revoke all tickets for a torn-down protection domain. */
    public void closeAll() {
        java.util.List<Ticket> revoke = new java.util.ArrayList<>();
        synchronized (COORDINATOR) {
            if (domainClosed) return;
            domainClosed = true;
            if (owner != null) revoke.add(owner);
            revoke.addAll(readers.keySet());
            revoke.addAll(waiters);
            owner = null;
            readers.clear();
            waiters.clear();
            for (Ticket ticket : revoke) {
                ticket.closed = true;
                ticket.granted = false;
                WAITING_ON.remove(ticket.logicalOwner, this);
            }
        }
        for (Ticket ticket : revoke) {
            ticket.admitted.failFromRuntime(
                    new CancellationException("critical domain closed"));
            if (ticket.synchronousWaiter != null) ticket.synchronousWaiter.countDown();
        }
    }

    public int activeReaderCount() {
        synchronized (COORDINATOR) { return readers.size(); }
    }

    public int queuedCount() {
        synchronized (COORDINATOR) { return waiters.size(); }
    }

    public boolean hasWriter() {
        synchronized (COORDINATOR) { return owner != null; }
    }

    public boolean held() {
        synchronized (COORDINATOR) { return owner != null || !readers.isEmpty(); }
    }

    /** Runtime diagnostic; identifies queued logical invocations, not threads. */
    public static int waitingLogicalDomains() {
        synchronized (COORDINATOR) { return WAITING_ON.size(); }
    }
}
