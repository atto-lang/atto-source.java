package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
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
    private final ArrayDeque<Ticket> waiters = new ArrayDeque<>();
    private Ticket owner;

    public final class Ticket implements AutoCloseable {
        // Cancellation of a pending admission retracts its queue slot. A granted
        // admission is already settled and therefore cannot be cancelled.
        // Cancellation must retract a pending FIFO/wait-graph edge BEFORE
        // publishing the cancelled Future. A post-settlement close hook lets
        // the waiter resume and see a phantom wait/deadlock registration.
        private final OresFuture<Void> admitted =
                new OresFuture<>(this::cancelPendingBeforePublication, () -> { });
        private boolean requested;
        private boolean granted;
        private boolean closed;
        private Thread synchronousOwner;
        private Object logicalOwner;
        // Only host/embedder synchronous callers may wait on a physical thread.
        // Per-ticket signaling avoids waking every queued host thread.
        private java.util.concurrent.CountDownLatch synchronousWaiter;

        private Ticket() { }

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
                if (closed || requested) {
                    throw new IllegalStateException("critical ticket already used or released");
                }
                requested = true;
                logicalOwner = domain;
                if (owner == null && waiters.isEmpty()) {
                    owner = this;
                    granted = true;
                    completeNow = true;
                } else {
                    if (waiters.size() >= MAX_WAITERS) {
                        requested = false;
                        throw new IllegalStateException("critical wait queue is full");
                    }
                    if (owner != null && owner.logicalOwner == domain) {
                        requested = false;
                        throw new IllegalStateException(
                                "recursive logical critical acquisition would deadlock");
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

        /**
         * OresFuture cancellation admission executes before its public
         * Cancelled state becomes observable. Only queued waiters may cancel:
         * once a ticket has been granted, the physical owner must release it
         * after its guest turn exits rather than using public Future cancel.
         * No Future callbacks, guest code or wakeups run under COORDINATOR.
         */
        private boolean cancelPendingBeforePublication() {
            synchronized (COORDINATOR) {
                if (!requested || closed || granted) return false;
                if (!waiters.remove(this)) return false;
                closed = true;
                WAITING_ON.remove(logicalOwner, CriticalGate.this);
                return true;
            }
        }

        /** Synchronous callers cannot suspend; never park an actor or ROOT carrier. */
        public void enterSync() {
            if (ActorRuntime.inActorExecution()
                    || ActorRuntime.isActorCarrierThread()
                    || ActorRuntime.inActorGroupMailmanExecution()
                    || OresScheduler.current() != null
                    || OresScheduler.isSchedulerCarrierThread()) {
                // A carrier must NEVER register a waiter and then attempt to
                // retract it. Between enqueue and close the owner could hand
                // us the gate, creating a spurious ownership/cancel race.
                // Admit only an immediately free gate, atomically, and leave
                // both FIFO and the global wait graph untouched on contention.
                Object domain = OresScheduler.currentTaskDomain();
                if (domain == null) domain = ActorRuntime.currentActorExecutionDomain();
                if (domain == null) domain = Thread.currentThread();
                synchronized (COORDINATOR) {
                    if (closed || requested) {
                        throw new IllegalStateException("critical ticket already used or released");
                    }
                    if (owner != null || !waiters.isEmpty()) {
                        if (owner != null && owner.logicalOwner == domain) {
                            throw new IllegalStateException(
                                    "recursive logical critical acquisition would deadlock");
                        }
                        throw new IllegalStateException(
                                "contended synchronous critical call would block an actor/scheduler carrier; declare suspend critical and await it");
                    }
                    requested = true;
                    logicalOwner = domain;
                    owner = this;
                    granted = true;
                    synchronousOwner = Thread.currentThread();
                }
                // Only runtime-Future publication happens after releasing the
                // short metadata coordinator. No guest body executes under it.
                if (!admitted.completeFromRuntime(null)) {
                    throw new CancellationException("critical admission was revoked");
                }
                return;
            }
            OresFuture<Void> ready = request(true);
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

    public Ticket newTicket() { return new Ticket(); }

    public int queuedCount() {
        synchronized (COORDINATOR) { return waiters.size(); }
    }

    public boolean held() {
        synchronized (COORDINATOR) { return owner != null; }
    }

    /** Runtime diagnostic; identifies queued logical invocations, not threads. */
    public static int waitingLogicalDomains() {
        synchronized (COORDINATOR) { return WAITING_ON.size(); }
    }
}
