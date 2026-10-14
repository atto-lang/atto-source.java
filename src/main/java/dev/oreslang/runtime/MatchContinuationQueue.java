package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serial, stack-bounded queue for suspendable match continuations.
 *
 * <p>Callbacks that complete inline schedule work behind the current scan
 * rather than recursively reentering it. An externally resumed callback may
 * start a new drain. Only one drain runs at a time; no producer executes
 * work after a terminal completion. This queue does not create or schedule
 * JVM threads: actor/source-task continuation affinity remains the
 * scheduler's responsibility.</p>
 *
 * <p>Termination suppresses queued future work, not code already executing.
 * When a task is cancelled, its scheduler must also prevent the in-flight
 * continuation from resuming guest evaluation.</p>
 */
public final class MatchContinuationQueue {
    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger workInProgress = new AtomicInteger();
    private final AtomicBoolean terminated = new AtomicBoolean();

    public boolean terminated() {
        return terminated.get();
    }

    /**
     * Run terminal delivery at most once. Terminal delivery may throw:
     * the queue remains terminated in that case.
     */
    public boolean complete(Runnable terminalDelivery) {
        Objects.requireNonNull(terminalDelivery, "terminalDelivery");
        if (!terminated.compareAndSet(false, true)) return false;
        pending.clear();
        terminalDelivery.run();
        return true;
    }

    /** Abort without delivering a terminal value (e.g. fatal host failure). */
    public void abort() {
        terminated.set(true);
        pending.clear();
    }

    public void enqueue(Runnable work) {
        Objects.requireNonNull(work, "work");
        if (terminated.get()) return;
        pending.add(work);
        // A completion may race with the add above. In that case make the
        // task unreachable rather than leave a dormant closure on the queue.
        if (terminated.get()) {
            pending.remove(work);
            return;
        }
        if (workInProgress.getAndIncrement() != 0) return;

        int missed = 1;
        try {
            do {
                Runnable next;
                while ((next = pending.poll()) != null) {
                    if (!terminated.get()) next.run();
                }
                missed = workInProgress.addAndGet(-missed);
            } while (missed != 0);
        } catch (RuntimeException | Error fatal) {
            // Never strand a positive drain counter on an exceptional exit.
            // Propagate to the source task/supervisor; don't execute another
            // arm on a partially failed interpreter continuation.
            abort();
            throw fatal;
        } finally {
            if (terminated.get()) {
                pending.clear();
                workInProgress.set(0);
            }
        }
    }
}
