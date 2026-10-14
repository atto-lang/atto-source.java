package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashSet;
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
    private boolean closed;

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
                throw new RejectedExecutionException("timer admission is full");
            }
            active.add(future);
            try {
                scheduled.set(CLOCK.schedule(
                        () -> future.completeFromRuntime(null),
                        delayNanos, TimeUnit.NANOSECONDS));
            } catch (RuntimeException | Error failure) {
                active.remove(future);
                throw failure;
            }
        }
        // Host-only cleanup. A completed or cancelled timer never retains
        // a slot in the owning context's timer admission set.
        future.whenCompleteRuntime((ignored, error) -> {
            synchronized (admission) {
                active.remove(future);
            }
        });
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
            return new Interval(periodNanos);
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
            if (cancelled) return OresFuture.failed(
                    new CancellationException("interval cancelled"));
            if (pending != null) {
                throw new IllegalStateException("interval already has an awaiting consumer");
            }
            OresFuture<Void> wake = afterNanos(periodNanos);
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
                        result.completeFromRuntime(++delivered);
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
        synchronized (admission) {
            if (closed) return;
            closed = true;
            toCancel = new ArrayList<>(active);
        }
        for (OresFuture<Void> timer : toCancel) timer.cancel(false);
    }

    int activeTimerCount() {
        synchronized (admission) {
            return active.size();
        }
    }
}
