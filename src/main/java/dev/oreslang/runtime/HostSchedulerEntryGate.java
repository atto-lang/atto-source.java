package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
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
    /**
     * A revocable pre-start admission ticket, not the task's Future.
     * State is monotonic: PENDING -> RUNNING -> SETTLED or
     * PENDING -> SETTLED on cancellation/rejection.
     */
    final class GuestTicket {
        private static final int PENDING = 0;
        private static final int RUNNING = 1;
        private static final int SETTLED = 2;
        private final Executor executor;
        private final Runnable body;
        private final Consumer<Throwable> onAdmissionFailure;
        private final AtomicInteger state = new AtomicInteger(PENDING);
        // Protected by the surrounding HostSchedulerEntryGate monitor.
        private boolean reserved;

        private GuestTicket(Executor executor, Runnable body,
                            Consumer<Throwable> onAdmissionFailure) {
            this.executor = executor;
            this.body = body;
            this.onAdmissionFailure = onAdmissionFailure;
        }

        /**
         * Cancel only before body execution claims the ticket. A previously
         * accepted Runnable will check the fence again before running guest.
         * A false return means the guest started or another transition won.
         */
        boolean cancelBeforeStart() {
            boolean hadReservation;
            synchronized (HostSchedulerEntryGate.this) {
                if (!state.compareAndSet(PENDING, SETTLED)) return false;
                queued.remove(this);
                hadReservation = reserved;
                HostSchedulerEntryGate.this.notifyAll();
            }
            try {
                if (hadReservation) physicallyExitedGuest();
            } finally {
                onAdmissionFailure.accept(
                        new CancellationException("guest entry cancelled before start"));
            }
            return true;
        }
    }

    private final int queueCapacity;
    private final ArrayDeque<GuestTicket> queued = new ArrayDeque<>();
    private Thread hostOwner;
    private int hostDepth;
    private int hostWaiters;
    private int activeGuests;
    private boolean sealed;
    // Prevent a direct/inline guest carrier from trying to obtain a host
    // lease while its own physical guest reservation is still outstanding.
    private final ThreadLocal<Boolean> executingGuest = new ThreadLocal<>();

    HostSchedulerEntryGate(int queueCapacity) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("entry gate capacity must be positive");
        }
        this.queueCapacity = queueCapacity;
    }

    HostLease enterHost() throws InterruptedException {
        if (Boolean.TRUE.equals(executingGuest.get())) {
            throw new SecurityException(
                    "guest turn cannot re-enter host admission before physical exit");
        }
        Thread caller = Thread.currentThread();
        List<GuestTicket> newlyAdmissible = List.of();
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
    GuestTicket submitGuest(Executor executor, Runnable body,
                            Consumer<Throwable> onAdmissionFailure) {
        GuestTicket guest = new GuestTicket(
                Objects.requireNonNull(executor, "executor"),
                Objects.requireNonNull(body, "body"),
                Objects.requireNonNull(onAdmissionFailure, "onAdmissionFailure"));
        boolean dispatchImmediately;
        synchronized (this) {
            rejectIfSealed();
            dispatchImmediately = hostOwner == null && hostWaiters == 0;
            if (dispatchImmediately) {
                guest.reserved = true;
                activeGuests++;
            } else {
                if (queued.size() >= queueCapacity) {
                    throw new RejectedExecutionException("host/scheduler entry queue full");
                }
                queued.addLast(guest);
            }
        }
        if (dispatchImmediately) dispatchOne(guest);
        return guest;
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
    private List<GuestTicket> releaseQueuedIfAllowed() {
        if (hostOwner != null || hostWaiters > 0 || queued.isEmpty()) return List.of();
        List<GuestTicket> ready = new ArrayList<>(queued);
        queued.clear();
        for (GuestTicket guest : ready) guest.reserved = true;
        activeGuests += ready.size();
        return ready;
    }

    private void dispatch(List<GuestTicket> ready) {
        RuntimeException firstException = null;
        Error firstError = null;
        // Every ready ticket owns a permit. One inline task's failure must
        // not abandon later reservations that were already published.
        for (GuestTicket guest : ready) {
            try {
                dispatchOne(guest);
            } catch (RuntimeException failure) {
                if (firstException == null) firstException = failure;
                else if (firstException != failure) firstException.addSuppressed(failure);
            } catch (Error failure) {
                if (firstError == null) firstError = failure;
                else if (firstError != failure) firstError.addSuppressed(failure);
            }
        }
        if (firstError != null) {
            if (firstException != null) firstError.addSuppressed(firstException);
            throw firstError;
        }
        if (firstException != null) throw firstException;
    }

    private void dispatchOne(GuestTicket guest) {
        if (guest.state.get() != GuestTicket.PENDING) return;
        try {
            guest.executor.execute(() -> {
                // If the executor accepted then threw before the Runnable
                // started, cancellation/rejection wins and late work is inert.
                if (!guest.state.compareAndSet(
                        GuestTicket.PENDING, GuestTicket.RUNNING)) return;
                boolean nestedGuest = Boolean.TRUE.equals(executingGuest.get());
                executingGuest.set(Boolean.TRUE);
                try {
                    guest.body.run();
                } finally {
                    if (nestedGuest) executingGuest.set(Boolean.TRUE);
                    else executingGuest.remove();
                    guest.state.set(GuestTicket.SETTLED);
                    physicallyExitedGuest();
                }
            });
        } catch (RuntimeException | Error rejected) {
            if (guest.state.compareAndSet(
                    GuestTicket.PENDING, GuestTicket.SETTLED)) {
                try {
                    physicallyExitedGuest();
                } finally {
                    guest.onAdmissionFailure.accept(rejected);
                }
            } else {
                // Inline execution has already started and owns its release.
                throw rejected;
            }
        }
    }

    private void physicallyExitedGuest() {
        List<GuestTicket> ready;
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
            List<GuestTicket> ready;
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
