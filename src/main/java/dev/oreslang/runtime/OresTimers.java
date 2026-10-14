package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Semaphore;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime-only monotonic timer source for compiler-lowered sleep/after/interval.
 *
 * <p>Expiration settles an OresFuture; it NEVER executes guest code. An
 * OresScheduler await resumes on the original scheduler's next dispatch.
 * The timer service is scoped to its owning VM/context; close revokes all
 * outstanding timers before guest-owned resources may be reclaimed.</p>
 */
public final class OresTimers implements AutoCloseable {
    private static final int MAX_ACTIVE_PER_OWNER = 4096;
    private static final int MAX_INTERVALS_PER_OWNER = 4096;
    // Per-context limits alone are insufficient when an untrusted application
    // creates thousands of timer owners against the shared host clock.
    static final int MAX_GLOBAL_ACTIVE_TIMERS = 16384;
    private static final Semaphore GLOBAL_SLOTS =
            new Semaphore(MAX_GLOBAL_ACTIVE_TIMERS);

    static int availableGlobalTimerSlots() {
        return GLOBAL_SLOTS.availablePermits();
    }
    private static final ScheduledThreadPoolExecutor CLOCK = createClock();

    private static ScheduledThreadPoolExecutor createClock() {
        AtomicInteger ids = new AtomicInteger();
        ScheduledThreadPoolExecutor clock = new ScheduledThreadPoolExecutor(2,
                runnable -> Thread.ofPlatform().daemon(true)
                        .name("ores-monotonic-timer-" + ids.incrementAndGet())
                        .unstarted(runnable));
        clock.setRemoveOnCancelPolicy(true);
        return clock;
    }

    private final Object admission = new Object();
    private final Set<OresFuture<Void>> active = new HashSet<>();
    private final Set<Interval> intervals = new HashSet<>();
    private volatile boolean closed;

    /**
     * Complete after the duration measured on a monotonic clock.
     * Cancellation removes the scheduled host callback, not an actor turn.
     */
    public OresFuture<Void> afterNanos(long delayNanos) {
        if (delayNanos < 0) throw new IllegalArgumentException("negative timer delay");
        AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        OresFuture<Void> future = new OresFuture<>(() -> {
            ScheduledFuture<?> event = scheduled.get();
            if (event != null) event.cancel(false);
        });
        synchronized (admission) {
            if (closed) throw new RejectedExecutionException("timer owner closed");
            if (active.size() >= MAX_ACTIVE_PER_OWNER) {
                throw new RejectedExecutionException("timer owner admission is full");
            }
            if (!GLOBAL_SLOTS.tryAcquire()) {
                throw new RejectedExecutionException("global timer admission is full");
            }
            // Register cleanup BEFORE arming the timer. A zero-delay
            // expiration is allowed to run before schedule() returns.
            try {
                active.add(future);
                future.whenCompleteRuntime((ignored, failure) -> {
                    // Never retain completed/cancelled timers or their slots.
                    synchronized (admission) {
                        active.remove(future);
                    }
                    GLOBAL_SLOTS.release();
                });
                scheduled.set(CLOCK.schedule(
                        () -> future.completeFromRuntime(null),
                        delayNanos, TimeUnit.NANOSECONDS));
            } catch (RuntimeException | Error failure) {
                // No Future escapes before this block exits. Settle it to
                // run the same exact-once cleanup on failed admission.
                future.failFromRuntime(failure);
                throw failure;
            }
        }
        return future;
    }

    public OresFuture<Void> sleepNanos(long delayNanos) {
        return afterNanos(delayNanos);
    }

    /** Demand-driven fixed-delay interval: only next() arms a timer. */
    public Interval intervalNanos(long periodNanos) {
        if (periodNanos <= 0) {
            throw new IllegalArgumentException("interval period must be positive");
        }
        synchronized (admission) {
            if (closed) throw new RejectedExecutionException("timer owner closed");
            if (intervals.size() >= MAX_INTERVALS_PER_OWNER) {
                throw new RejectedExecutionException("interval owner admission is full");
            }
            Interval interval = new Interval(periodNanos);
            intervals.add(interval);
            return interval;
        }
    }

    public final class Interval implements AutoCloseable {
        private final long periodNanos;
        private long delivered;
        private boolean cancelled;
        private OresFuture<Long> pending;

        private Interval(long periodNanos) {
            this.periodNanos = periodNanos;
        }

        /**
         * Wait for the next tick. Exactly one outstanding next() is permitted;
         * intervals neither pile up missed ticks nor execute guest callbacks.
         */
        public synchronized OresFuture<Long> next() {
            if (cancelled || closed) return OresFuture.failed(
                    new CancellationException("interval owner closed or cancelled"));
            if (pending != null) {
                throw new IllegalStateException("interval already has an awaiting consumer");
            }
            final OresFuture<Void> wake;
            try {
                wake = afterNanos(periodNanos);
            } catch (RejectedExecutionException rejected) {
                // Owner close racing with next() is a cancellation, not a
                // surprise synchronous exception from the interval API.
                if (closed) return OresFuture.failed(
                        new CancellationException("interval owner closed"));
                throw rejected;
            }
            OresFuture<Long> result = new OresFuture<>(() -> wake.cancel(false));
            pending = result;
            result.whenCompleteRuntime((value, error) -> {
                synchronized (Interval.this) {
                    if (pending == result) pending = null;
                }
            });
            wake.whenCompleteRuntime((ignored, failure) -> {
                synchronized (Interval.this) {
                    if (cancelled || result.isDone()) {
                        result.cancel(false);
                    } else if (failure instanceof CancellationException) {
                        result.cancel(false);
                    } else if (failure != null) {
                        result.failFromRuntime(OresFuture.unwrap(failure));
                    } else {
                        long nextIndex = delivered + 1;
                        if (nextIndex <= 0) {
                            result.failFromRuntime(new ArithmeticException(
                                    "interval tick counter overflow"));
                        } else if (result.completeFromRuntime(nextIndex)) {
                            delivered = nextIndex;
                        }
                    }
                }
            });
            return result;
        }

        /** Return true only for the first successful interval cancellation. */
        public synchronized boolean cancel() {
            if (cancelled) return false;
            cancelled = true;
            if (pending != null) pending.cancel(false);
            synchronized (admission) {
                intervals.remove(this);
            }
            return true;
        }

        @Override
        public void close() {
            cancel();
        }

        public synchronized long delivered() {
            return delivered;
        }
    }

    @Override
    public void close() {
        final ArrayList<OresFuture<Void>> toCancel;
        final ArrayList<Interval> intervalsToClose;
        synchronized (admission) {
            if (closed) return;
            closed = true;
            toCancel = new ArrayList<>(active);
            intervalsToClose = new ArrayList<>(intervals);
        }
        // No admission lock may be held while cancellation notifies waiters.
        for (Interval interval : intervalsToClose) interval.cancel();
        for (OresFuture<Void> timer : toCancel) timer.cancel(false);
    }

    int activeIntervalCount() {
        synchronized (admission) {
            return intervals.size();
        }
    }

    int activeTimerCount() {
        synchronized (admission) {
            return active.size();
        }
    }
}
