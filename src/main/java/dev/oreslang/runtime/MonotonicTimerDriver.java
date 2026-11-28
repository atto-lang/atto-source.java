package dev.oreslang.runtime;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * One monotonic clock thread over one {@link OresTimerWheel}: the host arms
 * exactly one wakeup, for the nearest logical deadline, however many timers
 * are outstanding (contracts/TIMER_ENGINE_V1.md).
 *
 * <p>The driver parks until {@code wheel.nextDeadlineNanos()}. Arming a timer
 * earlier than the parked-until deadline unparks it. The wakeup is race-free:
 * the parked-until deadline is published under the wheel lock before the
 * driver parks, and an arm that lands after that publication observes it and
 * unparks (a permit delivered before {@code park} makes {@code park} return).</p>
 *
 * <p>Expired actions run on the driver thread, outside the lock, in FIFO
 * expiry order, at most {@link #BATCH} per wheel pass. They must only settle
 * runtime state (for example complete an {@code OresFuture}); they never run
 * guest code. An action that throws is reported to the thread's uncaught
 * exception handler and the driver keeps running.</p>
 */
final class MonotonicTimerDriver {
    static final int BATCH = 256;
    static final long TICK_NANOS = 1_000_000L; // 1 ms wheel resolution; deadlines stay exact
    private static final int INITIAL_CAPACITY = 1024;
    private static final int MAX_CAPACITY = 1 << 22; // 4M logical timers per driver
    private static final AtomicInteger IDS = new AtomicInteger();

    private static final class Holder {
        static final MonotonicTimerDriver SHARED = new MonotonicTimerDriver();
    }

    /** Process-wide driver used by {@link OresTimers}. */
    static MonotonicTimerDriver shared() {
        return Holder.SHARED;
    }

    private final long origin = System.nanoTime();
    private final Object lock = new Object();
    private final OresTimerWheel<Runnable> wheel;
    private final long[] firedHandles = new long[BATCH];
    private final Object[] firedActions = new Object[BATCH];
    private final Thread thread;
    // Deadline the driver will next wake for; MIN_VALUE while it is running.
    private volatile long parkedUntil = Long.MIN_VALUE;

    MonotonicTimerDriver() {
        wheel = new OresTimerWheel<>(INITIAL_CAPACITY, MAX_CAPACITY, TICK_NANOS, 0L);
        thread = Thread.ofPlatform().daemon(true)
                .name("ores-monotonic-timer-" + IDS.incrementAndGet())
                .unstarted(this::run);
        thread.start();
    }

    /** Monotonic nanoseconds since this driver started; never negative. */
    long now() {
        return Math.max(0L, System.nanoTime() - origin);
    }

    /**
     * Arm {@code action} to run once {@code delayNanos} have elapsed.
     * Returns a handle for {@link #cancel}. The action may run before this
     * method returns when the delay is zero.
     */
    long armAfter(long delayNanos, Runnable action) {
        if (delayNanos < 0) throw new IllegalArgumentException("negative timer delay");
        long now = now();
        long deadline = delayNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE - 1 : now + delayNanos;
        long handle;
        synchronized (lock) {
            handle = wheel.arm(deadline, action);
        }
        if (handle == 0) throw new RejectedExecutionException("monotonic timer wheel is full");
        if (deadline < parkedUntil) LockSupport.unpark(thread);
        return handle;
    }

    /** True only if the timer was cancelled before its action was dequeued. */
    boolean cancel(long handle) {
        synchronized (lock) {
            return wheel.cancel(handle);
        }
    }

    int outstanding() {
        synchronized (lock) {
            return wheel.size();
        }
    }

    boolean isDriverThread() {
        return Thread.currentThread() == thread;
    }

    private void run() {
        for (;;) {
            int fired;
            long wakeAt;
            synchronized (lock) {
                fired = wheel.poll(now(), BATCH, firedHandles, firedActions);
                wakeAt = fired > 0 ? Long.MIN_VALUE : wheel.nextDeadlineNanos();
                parkedUntil = wakeAt;
            }
            for (int i = 0; i < fired; i++) {
                Runnable action = (Runnable) firedActions[i];
                firedActions[i] = null;
                try {
                    action.run();
                } catch (Throwable failure) {
                    Thread.UncaughtExceptionHandler h = thread.getUncaughtExceptionHandler();
                    if (h != null) h.uncaughtException(thread, failure);
                }
            }
            if (fired > 0) continue;
            if (wakeAt == OresTimerWheel.NO_DEADLINE) {
                LockSupport.park(this);
            } else {
                long wait = wakeAt - now();
                if (wait > 0) LockSupport.parkNanos(this, wait);
            }
        }
    }
}
