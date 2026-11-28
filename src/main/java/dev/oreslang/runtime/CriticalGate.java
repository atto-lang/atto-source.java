package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        // Cancellation and handoff arbitrate under COORDINATOR, not against
        // Future settlement timing. A granted ticket may still have a pending
        // completion notification; cancelling that notification MUST NOT
        // revoke ownership of a critical body that can already start.
        private final OresFuture<Void> admitted =
                new OresFuture<>(this::cancelQueuedAdmission, () -> { });
        private boolean requested;
        // Monotonic queue deadline is checked at handoff as well as by the
        // timer: a delayed/saturated timer cannot admit a stale waiter.
        private boolean timedAdmission;
        private long admissionStartNanos;
        private long admissionBudgetNanos;
        private boolean granted;
        private boolean closed;
        private Thread synchronousOwner;
        private Object logicalOwner;
        // Only host/embedder synchronous callers may wait on a physical thread.
        // Per-ticket signaling avoids waking every queued host thread.
        private java.util.concurrent.CountDownLatch synchronousWaiter;

        private Ticket() { }

        /**
         * Only a queued (or never-requested) ticket may be cancelled through
         * its admission Future. Granted permits must be released by physical
         * task completion, never by an observer cancelling admission.
         */
        private boolean cancelQueuedAdmission() {
            java.util.concurrent.CountDownLatch signal;
            synchronized (COORDINATOR) {
                if (closed || granted) return false;
                closed = true;
                waiters.remove(this);
                if (logicalOwner != null) WAITING_ON.remove(logicalOwner, CriticalGate.this);
                signal = synchronousWaiter;
            }
            if (signal != null) signal.countDown();
            return true;
        }

        /**
         * Nonblocking admission. Each scheduler task has a stable logical id
         * across await/carrier migration; detached host async invocations use
         * their own ticket rather than inheriting the creating host thread.
         */
        public OresFuture<Void> enterAsync() {
            return request(false);
        }

        /**
         * Bound only admission wait, not the duration of an admitted critical
         * invocation. The caller supplies its VM-owned timer executor; a
         * rejected timer registration fails closed rather than leaving an
         * unbounded waiter. The timer never executes guest code.
         *
         * <p>The fast path acquires immediately, including for a zero-duration
         * budget. On contention the timer atomically removes the waiting
         * ticket; it cannot revoke an already-granted owner. Total/execution
         * deadlines must cancel the owning task and await safe teardown,
         * rather than releasing this gate directly.</p>
         */
        public OresFuture<Void> enterAsyncWithin(
                Duration timeout, ScheduledExecutorService timer) {
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(timer, "timer");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("critical admission timeout is negative");
            }
            long nanos;
            try {
                nanos = timeout.toNanos();
            } catch (ArithmeticException overflow) {
                nanos = Long.MAX_VALUE;
            }
            timedAdmission = true;
            admissionStartNanos = System.nanoTime();
            admissionBudgetNanos = nanos;
            OresFuture<Void> ready = request(false);
            if (ready.isDone()) return ready;
            if (timeout.isZero()) {
                expireQueued();
                return ready;
            }
            // Account for time spent contending/admitting/scheduling.
            long elapsed = System.nanoTime() - admissionStartNanos;
            long remaining = Math.max(0L, nanos - Math.max(0L, elapsed));
            try {
                ScheduledFuture<?> scheduled = timer.schedule(
                        this::expireQueued, remaining, TimeUnit.NANOSECONDS);
                ready.whenCompleteRuntime((ignored, failure) -> scheduled.cancel(false));
            } catch (RejectedExecutionException rejected) {
                failQueued(new IllegalStateException(
                        "critical admission deadline could not be scheduled", rejected));
            }
            return ready;
        }

        private void expireQueued() {
            failQueued(new TimeoutException("critical admission queue timeout"));
        }

        /** Only an ungranted ticket can lose admission to timeout. */
        private void failQueued(Throwable failure) {
            java.util.concurrent.CountDownLatch signal;
            synchronized (COORDINATOR) {
                if (!requested || granted || closed) return;
                closed = true;
                waiters.remove(this);
                WAITING_ON.remove(logicalOwner, CriticalGate.this);
                signal = synchronousWaiter;
            }
            if (signal != null) signal.countDown();
            admitted.failFromRuntime(failure);
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

        /** Synchronous callers cannot suspend; never park an actor or ROOT carrier. */
        public void enterSync() {
            enterSyncWithTimeout(null);
        }

        /** Host/embedder-only bounded wait; a contended carrier still fails closed. */
        public void enterSyncWithin(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("critical admission timeout is negative");
            }
            long nanos;
            try {
                nanos = timeout.toNanos();
            } catch (ArithmeticException overflow) {
                nanos = Long.MAX_VALUE;
            }
            timedAdmission = true;
            admissionStartNanos = System.nanoTime();
            admissionBudgetNanos = nanos;
            enterSyncWithTimeout(timeout);
        }

        private void enterSyncWithTimeout(Duration timeout) {
            OresFuture<Void> ready = request(true);
            if (ActorRuntime.inActorExecution()
                    || OresScheduler.current() != null
                    || OresScheduler.isSchedulerCarrierThread()) {
                // Never park a scheduler/actor worker, including unbound
                // after-turn or CONTROL dispatch callbacks. Release outside
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
                    if (timeout == null) {
                        signal.await();
                    } else {
                        long elapsed = System.nanoTime() - admissionStartNanos;
                        long remaining = Math.max(0L,
                                admissionBudgetNanos - Math.max(0L, elapsed));
                        if (!signal.await(remaining, TimeUnit.NANOSECONDS)) {
                            expireQueued();
                        }
                    }
                } catch (InterruptedException interrupted) {
                    close();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "interrupted while waiting for critical section", interrupted);
                }
            }
            synchronized (COORDINATOR) {
                if (closed || !granted) {
                    if (timedAdmission
                            && System.nanoTime() - admissionStartNanos >= admissionBudgetNanos) {
                        throw new IllegalStateException(
                                "critical admission queue timeout",
                                new TimeoutException("critical admission queue timeout"));
                    }
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
            java.util.ArrayList<Ticket> expiredAtHandoff = new java.util.ArrayList<>();
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
                        if (candidate.timedAdmission
                                && System.nanoTime() - candidate.admissionStartNanos
                                        >= candidate.admissionBudgetNanos) {
                            candidate.closed = true;
                            expiredAtHandoff.add(candidate);
                            continue;
                        }
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
            for (Ticket expired : expiredAtHandoff) {
                expired.admitted.failFromRuntime(
                        new TimeoutException("critical admission queue timeout"));
                if (expired.synchronousWaiter != null) expired.synchronousWaiter.countDown();
            }
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
