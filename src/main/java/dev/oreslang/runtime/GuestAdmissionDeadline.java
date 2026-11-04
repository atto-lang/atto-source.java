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
    // Keep elapsed-time subtraction unambiguous even across nanoTime wrap.
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);
    private static final ScheduledThreadPoolExecutor TIMER = newTimer();

    private final AtomicInteger state = new AtomicInteger(RUNNING);
    private final long startNanos;
    private final long timeoutNanos;
    private final Runnable revokeContext;
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
        if (timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "untrusted admission deadline must be positive and <=5 minutes");
        }
        this.startNanos = System.nanoTime();
        this.timeoutNanos = timeout.toNanos();
        this.revokeContext = revokeContext;
        alarm = TIMER.schedule(this::expireAndRevoke, timeoutNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Timer callbacks can be delayed by executor starvation or scheduling.
     * Enforce the absolute monotonic deadline at the point of publication,
     * independent of whether the asynchronous alarm has run.
     */
    boolean commitIfLive() {
        if (state.get() != RUNNING) return false;
        if (System.nanoTime() - startNanos >= timeoutNanos) {
            expireAndRevoke();
            alarm.cancel(false);
            return false;
        }
        if (!state.compareAndSet(RUNNING, COMPLETE)) return false;
        // The thread may have stalled between its first clock read and CAS.
        // Never publish a late admission merely because TIMER was starved.
        if (System.nanoTime() - startNanos >= timeoutNanos) {
            state.set(EXPIRED); // only this winner can transition COMPLETE
            dispatchRevocation();
            alarm.cancel(false);
            return false;
        }
        alarm.cancel(false);
        return true;
    }

    private void expireAndRevoke() {
        if (!state.compareAndSet(RUNNING, EXPIRED)) return;
        // A nanosecond-scale timeout can fire before the constructor assigns
        // its final alarm reference: do not touch alarm on the timer thread.
        dispatchRevocation();
    }

    private void dispatchRevocation() {
        // Native Context.close(true) must never run on the one timer thread.
        Thread.ofVirtual().name("ores-untrusted-admission-revoke").start(() -> {
            try { revokeContext.run(); }
            catch (RuntimeException | Error ignored) {
                // Owning supervisor/fence records unsuccessful teardown and
                // refuses new allocation until physical close is confirmed.
            }
        });
    }

    @Override public void close() {
        if (state.compareAndSet(RUNNING, COMPLETE)) alarm.cancel(false);
    }
}
