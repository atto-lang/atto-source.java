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
 * permit was released in that interval, registration retries that caller now.
 * Runtime callers coalesce to one stable callback per parent actor.
 */
final class ControlWakeRetryQueue {
    private static final int MAX_LANE_AFFINITY_SCAN = 16;
    private record Retry(Runnable callback, int lane) { }
    private final int capacity;
    private final ArrayDeque<Retry> pending = new ArrayDeque<>();
    private long releaseEpoch;
    private boolean closed;
    private long callbacksFailed;
    // Per carrier thread and retry queue: nested synchronous slot releases
    // must not consume an unbounded number of Java stack frames. A state
    // contains no callback reference after its outer dispatch returns.
    private static final class RetryDispatch {
        boolean active;
        final ArrayDeque<Runnable> later = new ArrayDeque<>();
    }
    private final ThreadLocal<RetryDispatch> retryDispatch = new ThreadLocal<>();
    // Guarded by the queue monitor: bounded affinity preference over FIFO.
    private int consecutiveAffinityBypasses;

    ControlWakeRetryQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("retry capacity must be positive");
        this.capacity = capacity;
    }

    synchronized long epoch() {
        return releaseEpoch;
    }

    boolean register(Runnable callback, long observedEpoch) {
        return register(callback, observedEpoch, -1);
    }

    boolean register(Runnable callback, long observedEpoch, int lane) {
        Objects.requireNonNull(callback, "callback");
        Runnable ready = null;
        synchronized (this) {
            if (closed) return false;
            if (releaseEpoch != observedEpoch) {
                // The slot opened between this *caller's* failed submit
                // and registration. Retry that callback, not an older
                // queued waiter: waking another parent can strand this one
                // indefinitely if no further queue slots are released.
                // Nothing is enqueued, so the pending-capacity bound holds.
                ready = callback;
            } else {
                if (pending.size() >= capacity) return false;
                pending.addLast(new Retry(callback, lane));
            }
        }
        notifyRetry(ready);
        return true;
    }

    void slotReleased() { slotReleased(-1); }

    void slotReleased(int lane) {
        Runnable ready;
        synchronized (this) {
            if (closed) return;
            ++releaseEpoch;
            ready = dequeueForLane(lane);
        }
        notifyRetry(ready);
    }

    /**
     * Prefer the lane that just freed a physical queue slot. The global
     * fallback is essential: shared admission permits can be released on a
     * different lane even when a waiting parent's own lane is idle.
     * Scan at most 16 entries per release to bound scheduler overhead.
     */
    private Runnable dequeueForLane(int lane) {
        if (pending.isEmpty()) {
            consecutiveAffinityBypasses = 0;
            return null;
        }
        // Locality is a preference, not a priority class. Without a bypass
        // budget, a steady stream of matching-lane waiters can permanently
        // starve the oldest supervisor even while slots are being released.
        // Bound both search cost and head-of-line delay to 16 bypasses.
        if (lane >= 0 && consecutiveAffinityBypasses < MAX_LANE_AFFINITY_SCAN) {
            Retry oldest = pending.peekFirst();
            var cursor = pending.iterator();
            int inspected = 0;
            while (cursor.hasNext() && inspected++ < MAX_LANE_AFFINITY_SCAN) {
                Retry candidate = cursor.next();
                if (candidate.lane() == lane) {
                    cursor.remove();
                    if (candidate == oldest) consecutiveAffinityBypasses = 0;
                    else ++consecutiveAffinityBypasses;
                    return candidate.callback();
                }
            }
        }
        consecutiveAffinityBypasses = 0;
        return pending.removeFirst().callback();
    }

    synchronized void cancel(Runnable callback) {
        // Runnable identity is host-owned and one per parent actor.
        pending.removeIf(next -> next.callback() == callback);
        // Reentrant slot releases can dequeue a callback but defer it on
        // this same carrier thread. Cancel the deferred work as well.
        RetryDispatch state = retryDispatch.get();
        if (state != null && state.active) {
            state.later.removeIf(next -> next == callback);
        }
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
        RetryDispatch state = retryDispatch.get();
        if (state == null) {
            state = new RetryDispatch();
            retryDispatch.set(state);
        }
        if (state.active) {
            // A host wake callback can synchronously release the next
            // physical slot. Queue it on this thread rather than recursing.
            state.later.addLast(ready);
            return;
        }
        state.active = true;
        try {
            Runnable next = ready;
            while (next != null) {
                // A close() while a previous callback ran revokes nested
                // wakeups that were queued but not yet invoked. A callback
                // already executing remains in-flight.
                synchronized (this) {
                    if (closed) break;
                }
                try {
                    // The callback can only attempt scheduler admission; it
                    // must never run a guest actor handler on this carrier.
                    next.run();
                } catch (RuntimeException | AssertionError failedSubmission) {
                    // An unrelated admitted actor turn cannot be aborted by
                    // a nonfatal supervisor retry callback failure. Process
                    // fatal errors retain their existing propagation policy.
                    synchronized (this) {
                        if (callbacksFailed < 0 || callbacksFailed == Long.MAX_VALUE) {
                            callbacksFailed = Long.MAX_VALUE;
                        } else {
                            ++callbacksFailed;
                        }
                    }
                }
                next = state.later.pollFirst();
            }
        } finally {
            // A fatal callback must never leave captured retry Runnables
            // pinned in the carrier's ThreadLocal on pooled threads.
            state.later.clear();
            state.active = false;
        }
    }
}
