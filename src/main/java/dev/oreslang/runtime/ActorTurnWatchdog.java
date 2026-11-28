package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Independent observer of running scheduler/actor turns.
 *
 * <p>A scheduler carrier cannot reliably diagnose starvation when every carrier
 * is busy. This daemon therefore runs outside the guest dispatcher pools.
 * It may ONLY request cooperation and record an overrun: it never interrupts,
 * suspends, cancels, or re-enters a guest turn. Compiler-lowered resumable
 * safepoints own physical handoff.</p>
 *
 * <p>Leases have unique object identities (not recycled thread or actor ids).
 * Retirement synchronizes with inspection so a retired turn cannot generate
 * a late notice about a replacement turn on a reused carrier.</p>
 *
 * <p>The monitor thread starts with the first turn, not at class load, and it
 * parks while no turn is running instead of waking every scan period. An idle
 * VM therefore has no watchdog thread before its first turn and no periodic
 * wakeups afterwards.</p>
 */
final class ActorTurnWatchdog {
    static final long OVERRUN_NANOS = TimeUnit.MILLISECONDS.toNanos(4);
    private static final Set<Lease> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final AtomicLong OBSERVER_FAILURES = new AtomicLong();
    private static final AtomicLong SCANS = new AtomicLong();
    private static final long SCAN_PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
    /** Bounds any missed wakeup; the idle path is otherwise wakeup-free. */
    private static final long IDLE_PARK_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final Object START_LOCK = new Object();
    private static volatile Thread monitor;
    /** Set by the monitor just before it parks with no active leases. */
    private static volatile boolean idle;

    private ActorTurnWatchdog() { }

    static Lease enter(Consumer<Lease> onOverrun) {
        Lease lease = new Lease(Objects.requireNonNull(onOverrun, "onOverrun"));
        // Start before registering, so a failed thread start leaks no lease.
        Thread current = monitor;
        if (current == null) current = startMonitor();
        ACTIVE.add(lease);
        if (idle) {
            // ACTIVE.add is published before this read of idle, and the
            // monitor writes idle before re-checking ACTIVE, so one side
            // always observes the other.
            LockSupport.unpark(current);
        }
        return lease;
    }

    /** Completed periodic scans (tests use it to prove idle parking). */
    static long scans() {
        return SCANS.get();
    }

    /** True once the monitor thread exists (diagnostics and tests). */
    static boolean monitorStarted() {
        return monitor != null;
    }

    private static Thread startMonitor() {
        synchronized (START_LOCK) {
            Thread existing = monitor;
            if (existing != null) return existing;
            Thread thread = Thread.ofPlatform()
                    .daemon(true)
                    .name("ores-turn-watchdog")
                    .unstarted(ActorTurnWatchdog::monitorLoop);
            thread.start();
            monitor = thread;
            return thread;
        }
    }

    private static void monitorLoop() {
        for (;;) {
            if (ACTIVE.isEmpty()) {
                idle = true;
                if (ACTIVE.isEmpty()) LockSupport.parkNanos(IDLE_PARK_NANOS);
                idle = false;
                continue;
            }
            LockSupport.parkNanos(SCAN_PERIOD_NANOS);
            scanSafely();
        }
    }

    /** Failed observer callbacks and recoverable monitor errors, never guest exceptions. */
    static long observerFailures() {
        return OBSERVER_FAILURES.get();
    }

    /** A bad observer must never permanently suppress all future scans. */
    private static void scanSafely() {
        SCANS.incrementAndGet();
        try {
            scanAt(System.nanoTime());
        } catch (RuntimeException | AssertionError failure) {
            OBSERVER_FAILURES.incrementAndGet();
        }
    }

    // Package-private for deterministic regression tests; does not run guest work.
    static void scanAt(long nowNanos) {
        for (Lease lease : ACTIVE) {
            try {
                lease.inspect(nowNanos);
            } catch (RuntimeException | AssertionError failure) {
                // The failing lease remains requested and cannot fire twice.
                // Another actor's lease must still be inspected this tick.
                OBSERVER_FAILURES.incrementAndGet();
            }
        }
    }

    static final class Lease implements AutoCloseable {
        private final long startedNanos = System.nanoTime();
        private final Consumer<Lease> onOverrun;
        private final AtomicBoolean requested = new AtomicBoolean();
        private volatile long lastCheckpointNanos = startedNanos;
        private boolean active = true;

        private Lease(Consumer<Lease> onOverrun) {
            this.onOverrun = onOverrun;
        }

        long elapsedNanos(long nowNanos) {
            return Math.max(0L, nowNanos - startedNanos);
        }

        long sinceCheckpointNanos(long nowNanos) {
            return Math.max(0L, nowNanos - lastCheckpointNanos);
        }

        void checkpoint() {
            lastCheckpointNanos = System.nanoTime();
        }

        boolean preemptionRequested() {
            return requested.get();
        }

        private synchronized void inspect(long nowNanos) {
            if (!active || requested.get()
                    || elapsedNanos(nowNanos) < OVERRUN_NANOS) return;
            requested.set(true);
            // The callback is VM-internal, nonblocking accounting only.
            // It must never invoke arbitrary guest code on the monitor thread.
            onOverrun.accept(this);
        }

        @Override
        public synchronized void close() {
            active = false;
            ACTIVE.remove(this);
        }
    }
}
