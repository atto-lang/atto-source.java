package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Non-blocking admission primitive for a context-owned host/scheduler entry boundary.
 *
 * Host entry is exclusive against guest turns and may wait on the HOST thread.
 * Submitting a guest turn never waits for host entry: turns are held in a bounded
 * FIFO until the host scope exits, then dispatched with a physical-exit lease.
 * The guest reservation lasts through the Runnable's finally, not through its
 * Future's logical completion. Already queued work drains after admission is
 * sealed; new work fails closed.
 *
 * This is an integration primitive, NOT the Truffle entry fix by itself:
 * the external host must obtain its lease before Value.execute, and every
 * physical guest enter/leave path must pass through submitGuest. Do not wire a
 * subset of those paths into a production context.
 */
final class HostSchedulerEntryGate {
    private record GuestTurn(
            Executor executor, Runnable body, Consumer<Throwable> onAdmissionFailure) { }

    private final int queueCapacity;
    private final ArrayDeque<GuestTurn> queued = new ArrayDeque<>();
    private Thread hostOwner;
    private int hostDepth;
    private int hostWaiters;
    private int activeGuests;
    private boolean sealed;

    HostSchedulerEntryGate(int queueCapacity) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("entry gate capacity must be positive");
        }
        this.queueCapacity = queueCapacity;
    }

    HostLease enterHost() throws InterruptedException {
        Thread caller = Thread.currentThread();
        List<GuestTurn> newlyAdmissible = List.of();
        boolean acquired = false;
        try {
            synchronized (this) {
                // Sealing prohibits NEW nested host admission too. An existing
                // owner still gets to close its original lease and drain work.
                rejectIfSealed();
                if (hostOwner == caller) {
                    hostDepth++;
                    return new HostLease(caller);
                }
                hostWaiters++;
                try {
                    while (hostOwner != null || activeGuests != 0) {
                        rejectIfSealed();
                        wait();
                    }
                    rejectIfSealed();
                    hostOwner = caller;
                    hostDepth = 1;
                    acquired = true;
                    return new HostLease(caller);
                } finally {
                    hostWaiters--;
                    // An interrupted/rejected waiter cannot strand a queued
                    // guest turn behind a waiter that no longer exists.
                    if (!acquired) newlyAdmissible = releaseQueuedIfAllowed();
                    notifyAll();
                }
            }
        } finally {
            dispatch(newlyAdmissible);
        }
    }

    /**
     * Scheduling, unlike guest entry, never blocks a carrier behind a host.
     * onAdmissionFailure must settle the owning task if executor.execute fails.
     */
    void submitGuest(Executor executor, Runnable body,
                     Consumer<Throwable> onAdmissionFailure) {
        GuestTurn guest = new GuestTurn(
                Objects.requireNonNull(executor, "executor"),
                Objects.requireNonNull(body, "body"),
                Objects.requireNonNull(onAdmissionFailure, "onAdmissionFailure"));
        boolean dispatchImmediately;
        synchronized (this) {
            rejectIfSealed();
            dispatchImmediately = hostOwner == null && hostWaiters == 0;
            if (dispatchImmediately) {
                activeGuests++;
            } else {
                if (queued.size() >= queueCapacity) {
                    throw new RejectedExecutionException("host/scheduler entry queue full");
                }
                queued.addLast(guest);
            }
        }
        if (dispatchImmediately) dispatchOne(guest);
    }

    synchronized void sealAdmission() {
        sealed = true;
        notifyAll();
    }

    synchronized boolean physicallyQuiescent() {
        return sealed && hostOwner == null && hostWaiters == 0
                && activeGuests == 0 && queued.isEmpty();
    }

    synchronized int pendingGuests() {
        return queued.size();
    }

    private void rejectIfSealed() {
        if (sealed) {
            throw new RejectedExecutionException("context guest entry admission retired");
        }
    }

    /** Called with this monitor held. Each admitted turn owns one reservation. */
    private List<GuestTurn> releaseQueuedIfAllowed() {
        if (hostOwner != null || hostWaiters > 0 || queued.isEmpty()) return List.of();
        List<GuestTurn> ready = new ArrayList<>(queued);
        queued.clear();
        activeGuests += ready.size();
        return ready;
    }

    private void dispatch(List<GuestTurn> ready) {
        for (GuestTurn guest : ready) dispatchOne(guest);
    }

    private void dispatchOne(GuestTurn guest) {
        // Some Executors legally execute inline. If guest code throws from
        // an inline executor, its finally already released the permit; that
        // is NOT an admission rejection and must never release it twice.
        java.util.concurrent.atomic.AtomicBoolean started =
                new java.util.concurrent.atomic.AtomicBoolean();
        try {
            guest.executor().execute(() -> {
                started.set(true);
                try {
                    guest.body().run();
                } finally {
                    physicallyExitedGuest();
                }
            });
        } catch (RuntimeException | Error rejected) {
            if (started.get()) throw rejected;
            // execute() rejected before any worker took ownership.
            physicallyExitedGuest();
            guest.onAdmissionFailure().accept(rejected);
        }
    }

    private void physicallyExitedGuest() {
        List<GuestTurn> ready;
        synchronized (this) {
            if (--activeGuests < 0) {
                throw new IllegalStateException("guest entry reservation underflow");
            }
            notifyAll();
            ready = releaseQueuedIfAllowed();
        }
        dispatch(ready);
    }

    final class HostLease implements AutoCloseable {
        private final Thread thread;
        private boolean closed;

        private HostLease(Thread thread) {
            this.thread = thread;
        }

        @Override
        public void close() {
            List<GuestTurn> ready;
            synchronized (HostSchedulerEntryGate.this) {
                if (Thread.currentThread() != thread) {
                    throw new SecurityException("host entry lease belongs to another thread");
                }
                if (closed) return;
                if (hostOwner != thread || hostDepth <= 0) {
                    throw new IllegalStateException("host entry lease has no active owner");
                }
                closed = true;
                if (--hostDepth == 0) hostOwner = null;
                HostSchedulerEntryGate.this.notifyAll();
                ready = releaseQueuedIfAllowed();
            }
            dispatch(ready);
        }
    }
}
