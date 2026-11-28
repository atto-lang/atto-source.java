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
    private static final class Pending {
        final Runnable task;
        final Consumer<Runnable> submit;
        final Consumer<Throwable> reject;
        final java.util.concurrent.atomic.AtomicBoolean physicalStart;
        // Zero-cost-per-turn state: pending=0, running=1, rejected=2.
        // Synchronization on the existing Pending object linearizes guest
        // entry versus a submitter which enqueued work but then threw.
        private int claim;

        Pending(Runnable task, Consumer<Runnable> submit, Consumer<Throwable> reject,
                java.util.concurrent.atomic.AtomicBoolean physicalStart) {
            this.task = task;
            this.submit = submit;
            this.reject = reject;
            this.physicalStart = physicalStart;
        }
    }

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
        dispatchInternal(task, submit, reject, null);
    }

    private void dispatchInternal(Runnable task, Consumer<Runnable> submit,
                                  Consumer<Throwable> reject,
                                  java.util.concurrent.atomic.AtomicBoolean physicalStart) {
        Pending item = new Pending(
                Objects.requireNonNull(task), Objects.requireNonNull(submit),
                Objects.requireNonNull(reject), physicalStart);
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

    /**
     * Bridge for a region-backed actor turn. A lifetime pin is acquired
     * BEFORE the physical dispatch is enqueued, so STOP cannot reclaim the
     * region while a queued or executing turn still owns the ticket.
     *
     * A rejected/closed submission releases its pin exactly once; an admitted
     * task releases it only after its synchronous Runnable has physically
     * returned (including its TurnExecutor boundary). A cancellation signal
     * alone never releases the running task's pin.
     *
     * This is an opt-in host bridge, not yet enabled for every ActorCell or
     * independently scheduled source Task. Reuse of the pinned region by an
     * arbitrary guest callback still needs compiler ownership validation.
     */
    void dispatchPinned(ReusableLongStateRegion region, Runnable task,
                        Consumer<Runnable> submit, Consumer<Throwable> reject) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(submit, "submit");
        Objects.requireNonNull(reject, "reject");
        long ticket = region.pin();
        java.util.concurrent.atomic.AtomicBoolean released =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean started =
                new java.util.concurrent.atomic.AtomicBoolean();
        Runnable complete = () -> {
            if (released.compareAndSet(false, true)) region.releasePin(ticket);
        };
        try {
            dispatchInternal(() -> {
                try {
                    if (!region.claimPinnedTurn(ticket)) {
                        reject.accept(new RejectedExecutionException(
                                "region retired before pinned turn entry"));
                        return;
                    }
                    task.run();
                } finally {
                    complete.run();
                }
            }, submit, failure -> {
                try {
                    reject.accept(failure);
                } finally {
                    complete.run();
                }
            }, started);
        } catch (RuntimeException | Error failure) {
            // An executor may START a runnable and only then throw from
            // submission. If the physical turn owns the pin, its finally
            // block, not this submitting thread, must release it. If start
            // lost, ActorPhysicalTurnGate has marked the wrapper rejected
            // before propagating failure, so no later execution is possible.
            if (!started.get()) complete.run();
            throw failure;
        }
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
                    boolean cancelledOnClose;
                    synchronized (accepted) {
                        if (accepted.claim != 0) {
                            // The runnable was already rejected OR has been
                            // physically claimed once. A buggy executor that
                            // invokes the same callback twice must never
                            // enter guest code or release one permit twice.
                            return;
                        }
                        // Serialize the decision to BEGIN a guest turn with
                        // close(). A dispatch handed to a stalled executor
                        // must not start guest code after the gate has closed.
                        synchronized (ActorPhysicalTurnGate.this) {
                            cancelledOnClose = closed;
                            accepted.claim = cancelledOnClose ? 2 : 1;
                            // Publish physical-claim ownership BEFORE exposing
                            // the claim to the submitter's error path. Setting
                            // this inside the same critical section closes the
                            // claim-before-task-wrapper scheduling window.
                            if (!cancelledOnClose && accepted.physicalStart != null)
                                accepted.physicalStart.set(true);
                        }
                    }
                    if (cancelledOnClose) {
                        try {
                            accepted.reject.accept(new RejectedExecutionException(
                                    "actor turn gate closed before dispatch started"));
                        } catch (Throwable ignored) {
                            // Revocation must not strand the transferred permit.
                        } finally {
                            release();
                        }
                        return;
                    }
                    try {
                        accepted.task.run();
                    } finally {
                        release();
                    }
                });
                return;
            } catch (RuntimeException | Error failure) {
                synchronized (item) {
                    if (item.claim == 1) {
                        // An inline or already-running turn owns completion;
                        // no duplicate rejection or physical release.
                        throw failure;
                    }
                    if (item.claim != 0) {
                        throw new IllegalStateException(
                                "actor dispatch admitted or rejected twice", failure);
                    }
                    item.claim = 2;
                }
                try {
                    item.reject.accept(failure);
                } catch (Throwable observerFailure) {
                    // A broken lifecycle observer cannot retain the permit.
                    if (observerFailure != failure) {
                        failure.addSuppressed(observerFailure);
                    }
                }
                synchronized (this) {
                    if (closed || pending.isEmpty()) {
                        admitted--;
                        return;
                    }
                    // Transfer the permit to the next bounded FIFO successor.
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
            } catch (Throwable ignored) {
                // One broken dispatch failure observer cannot retain or
                // prevent revocation of subsequent queued actor admissions.
            }
        }
    }
}
