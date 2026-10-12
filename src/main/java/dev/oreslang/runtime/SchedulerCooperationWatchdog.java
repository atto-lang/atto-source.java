package dev.oreslang.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide, observational watchdog for active scheduler dispatches.
 *
 * <p>There is one daemon for the JVM, not a monitor/carrier for every actor
 * or scheduler. Suspended tasks are not monitored. A missed soft deadline
 * requests cooperation; it NEVER executes guest code, signals or interrupts
 * carriers, steals an actor lease, or declares cancellation complete.</p>
 *
 * <p>The compiler must still supply a post-commit, heap-resumable safe point.
 * A task in native code or a no-yield critical region cannot be asynchronously
 * suspended by this watchdog.</p>
 */
final class SchedulerCooperationWatchdog {
    static final long GREEDY_DISPATCH_NANOS = TimeUnit.MILLISECONDS.toNanos(4);
    private static final Set<Lease> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService MONITOR = startMonitor();

    private SchedulerCooperationWatchdog() { }

    private static ScheduledExecutorService startMonitor() {
        ThreadFactory factory = runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("ores-cooperation-watchdog")
                .unstarted(runnable);
        ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(factory);
        executor.scheduleWithFixedDelay(() -> {
            // Monitoring is advisory. Never let an unexpected diagnostic
            // exception disable all subsequent scans or reach guest code.
            try {
                inspectAt(System.nanoTime());
            } catch (RuntimeException ignored) {
                // A scheduler quantum remains compiler/cooperative even when
                // the advisory monitor itself cannot scan.
            }
        }, 4, 4, TimeUnit.MILLISECONDS);
        return executor;
    }

    static final class Lease {
        private final AtomicLong watchdogObservations;
        private final AtomicBoolean pressure = new AtomicBoolean();
        private volatile long dispatchStartedNanos;
        private volatile boolean active;

        Lease(AtomicLong watchdogObservations) {
            this.watchdogObservations = watchdogObservations;
        }

        synchronized void enter() {
            pressure.set(false);
            dispatchStartedNanos = System.nanoTime();
            active = true;
            ACTIVE.add(this);
        }

        synchronized void leave() {
            active = false;
            ACTIVE.remove(this);
            dispatchStartedNanos = 0;
            pressure.set(false);
        }

        boolean cooperationRequested() {
            return pressure.get();
        }

        /** Same-thread and deterministic-clock testability without sleeping. */
        synchronized boolean inspect(long nowNanos) {
            // Serialize observation with dispatch enter/exit. An old monitor
            // snapshot must never put a stale request on a *new* turn.
            // This monitor lock is off the compiled loop fast path.
            long started = dispatchStartedNanos;
            if (!active || nowNanos - started < GREEDY_DISPATCH_NANOS) return false;
            if (pressure.compareAndSet(false, true)) {
                watchdogObservations.incrementAndGet();
                return true;
            }
            return false;
        }

        /** A suspended await cannot turn into a greedy dispatch. */
        synchronized void enterAtForTest(long nowNanos) {
            pressure.set(false);
            dispatchStartedNanos = nowNanos;
            active = true;
        }

        synchronized void leaveForTest() {
            active = false;
            dispatchStartedNanos = 0;
            pressure.set(false);
        }
    }

    static void inspectAt(long nowNanos) {
        for (Lease lease : ACTIVE) lease.inspect(nowNanos);
    }
}
