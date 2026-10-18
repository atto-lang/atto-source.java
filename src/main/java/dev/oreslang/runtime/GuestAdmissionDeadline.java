package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounds hostile source parse/link/start admission independently of a guest
 * Context's cumulative (long-lived) CPU quota.
 *
 * A single daemon timer controls deadlines. Expired admissions launch a
 * separate virtual-thread revoker so one slow native Context.close(true)
 * cannot block timers for unrelated untrusted Contexts. A successful
 * admission atomically disarms the deadline before exposing an actor lease.
 */
final class GuestAdmissionDeadline implements AutoCloseable {
    private static final int RUNNING = 0;
    private static final int EXPIRED = 1;
    private static final int COMPLETE = 2;
    private static final ScheduledThreadPoolExecutor TIMER = newTimer();

    private final AtomicInteger state = new AtomicInteger(RUNNING);
    private final ScheduledFuture<?> alarm;

    private static ScheduledThreadPoolExecutor newTimer() {
        var timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "ores-untrusted-admission-timer");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    GuestAdmissionDeadline(Duration timeout, Runnable revokeContext) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(revokeContext, "revokeContext");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("untrusted admission deadline must be positive");
        }
        alarm = TIMER.schedule(() -> {
            if (!state.compareAndSet(RUNNING, EXPIRED)) return;
            // Never run native shutdown on the one shared timer thread.
            Thread.ofVirtual().name("ores-untrusted-admission-revoke").start(() -> {
                try { revokeContext.run(); }
                catch (RuntimeException | Error ignored) {
                    // Physical-close failures are recorded by the owner;
                    // the owner retains the failed admission reservation.
                }
            });
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** True only if this admission beat the watchdog's expiry decision. */
    boolean commitIfLive() {
        if (!state.compareAndSet(RUNNING, COMPLETE)) return false;
        alarm.cancel(false);
        return true;
    }

    @Override public void close() {
        if (state.compareAndSet(RUNNING, COMPLETE)) alarm.cancel(false);
    }
}
