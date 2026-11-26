package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Trusted, prestarted CONTROL carrier. One executing wake and one reserved
 * pending wake; no dynamically growing executor queue. Host owns close().
 * This is not a native/FFI physical-release verifier. */
final class SupervisorEmergencyControlV1 implements Executor, AutoCloseable {
    private final Object gate = new Object();
    private final Thread worker;
    private final RejectedExecutionException unavailable =
            new RejectedExecutionException("reserved supervisor CONTROL carrier unavailable");
    private final IllegalStateException shutdownTimeout =
            new IllegalStateException("CONTROL carrier termination unverified");
    private Runnable pending;
    private boolean closed;
    private Throwable failure;

    SupervisorEmergencyControlV1() {
        worker = Thread.ofPlatform().daemon(true).name("atto-supervisor-control")
                .unstarted(this::run);
        worker.start();
    }

    @Override public void execute(Runnable wake) {
        Objects.requireNonNull(wake, "wake");
        synchronized (gate) {
            if (closed || failure != null || pending != null) throw unavailable;
            pending = wake;
            gate.notifyAll();
        }
    }

    private void run() {
        try {
            for (;;) {
                final Runnable wake;
                synchronized (gate) {
                    while (pending == null && !closed) gate.wait();
                    if (pending == null) return;
                    wake = pending;
                    pending = null;
                }
                wake.run();
            }
        } catch (InterruptedException | RuntimeException | Error fault) {
            synchronized (gate) {
                failure = fault;
                gate.notifyAll();
            }
        }
    }

    Throwable failure() {
        synchronized (gate) { return failure; }
    }

    /** Closed admission is a restart blocker even without a worker fault. */
    Throwable availabilityFault() {
        synchronized (gate) { return failure != null ? failure : closed ? unavailable : null; }
    }

    /** Trusted host only. Timeout is not a retirement receipt. A timed-out
     * worker retains its accepted wake and may subsequently finish. */
    boolean closeWithin(long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("negative CONTROL shutdown timeout");
        if (Thread.currentThread() == worker)
            throw new IllegalStateException("CONTROL carrier cannot join itself");
        long budget = unit.toNanos(timeout);
        long start = System.nanoTime();
        synchronized (gate) {
            closed = true;
            gate.notifyAll();
        }
        boolean interrupted = false;
        try {
            while (worker.isAlive()) {
                long remaining = budget - (System.nanoTime() - start);
                if (remaining <= 0) return false;
                try {
                    worker.join(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                } catch (InterruptedException ignored) { interrupted = true; }
            }
            synchronized (gate) { pending = null; }
            return true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Override public void close() {
        if (!closeWithin(5, TimeUnit.SECONDS)) throw shutdownTimeout;
    }
}
