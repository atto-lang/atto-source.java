package dev.oreslang.runtime;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Opt-in host scheduler; does not grant guest/actor execution authority. */
public final class SharedReactorPool implements AutoCloseable {
    public interface Reactor {
        /** Bounded (<=10ms), single-owner polling. Enqueue records, never guest callbacks. */
        void poll(boolean mayWait);
        /** Nonblocking, thread-safe, sticky across the next poll entry. Must not throw. */
        void wake();
    }
    private static final int MAX_WORKERS = 256, CAPACITY = 1024;
    // SoA: cold thread handles, hot state and counters occupy separate columns.
    // Strides reduce adjacent-worker cache-line write sharing (not a JVM layout guarantee).
    private final Thread[] threads = new Thread[MAX_WORKERS];
    private final AtomicIntegerArray states = new AtomicIntegerArray(MAX_WORKERS * 16);
    private final AtomicLongArray completed = new AtomicLongArray(MAX_WORKERS * 8);
    private final AtomicInteger pollOwner = new AtomicInteger(-1);
    private final AtomicLong failures = new AtomicLong();
    private final Runnable[] queue = new Runnable[CAPACITY];
    private final Object gate = new Object(), joinGate = new Object();
    private final Reactor reactor;
    private final int maximum;
    private int head, size, started;
    private boolean closing;
    private static final ThreadLocal<SharedReactorPool> CURRENT = new ThreadLocal<>();

    public SharedReactorPool(Reactor reactor) { this(reactor, 2, 2); }
    public SharedReactorPool(Reactor reactor, int initial, int maximum) {
        if (reactor == null || initial < 1 || initial > maximum || maximum > MAX_WORKERS)
            throw new IllegalArgumentException("require 1 <= initial <= maximum <= 256");
        this.reactor = reactor; this.maximum = maximum;
        try { ensure_workers(initial); }
        catch (RuntimeException | Error failure) { close(); throw failure; }
    }
    /** Supervisor admission only; actor count and pending I/O do not grow the pool. */
    public void ensure_workers(int count) {
        synchronized (gate) {
            if (closing || count < 1 || count > maximum)
                throw new IllegalArgumentException("worker growth denied");
            while (started < count) {
                int slot = started;
                Thread worker = new Thread(() -> run(slot), "ores-shared-worker-" + slot);
                worker.setDaemon(true);
                worker.start();
                threads[slot] = worker; ++started;
            }
        }
    }
    public boolean try_submit(Runnable work) {
        if (work == null) return false;
        synchronized (gate) {
            if (closing || size == CAPACITY) return false;
            queue[(head + size) % CAPACITY] = work; ++size;
            gate.notify();
        }
        // The worker acquires the polling lease before testing the ready queue.
        // If no lease exists, a future poller must observe the published work.
        // Avoid a kernel/selector wake for every submission on a hot queue.
        if (pollOwner.get() != -1) wake_reactor();
        return true;
    }
    public void execute(Runnable work) {
        if (!try_submit(work)) throw new RejectedExecutionException("shared ready queue closed/full");
    }
    private void run(int slot) {
        CURRENT.set(this);
        int slices = 0;
        try {
            for (;;) {
                Runnable work = null;
                synchronized (gate) {
                    if (size != 0) {
                        work = queue[head]; queue[head] = null;
                        head = (head + 1) % CAPACITY; --size;
                    } else if (closing) return;
                }
                if (work != null) {
                    states.set(slot * 16, 2);
                    try { work.run(); }
                    catch (Throwable failure) { failures.incrementAndGet(); }
                    finally { completed.incrementAndGet(slot * 8); }
                    if (++slices < 64) continue;
                }
                slices = 0;
                // Once admission is sealed, drain the queue without polling again.
                synchronized (gate) { if (closing) continue; }
                if (pollOwner.compareAndSet(-1, slot)) {
                    try {
                        boolean mayWait;
                        synchronized (gate) { mayWait = !closing && size == 0; }
                        states.set(slot * 16, 3);
                        reactor.poll(mayWait);
                    } catch (Throwable failure) {
                        // Seal admission after reactor failure; drain accepted work.
                        // Repeated broken polls must not turn an idle pool into a spin loop.
                        failures.incrementAndGet();
                        synchronized (gate) { closing = true; gate.notifyAll(); }
                    } finally {
                        pollOwner.set(-1);
                    }
                } else if (work == null) {
                    states.set(slot * 16, 1);
                    synchronized (gate) {
                        if (!closing && size == 0) {
                            try { gate.wait(10); }
                            catch (InterruptedException ignored) { /* close is explicit */ }
                        }
                    }
                }
            }
        } finally {
            states.set(slot * 16, 4); CURRENT.remove();
        }
    }
    public int started_workers() { synchronized (gate) { return started; } }
    public long completed() {
        long count = 0;
        for (int i = 0; i < MAX_WORKERS; ++i) count += completed.get(i * 8);
        return count;
    }
    public long failures() { return failures.get(); }
    private void wake_reactor() {
        try { reactor.wake(); }
        catch (Throwable failure) {
            // Work is already published: never report an ambiguous rejection.
            // A conforming poll has a bounded timeout even if wake is broken.
            failures.incrementAndGet();
        }
    }
    @Override public void close() {
        if (CURRENT.get() == this) throw new IllegalStateException("cannot join current worker");
        synchronized (joinGate) {
            int count;
            synchronized (gate) { closing = true; count = started; gate.notifyAll(); }
            wake_reactor();
            boolean interrupted = false;
            for (int i = 0; i < count; ++i) {
                for (;;) {
                    try { threads[i].join(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
