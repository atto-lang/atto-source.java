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

    // A throwing internal close observer cannot strand a granted successor
    // or leave another actor's STOP/domain-close cleanup unfinished.
    private static Throwable recordCleanupFailure(Throwable prior, Throwable next) {
        if (prior == null) return next;
        // Preserve JVM Error severity even if an ordinary callback threw
        // first; ActorRuntime must not mistake a suppressed Error for a
        // harmless RuntimeException during STOP cleanup.
        if (next instanceof Error && !(prior instanceof Error)) {
            if (next != prior) next.addSuppressed(prior);
            return next;
        }
        if (prior != next) prior.addSuppressed(next);
        return prior;
    }

    private static void throwCleanupFailure(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
    }

    public static final int MAX_ACTIVE_READERS = MAX_WAITERS;
    private final ArrayDeque<Ticket> waiters = new ArrayDeque<>();
    private final IdentityHashMap<Ticket, Boolean> readers = new IdentityHashMap<>();
    // Avoid O(n²) read admission: same-domain recursion checks should not
    // scan every granted reader as cohort sizes grow.
    // Every granted ticket has exactly one entry, removed on release/STOP.
    private final IdentityHashMap<Object, Ticket> heldDomains = new IdentityHashMap<>();
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
        private Runnable deregisterActorResource = () -> { };
        private Runnable onClose = () -> { };
        private boolean closeListenerRegistered;
        /** Captured actor incarnation, not a JVM carrier/thread affinity. */
        private java.util.function.Consumer<Runnable> actorPhysicalExit;
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
            ActorRuntime actor = ActorRuntime.currentActorRuntime();
            java.util.function.Consumer<Runnable> physicalExit = actor == null
                    ? null : actor.captureCurrentActorCarrierExit();
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
                actorPhysicalExit = physicalExit;
                if (heldByDomain(domain)) {
                    requested = false;
                    throw new IllegalStateException(
                            "recursive logical critical acquisition would deadlock");
                }
                // A logical task already awaiting another gate cannot begin
                // any second acquisition, even if this gate is immediately
                // free. Checking only the queueing branch lets it bypass the
                // wait-for dependency graph and retain multiple live tickets.
                CriticalGate priorWait = WAITING_ON.get(domain);
                if (priorWait != null) {
                    requested = false;
                    throw new IllegalStateException(
                            "logical caller cannot acquire another critical gate while waiting");
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
                    if (wouldCreateCycle(domain)) {
                        requested = false;
                        throw new IllegalStateException(
                                "cross-gate critical acquisition would create a deadlock cycle");
                    }
                    WAITING_ON.put(domain, CriticalGate.this);
                    waiters.addLast(this);
                }
            }
            // First finish admission metadata under COORDINATOR; only then
            // register actor ownership. A duplicate request must never remove
            // the existing admission's STOP registration.
            if (actor != null) {
                final Runnable registration;
                try {
                    registration = actor.trackCurrentActorResource(this);
                } catch (RuntimeException | Error failure) {
                    close(); // retract an admission racing actor STOP
                    throw failure;
                }
                boolean wasClosed;
                synchronized (COORDINATOR) {
                    wasClosed = closed;
                    if (!wasClosed) deregisterActorResource = registration;
                }
                if (wasClosed) registration.run();
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
            if (ActorRuntime.inActorExecution()
                    || ActorRuntime.isActorCarrierThread()
                    || OresScheduler.current() != null
                    || OresScheduler.isSchedulerCarrierThread()) {
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

        /** Let an RW wrapper clean its separate logical-owner registry. */
        void onClose(Runnable listener) {
            Objects.requireNonNull(listener, "listener");
            boolean alreadyClosed;
            synchronized (COORDINATOR) {
                if (closeListenerRegistered) {
                    throw new IllegalStateException("critical ticket close listener already set");
                }
                closeListenerRegistered = true;
                alreadyClosed = closed;
                if (!alreadyClosed) onClose = listener;
            }
            if (alreadyClosed) listener.run();
        }

        @Override
        public void close() {
            closeTicket(false);
        }

        /**
         * STOP may retract an admission that has not yet been granted without
         * waiting for its actor's active physical turn to unwind. Decide and
         * close atomically with queue-to-grant handoff under COORDINATOR:
         * observing a pending ticket and later calling close() separately
         * would incorrectly revoke a permit granted in between.
         */
        void retractPendingAtStop() {
            closeTicket(true);
        }

        private void closeTicket(boolean pendingOnly) {
            java.util.List<Ticket> wake;
            java.util.concurrent.CountDownLatch cancelledWaiter = null;
            Runnable removeActorTracking;
            Runnable notifyWrapper;
            synchronized (COORDINATOR) {
                if (closed || (pendingOnly && granted)) return;
                closed = true;
                synchronousOwner = null;
                cancelledWaiter = synchronousWaiter;
                removeActorTracking = deregisterActorResource;
                deregisterActorResource = () -> { };
                notifyWrapper = onClose;
                onClose = () -> { };
                if (owner == this) {
                    owner = null;
                } else if (mode == Mode.READ) {
                    readers.remove(this);
                }
                heldDomains.remove(logicalOwner, this);
                if (waiters.remove(this)) {
                    WAITING_ON.remove(logicalOwner, CriticalGate.this);
                }
                wake = drainLocked();
            }
            Throwable cleanupFailure = null;
            try {
                removeActorTracking.run();
            } catch (RuntimeException | Error failure) {
                cleanupFailure = recordCleanupFailure(cleanupFailure, failure);
            }
            try {
                notifyWrapper.run();
            } catch (RuntimeException | Error failure) {
                cleanupFailure = recordCleanupFailure(cleanupFailure, failure);
            }
            if (cancelledWaiter != null) cancelledWaiter.countDown();
            try {
                if (!admitted.isDone()) {
                    admitted.failFromRuntime(new CancellationException("critical ticket released"));
                }
            } catch (RuntimeException | Error failure) {
                cleanupFailure = recordCleanupFailure(cleanupFailure, failure);
            }
            for (Ticket candidate : wake) {
                try {
                    if (candidate.isHeld()) candidate.admitted.completeFromRuntime(null);
                } catch (RuntimeException | Error failure) {
                    cleanupFailure = recordCleanupFailure(cleanupFailure, failure);
                } finally {
                    java.util.concurrent.CountDownLatch signal;
                    synchronized (COORDINATOR) {
                        signal = candidate.synchronousWaiter;
                    }
                    if (signal != null) signal.countDown();
                }
            }
            throwCleanupFailure(cleanupFailure);
        }
    }

    /** Caller owns the monitor. */
    private boolean heldByDomain(Object domain) {
        return heldDomains.containsKey(domain);
    }

    /** Caller owns the monitor. */
    private void grantLocked(Ticket ticket) {
        if (heldDomains.containsKey(ticket.logicalOwner)) {
            throw new IllegalStateException("duplicate logical critical owner domain");
        }
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
        heldDomains.put(ticket.logicalOwner, ticket);
        ticket.granted = true;
    }

    /** Drain exactly one writer or one FIFO cohort of readers. Monitor held. */
    private java.util.List<Ticket> drainLocked() {
        // Domain shutdown is terminal: a concurrent ticket release must never
        // hand off another permit after the close admission fence is installed.
        if (domainClosed || owner != null || waiters.isEmpty()) return java.util.List.of();
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

    /**
     * Permanently close admission to this domain. Queued requests are cancelled
     * immediately; a granted ticket owned by an actively executing actor stays
     * physically held until that actor's TurnExecutor has fully unwound.
     *
     * This operation is intentionally nonblocking: a hostile or CPU-bound
     * actor cannot park the STOP thread or critical metadata coordinator.
     * Host-owned tickets lack a runtime physical-turn identity and remain
     * subject to the embedder's own scope/lifetime discipline.
     */
    public void closeAll() {
        java.util.List<Ticket> queued;
        java.util.List<Ticket> granted = new java.util.ArrayList<>();
        CriticalRwGate facade;
        synchronized (COORDINATOR) {
            if (domainClosed) return;
            domainClosed = true; // atomic with every request and FIFO grant
            if (owner != null) granted.add(owner);
            granted.addAll(readers.keySet());
            queued = java.util.List.copyOf(waiters);
            // Remove queued dependencies now: an unrelated actor must not
            // observe a deadlocked wait-for edge to an already-closed domain.
            for (Ticket ticket : queued) {
                WAITING_ON.remove(ticket.logicalOwner, this);
            }
            waiters.clear();
            // Do NOT clear owner/readers/heldDomains: those capabilities
            // protect live actor guest turns until their ticket actually closes.
        }
        synchronized (this) { facade = rwFacade; }
        Throwable failure = null;
        // Preserve the established first-failure ordering for an immediate
        // host-owned grant versus queued observers. Actor-owned grants are
        // only scheduled here; no physical-carrier waiting occurs.
        for (Ticket ticket : granted) {
            try {
                java.util.function.Consumer<Runnable> afterExit = ticket.actorPhysicalExit;
                if (afterExit == null) {
                    ticket.close();
                } else {
                    afterExit.accept(ticket::close);
                }
            } catch (RuntimeException | Error problem) {
                failure = recordCleanupFailure(failure, problem);
            }
        }
        for (Ticket ticket : queued) {
            try {
                ticket.close();
            } catch (RuntimeException | Error problem) {
                failure = recordCleanupFailure(failure, problem);
            }
        }
        // The facade must stop taking new tickets, but it must not force-close
        // a writer/reader still protecting an active guest turn. Underlying
        // onClose callbacks retire those deferred wrappers after quiescence.
        if (facade != null) {
            try {
                facade.onEngineClosed();
            } catch (RuntimeException | Error problem) {
                failure = recordCleanupFailure(failure, problem);
            }
        }
        throwCleanupFailure(failure);
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
