package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * A bounded, host-only retry registry for supervisor CONTROL admission.
 *
 * A carrier releases an admission permit as it begins work, and immediately
 * signals at most one waiting supervisor. No timers, polling threads, guest
 * objects, or unbounded per-failure callback allocations are required.
 *
 * The epoch closes the gap between a failed enqueue and registration: if a
 * permit was released in that interval, registration retries one waiter now.
 * Runtime callers coalesce to one stable callback per parent actor.
 */
final class ControlWakeRetryQueue {
    private final int capacity;
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private long releaseEpoch;
    private boolean closed;
    private long callbacksFailed;

    ControlWakeRetryQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("retry capacity must be positive");
        this.capacity = capacity;
    }

    synchronized long epoch() {
        return releaseEpoch;
    }

    boolean register(Runnable callback, long observedEpoch) {
        Objects.requireNonNull(callback, "callback");
        Runnable ready = null;
        synchronized (this) {
            if (closed || pending.size() >= capacity) return false;
            pending.addLast(callback);
            if (releaseEpoch != observedEpoch) ready = pending.removeFirst();
        }
        notifyRetry(ready);
        return true;
    }

    void slotReleased() {
        Runnable ready;
        synchronized (this) {
            if (closed) return;
            ++releaseEpoch;
            ready = pending.pollFirst();
        }
        notifyRetry(ready);
    }

    synchronized void cancel(Runnable callback) {
        // Runnable identity is host-owned and one per parent actor.
        pending.removeIf(next -> next == callback);
    }

    synchronized void close() {
        closed = true;
        pending.clear();
    }

    synchronized int pending() {
        return pending.size();
    }

    synchronized long callbacksFailed() {
        return callbacksFailed;
    }

    private void notifyRetry(Runnable ready) {
        if (ready == null) return;
        try {
            // The callback can only attempt a scheduler submission; it cannot
            // execute a guest handler on this carrier.
            ready.run();
        } catch (RuntimeException failedSubmission) {
            synchronized (this) { ++callbacksFailed; }
            // Never let a host callback prevent the current carrier's task
            // from running after its queue permit was released.
        }
    }
}
