package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

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
    // Guarded by this monitor: pending, deferred and dequeued-but-not-started
    // callbacks share one finite admission budget. An in-flight callback no
    // longer retains its slot once execution actually begins.
    private int retainedCallbacks;
    // Per carrier thread and retry queue: nested synchronous slot releases
    // must not consume an unbounded number of Java stack frames. A state
    // contains no callback reference after its outer dispatch returns.
    private static final class RetryDispatch {
        boolean active;
        final ArrayDeque<Runnable> later = new ArrayDeque<>();
    }
    private final ThreadLocal<RetryDispatch> retryDispatch = new ThreadLocal<>();
    // Guarded by this monitor: all currently active carrier dispatchers.
    // A parent can be cancelled by a *different* host thread after a retry
    // was dequeued but before the owner carrier invokes it.
    private final Set<RetryDispatch> activeDispatchers = new HashSet<>();
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
                // A slot was released after this caller observed its epoch.
                // Retry *this* parent rather than taking an unrelated waiter.
                // During a synchronous retry callback, a caller can repeat
                // stale-epoch registrations arbitrarily many times. Without
                // a quota these bypass the bounded pending queue and append
                // unbounded Runnable references to the thread-local retry
                // trampoline. Enforce a second, capacity-sized bound here.
                RetryDispatch dispatch = retryDispatch.get();
                if (dispatch != null && dispatch.active) {
                    if (retainedCallbacks >= capacity) return false;
                    ++retainedCallbacks;
                    dispatch.later.addLast(callback);
                    return true;
                }
                ready = callback;
            } else {
                if (retainedCallbacks >= capacity) return false;
                pending.addLast(new Retry(callback, lane));
                ++retainedCallbacks;
            }
        }
        notifyRetry(ready, false);
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
        notifyRetry(ready, true);
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
        int previouslyPending = pending.size();
        pending.removeIf(next -> next.callback() == callback);
        retainedCallbacks -= previouslyPending - pending.size();
        // The cancellation thread need not be the carrier thread. Revoke
        // deferred callbacks from *every* active dispatcher under the
        // monitor also used to enqueue and drain deferred callbacks.
        for (RetryDispatch state : activeDispatchers) {
            int previouslyDeferred = state.later.size();
            state.later.removeIf(next -> next == callback);
            retainedCallbacks -= previouslyDeferred - state.later.size();
        }
    }

    synchronized void close() {
        closed = true;
        retainedCallbacks -= pending.size();
        pending.clear();
        for (RetryDispatch state : activeDispatchers) {
            retainedCallbacks -= state.later.size();
            state.later.clear();
        }
    }

    synchronized int pending() {
        return pending.size();
    }

    synchronized int retained() {
        return retainedCallbacks;
    }

    synchronized long callbacksFailed() {
        return callbacksFailed;
    }

    private void notifyRetry(Runnable ready, boolean retained) {
        if (ready == null) return;
        RetryDispatch state = retryDispatch.get();
        if (state == null) {
            state = new RetryDispatch();
            retryDispatch.set(state);
        }
        if (state.active) {
            // A host wake callback can synchronously release the next
            // physical slot. This queue is also read by cancellation from
            // other carrier threads, so always serialize the append.
            synchronized (this) {
                if (!closed) state.later.addLast(ready);
                else if (retained) --retainedCallbacks;
            }
            return;
        }
        synchronized (this) {
            if (closed) {
                if (retained) --retainedCallbacks;
                retryDispatch.remove();
                return;
            }
            state.active = true;
            activeDispatchers.add(state);
        }
        try {
            Runnable next = ready;
            boolean nextRetained = retained;
            while (next != null) {
                // Only an actually executing callback gives back its
                // admission slot. Count dequeued-but-not-started callbacks
                // so another registration cannot refill the same slot.
                synchronized (this) {
                    if (nextRetained) --retainedCallbacks;
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
                synchronized (this) {
                    next = state.later.pollFirst();
                    // Every deferred callback holds a retained admission.
                    nextRetained = next != null;
                }
            }
        } finally {
            // Even after a fatal callback, no dispatcher may retain an
            // executable callback or an active-state registration.
            synchronized (this) {
                retainedCallbacks -= state.later.size();
                state.later.clear();
                state.active = false;
                activeDispatchers.remove(state);
            }
            retryDispatch.remove();
        }
    }
}
