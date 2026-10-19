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

    private void launch(Pending item) {
        try {
            item.submit.accept(() -> {
                try {
                    item.task.run();
                } finally {
                    release();
                }
            });
        } catch (RuntimeException | Error failure) {
            // Admission to the underlying executor failed; do not strand its
            // physical permit, the dispatch identity or later queued actors.
            try {
                item.reject.accept(failure);
            } finally {
                release();
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
            item.reject.accept(new RejectedExecutionException("actor turn gate closed"));
        }
    }
}
