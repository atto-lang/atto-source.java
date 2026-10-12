package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One language-context physical admission boundary. A host lease covers the
 * embedding Value.execute entry; a guest lease covers TruffleContext.enter,
 * the guest Runnable, and TruffleContext.leave.
 *
 * Root/actor/async task submission is unchanged: only admitted physical
 * carriers wait for a host to leave. The lock is released on guest suspension
 * because a scheduler turn returns before the Future awaits its continuation.
 * This class deliberately does not authenticate any arbitrary Polyglot Value:
 * the invoking bridge must separately bind the ticket to this exact context.
 */
final class ContextEntryCoordinator {
    private static final long HOST_ENTRY_TIMEOUT_SECONDS = 5;
    private final ReentrantReadWriteLock entry = new ReentrantReadWriteLock(true);
    private volatile boolean hostSealed;
    private volatile boolean guestsRetired;

    HostLease enterHost() {
        if (entry.getReadHoldCount() != 0 && !entry.isWriteLockedByCurrentThread()) {
            throw new SecurityException(
                    "a physical guest turn cannot upgrade to host entry");
        }
        if (hostSealed) {
            throw new RejectedExecutionException("context host entry is retired");
        }
        boolean locked;
        try {
            locked = entry.writeLock().tryLock(
                    HOST_ENTRY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException(
                    "interrupted waiting for exclusive host entry", interrupted);
        }
        if (!locked) {
            throw new RejectedExecutionException(
                    "timed out waiting for physical guest turn exit");
        }
        if (hostSealed) {
            entry.writeLock().unlock();
            throw new RejectedExecutionException("context host entry is retired");
        }
        return new HostLease(Thread.currentThread());
    }

    final class HostLease implements AutoCloseable {
        private final Thread owner;
        private boolean closed;

        private HostLease(Thread owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (Thread.currentThread() != owner) {
                throw new SecurityException(
                        "host entry lease must be released on its entering thread");
            }
            if (closed) {
                throw new IllegalStateException("host entry lease already released");
            }
            closed = true;
            entry.writeLock().unlock();
        }
    }

    void executeGuest(Runnable guest) {
        Objects.requireNonNull(guest, "guest");
        if (guestsRetired) {
            throw new RejectedExecutionException("guest physical entry is retired");
        }
        // Only the carrier waits; no actor or scheduler submission thread
        // blocks while holding its admission/registry monitor.
        entry.readLock().lock();
        try {
            if (guestsRetired) {
                throw new RejectedExecutionException("guest physical entry is retired");
            }
            guest.run();
        } finally {
            entry.readLock().unlock();
        }
    }

    void beginRetirement() {
        if (entry.isWriteLockedByCurrentThread() || entry.getReadHoldCount() > 0) {
            throw new IllegalStateException(
                    "cannot close context from an entered host or guest turn");
        }
        hostSealed = true;
    }

    void finishRetirement() {
        // All actor, VM and async physical carriers must have completed their
        // own cleanup before this point. Wait for an in-flight host lease too.
        entry.writeLock().lock();
        try {
            guestsRetired = true;
            hostSealed = true;
        } finally {
            entry.writeLock().unlock();
        }
    }

    boolean hostSealed() {
        return hostSealed;
    }

    boolean guestsRetired() {
        return guestsRetired;
    }
}
