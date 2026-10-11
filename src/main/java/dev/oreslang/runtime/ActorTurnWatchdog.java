package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 */
final class ActorTurnWatchdog {
    static final long OVERRUN_NANOS = TimeUnit.MILLISECONDS.toNanos(4);
    private static final Set<Lease> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService WATCHDOG;

    static {
        ThreadFactory factory = runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("ores-turn-watchdog")
                .unstarted(runnable);
        WATCHDOG = Executors.newSingleThreadScheduledExecutor(factory);
        WATCHDOG.scheduleWithFixedDelay(
                () -> scanAt(System.nanoTime()), 2, 2, TimeUnit.MILLISECONDS);
    }

    private ActorTurnWatchdog() { }

    static Lease enter(Consumer<Lease> onOverrun) {
        Lease lease = new Lease(Objects.requireNonNull(onOverrun, "onOverrun"));
        ACTIVE.add(lease);
        return lease;
    }

    private static void scanAt(long nowNanos) {
        for (Lease lease : ACTIVE) lease.inspect(nowNanos);
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
