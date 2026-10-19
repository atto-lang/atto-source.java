package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Optional per-runtime, cross-actor-domain physical turn admission.
 *
 * <p>When full, an ActorCell's dispatch sits in this bounded logical queue:
 * it does NOT take an OS worker or park an Oreslang Task. A permit covers the
 * dispatched Runnable through physical completion, including its TurnExecutor
 * boundary. This is not a global budget across multiple ActorRuntimes and
 * does not yet include independently owned OresScheduler Task dispatches.</p>
 */
final class ActorPhysicalTurnGate {
    private record Pending(
            Runnable task, Consumer<Runnable> submit, Consumer<Throwable> reject) { }

    private final int maximum;
    private final int queuedLimit;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private int admitted;
    private int peak;
    private boolean closed;

    ActorPhysicalTurnGate(int maximum, int queuedLimit) {
        if (maximum < 1 || queuedLimit < 1) {
            throw new IllegalArgumentException("invalid physical actor turn budget");
        }
        this.maximum = maximum;
        this.queuedLimit = queuedLimit;
    }

    int maximum() { return maximum; }
    synchronized int peak() { return peak; }
    synchronized int queued() { return pending.size(); }

    void dispatch(Runnable task, Consumer<Runnable> submit, Consumer<Throwable> reject) {
        Pending item = new Pending(
                Objects.requireNonNull(task), Objects.requireNonNull(submit),
                Objects.requireNonNull(reject));
        synchronized (this) {
            if (closed) throw new RejectedExecutionException("actor turn gate closed");
            if (admitted >= maximum) {
                if (pending.size() >= queuedLimit) {
                    throw new RejectedExecutionException("actor turn gate backlog exhausted");
                }
                pending.addLast(item);
                return;
            }
            admitted++;
            peak = Math.max(peak, admitted);
        }
        launch(item);
    }

    private void launch(Pending first) {
        // Executor admission callbacks must ENQUEUE, never run a guest turn
        // synchronously. Iterative failure draining prevents recursive
        // stack growth if thousands of queued dispatches encounter shutdown.
        Pending item = first;
        for (;;) {
            try {
                Pending accepted = item;
                item.submit.accept(() -> {
                    try {
                        accepted.task.run();
                    } finally {
                        release();
                    }
                });
                return;
            } catch (RuntimeException | Error failure) {
                try {
                    item.reject.accept(failure);
                } catch (RuntimeException observerFailure) {
                    failure.addSuppressed(observerFailure);
                }
                synchronized (this) {
                    if (closed || pending.isEmpty()) {
                        admitted--;
                        return;
                    }
                    // Failed admission transfers the permit to the next
                    // bounded FIFO successor, without carrier parking.
                    item = pending.removeFirst();
                }
            }
        }
    }

    private void release() {
        Pending next;
        synchronized (this) {
            if (admitted == 0) throw new IllegalStateException("actor turn gate underflow");
            if (closed || pending.isEmpty()) {
                admitted--;
                return;
            }
            // Transfer the existing permit. No other admission can overtake
            // this FIFO successor, and callbacks execute outside the lock.
            next = pending.removeFirst();
        }
        launch(next);
    }

    /** Called only after ActorRuntime has stopped its owned ActorCells. */
    void close() {
        List<Pending> rejected;
        synchronized (this) {
            if (closed) return;
            closed = true;
            rejected = new ArrayList<>(pending);
            pending.clear();
        }
        for (Pending item : rejected) {
            try {
                item.reject.accept(new RejectedExecutionException("actor turn gate closed"));
            } catch (RuntimeException ignored) {
                // One broken dispatch failure observer cannot retain or
                // prevent revocation of subsequent queued actor admissions.
            }
        }
    }
}
