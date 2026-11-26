package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Trusted, prestarted CONTROL carrier. One executing wake and one reserved
 * pending wake; no dynamically growing executor queue. Host owns close().
 * This is not a native/FFI physical-release verifier. */
final class SupervisorEmergencyControlV1 implements Executor, AutoCloseable {
    private final Object gate = new Object();
    private final Thread worker;
    private final RejectedExecutionException unavailable =
            new RejectedExecutionException("reserved supervisor CONTROL carrier unavailable");
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

    @Override public void close() {
        if (Thread.currentThread() == worker)
            throw new IllegalStateException("CONTROL carrier cannot join itself");
        synchronized (gate) {
            closed = true;
            gate.notifyAll();
        }
        boolean interrupted = false;
        for (;;) {
            try { worker.join(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        synchronized (gate) { pending = null; }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
