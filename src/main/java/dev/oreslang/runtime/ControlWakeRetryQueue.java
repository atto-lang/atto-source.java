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
    // Identity-bearing ticket survives movement from pending to in-flight.
    // Cancellation must be able to revoke a callback until its first instruction
    // is admitted, even if it has already left the pending/deferred queues.
    private static final class Retry {
        final Runnable callback;
        final int lane;
        boolean retained;
        boolean directReserved;
        boolean cancelled;
        Retry(Runnable callback, int lane, boolean retained) {
            this(callback, lane, retained, false);
        }
        Retry(Runnable callback, int lane, boolean retained, boolean directReserved) {
            this.callback = callback;
            this.lane = lane;
            this.retained = retained;
            this.directReserved = directReserved;
        }
        Runnable callback() { return callback; }
        int lane() { return lane; }
    }
    private final int capacity;
    private final ArrayDeque<Retry> pending = new ArrayDeque<>();
    private long releaseEpoch;
    private boolean closed;
    private long callbacksFailed;
    // Guarded by this monitor: pending, deferred and dequeued-but-not-started
    // callbacks share one finite admission budget. An in-flight callback no
    // longer retains its slot once execution actually begins.
    private int retainedCallbacks;
    // A stale-epoch retry must not wait behind a full pending queue, but
    // immediate handoffs can still stall between registration and callback
    // start. Bound their outstanding tickets independently so that slow or
    // blocked carrier threads cannot grow aboutToInvoke without limit.
    private int directHandoffs;
    // Per carrier thread and retry queue: nested synchronous slot releases
    // must not consume an unbounded number of Java stack frames. A state
    // contains no callback reference after its outer dispatch returns.
    private static final class RetryDispatch {
        boolean active;
        final ArrayDeque<Retry> later = new ArrayDeque<>();
    }
    private final ThreadLocal<RetryDispatch> retryDispatch = new ThreadLocal<>();
    // Guarded by this monitor: all currently active carrier dispatchers.
    // A parent can be cancelled by a *different* host thread after a retry
    // was dequeued but before the owner carrier invokes it.
    private final Set<RetryDispatch> activeDispatchers = new HashSet<>();
    // Includes dequeued tickets not yet admitted into a callback, including
    // tickets between a physical slot release and the trampoline handoff.
    // Access, cancellation, and callback-start linearization use this monitor.
    private final Set<Retry> aboutToInvoke = new HashSet<>();
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
        Retry ready = null;
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
                    dispatch.later.addLast(new Retry(callback, lane, true));
                    return true;
                }
                if (directHandoffs >= capacity) return false;
                ++directHandoffs;
                ready = new Retry(callback, lane, false, true);
                aboutToInvoke.add(ready);
            } else {
                if (retainedCallbacks >= capacity) return false;
                pending.addLast(new Retry(callback, lane, true));
                ++retainedCallbacks;
            }
        }
        notifyRetry(ready);
        return true;
    }

    void slotReleased() { slotReleased(-1); }

    void slotReleased(int lane) {
        Retry ready;
        synchronized (this) {
            if (closed) return;
            ++releaseEpoch;
            ready = dequeueForLane(lane);
            if (ready != null) aboutToInvoke.add(ready);
        }
        notifyRetry(ready);
    }

    /**
     * Prefer the lane that just freed a physical queue slot. The global
     * fallback is essential: shared admission permits can be released on a
     * different lane even when a waiting parent's own lane is idle.
     * Scan at most 16 entries per release to bound scheduler overhead.
     */
    private Retry dequeueForLane(int lane) {
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
                    return candidate;
                }
            }
        }
        consecutiveAffinityBypasses = 0;
        return pending.removeFirst();
    }

    // Monitor-only helpers. The retention charge is released exactly once:
    // either at callback-start admission or when a ticket is revoked.
    private void releaseRetention(Retry ticket) {
        if (ticket.retained) {
            ticket.retained = false;
            --retainedCallbacks;
        }
        if (ticket.directReserved) {
            ticket.directReserved = false;
            --directHandoffs;
        }
    }

    private void revoke(Retry ticket) {
        ticket.cancelled = true;
        releaseRetention(ticket);
    }

    synchronized void cancel(Runnable callback) {
        pending.removeIf(ticket -> {
            if (ticket.callback() != callback) return false;
            revoke(ticket);
            return true;
        });
        for (RetryDispatch state : activeDispatchers) {
            state.later.removeIf(ticket -> {
                if (ticket.callback() != callback) return false;
                revoke(ticket);
                return true;
            });
        }
        // A dequeued callback is still cancellable before the start gate;
        // removing from pending/later alone is not sufficient.
        var iterator = aboutToInvoke.iterator();
        while (iterator.hasNext()) {
            Retry ticket = iterator.next();
            if (ticket.callback() == callback) {
                revoke(ticket);
                // The carrier still owns its local ticket, whose cancellation
                // bit it checks at the start gate; the queue need not retain
                // another strong reference while that carrier is delayed.
                iterator.remove();
            }
        }
    }

    synchronized void close() {
        closed = true;
        for (Retry ticket : pending) revoke(ticket);
        pending.clear();
        for (RetryDispatch state : activeDispatchers) {
            for (Retry ticket : state.later) revoke(ticket);
            state.later.clear();
        }
        for (Retry ticket : aboutToInvoke) revoke(ticket);
        aboutToInvoke.clear();
    }

    synchronized int pending() {
        return pending.size();
    }

    synchronized int retained() {
        return retainedCallbacks;
    }

    synchronized int directHandoffs() {
        return directHandoffs;
    }

    synchronized long callbacksFailed() {
        return callbacksFailed;
    }

    private void notifyRetry(Retry ready) {
        if (ready == null) return;
        RetryDispatch state = retryDispatch.get();
        if (state == null) {
            state = new RetryDispatch();
            retryDispatch.set(state);
        }
        if (state.active) {
            // A reentrant slot release has already selected this ticket.
            // Transfer ownership without a cancellation-invisible gap.
            synchronized (this) {
                aboutToInvoke.remove(ready);
                if (!closed && !ready.cancelled) {
                    state.later.addLast(ready);
                } else {
                    revoke(ready);
                }
            }
            return;
        }
        synchronized (this) {
            if (closed || ready.cancelled) {
                aboutToInvoke.remove(ready);
                revoke(ready);
                retryDispatch.remove();
                return;
            }
            state.active = true;
            activeDispatchers.add(state);
        }
        try {
            Retry next = ready;
            while (next != null) {
                boolean admitted;
                synchronized (this) {
                    // This lock acquisition is the callback-start boundary.
                    // A preceding cancel/close must win and suppress the turn;
                    // a cancellation after it cannot undo an executing turn.
                    aboutToInvoke.remove(next);
                    admitted = !closed && !next.cancelled;
                    releaseRetention(next);
                }
                if (admitted) {
                    try {
                        // This callback may only attempt scheduler admission.
                        // It must not run a guest actor handler on this carrier.
                        next.callback().run();
                    } catch (RuntimeException | AssertionError failedSubmission) {
                        synchronized (this) {
                            if (callbacksFailed < 0 || callbacksFailed == Long.MAX_VALUE) {
                                callbacksFailed = Long.MAX_VALUE;
                            } else {
                                ++callbacksFailed;
                            }
                        }
                    }
                }
                synchronized (this) {
                    next = state.later.pollFirst();
                    if (next != null) aboutToInvoke.add(next);
                }
            }
        } finally {
            synchronized (this) {
                for (Retry ticket : state.later) revoke(ticket);
                state.later.clear();
                state.active = false;
                activeDispatchers.remove(state);
            }
            retryDispatch.remove();
        }
    }
}
