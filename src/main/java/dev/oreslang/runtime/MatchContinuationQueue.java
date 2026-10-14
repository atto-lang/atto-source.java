package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Serial, stack-bounded and memory-bounded queue for suspendable match
 * continuations. This class never creates scheduler threads: callers must
 * arrange the appropriate source-task and actor-affine continuation.
 *
 * <p>All terminal/drain/enqueue transitions share one monitor. In particular,
 * a producer can never enqueue after termination or strand a queued callback
 * between the last empty-poll and a concurrent enqueue. Guest work executes
 * outside the monitor so a callback may suspend or enqueue its successor.</p>
 *
 * <p>Termination prevents *new* queue work, but cannot interrupt a Runnable
 * already executing. Actor cancellation must also be enforced by the owning
 * scheduler before it resumes suspended guest source tasks.</p>
 */
public final class MatchContinuationQueue {
    public static final int MAX_PENDING = 4096;

    private final Object gate = new Object();
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private boolean draining;
    private boolean terminated;

    public boolean terminated() {
        synchronized (gate) {
            return terminated;
        }
    }

    /**
     * Atomically seal this match and deliver a terminal continuation once.
     * User delivery executes outside the monitor and may itself throw.
     */
    public boolean complete(Runnable terminalDelivery) {
        Objects.requireNonNull(terminalDelivery, "terminalDelivery");
        synchronized (gate) {
            if (terminated) return false;
            terminated = true;
            pending.clear();
        }
        terminalDelivery.run();
        return true;
    }

    /** Discard pending callbacks, e.g. on cancellation or host failure. */
    public void abort() {
        synchronized (gate) {
            terminated = true;
            pending.clear();
        }
    }

    /**
     * Enqueue and, if needed, start the sole draining caller. A bounded
     * backlog is important if an untrusted host can complete many promises
     * while another guest callback is still running.
     */
    public void enqueue(Runnable work) {
        Objects.requireNonNull(work, "work");
        synchronized (gate) {
            if (terminated) return;
            if (pending.size() >= MAX_PENDING) {
                terminated = true;
                pending.clear();
                throw new IllegalStateException("match continuation queue exceeds "
                        + MAX_PENDING + " pending callbacks");
            }
            pending.addLast(work);
            if (draining) return;
            draining = true;
        }

        try {
            while (true) {
                Runnable next;
                synchronized (gate) {
                    if (terminated) {
                        pending.clear();
                        draining = false;
                        return;
                    }
                    next = pending.pollFirst();
                    if (next == null) {
                        draining = false;
                        return;
                    }
                }
                next.run();
            }
        } catch (RuntimeException | Error fatal) {
            // The queue is irrecoverably failed. Do not run subsequent
            // callbacks or leave the drain lock permanently held.
            abort();
            throw fatal;
        } finally {
            synchronized (gate) {
                if (terminated) draining = false;
            }
        }
    }
}
