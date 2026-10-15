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
        if (pending.isEmpty()) return null;
        if (lane >= 0) {
            var cursor = pending.iterator();
            int inspected = 0;
            while (cursor.hasNext() && inspected++ < MAX_LANE_AFFINITY_SCAN) {
                Retry candidate = cursor.next();
                if (candidate.lane() == lane) {
                    cursor.remove();
                    return candidate.callback();
                }
            }
        }
        return pending.removeFirst().callback();
    }

    synchronized void cancel(Runnable callback) {
        // Runnable identity is host-owned and one per parent actor.
        pending.removeIf(next -> next.callback() == callback);
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
        } catch (RuntimeException | AssertionError failedSubmission) {
            // A nonfatal host retry bug must not abort the *unrelated*
            // admitted turn whose physical queue slot just became free.
            // Do not catch VirtualMachineError or ThreadDeath: process-fatal
            // conditions must retain their normal emergency semantics.
            synchronized (this) {
                // Like ActorRuntime's CONTROL loss counters, diagnostics
                // must never wrap negative after sustained failures.
                if (callbacksFailed < 0 || callbacksFailed == Long.MAX_VALUE) {
                    callbacksFailed = Long.MAX_VALUE;
                } else {
                    ++callbacksFailed;
                }
            }
        }
    }
}
