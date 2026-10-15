package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-affinity carrier pool with an independent, finite supervisor CONTROL
 * submission budget. Both queues use the same workers; there is no dedicated
 * CONTROL thread or guest-visible scheduling authority.
 *
 * The underlying physical queue must be sized to at least the sum of both
 * budgets, so ordinary scheduling cannot consume CONTROL's reserved slots.
 */
final class ReservedActorDispatcher extends AbstractExecutorService {
    private final ExecutorService delegate;
    private final Semaphore ordinaryPermits;
    private final Semaphore controlPermits;
    private final ControlWakeRetryQueue controlRetries;
    private final AtomicBoolean shutdown = new AtomicBoolean();

    ReservedActorDispatcher(ExecutorService delegate, int ordinaryCapacity, int controlCapacity) {
        if (ordinaryCapacity <= 0 || controlCapacity <= 0) {
            throw new IllegalArgumentException("invalid actor dispatch admission budgets");
        }
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        ordinaryPermits = new Semaphore(ordinaryCapacity);
        controlPermits = new Semaphore(controlCapacity);
        controlRetries = new ControlWakeRetryQueue(ordinaryCapacity);
    }

    long controlRetryEpoch() { return controlRetries.epoch(); }
    boolean registerControlRetry(Runnable callback, long epoch) {
        return controlRetries.register(callback, epoch);
    }
    void cancelControlRetry(Runnable callback) { controlRetries.cancel(callback); }

    ExecutorService delegate() { return delegate; }

    @Override
    public void execute(Runnable command) {
        submit(command, ordinaryPermits);
    }

    void executeControl(Runnable command) {
        submit(command, controlPermits);
    }

    private void submit(Runnable command, Semaphore permits) {
        Objects.requireNonNull(command, "command");
        if (shutdown.get() || !permits.tryAcquire()) {
            throw new RejectedExecutionException("actor scheduling admission unavailable");
        }
        QueuedTurn task = new QueuedTurn(command, permits, controlRetries);
        try {
            if (shutdown.get()) throw new RejectedExecutionException("actor dispatcher closed");
            delegate.execute(task);
        } catch (RuntimeException | Error failure) {
            task.releaseSlot(false);
            throw failure;
        }
    }

    private static final class QueuedTurn implements Runnable {
        private final Runnable command;
        private final Semaphore permits;
        private final ControlWakeRetryQueue retries;
        private final AtomicBoolean released = new AtomicBoolean();

        private QueuedTurn(Runnable command, Semaphore permits, ControlWakeRetryQueue retries) {
            this.command = command;
            this.permits = permits;
            this.retries = retries;
        }

        private void releaseSlot(boolean started) {
            if (released.compareAndSet(false, true)) {
                permits.release();
                if (started) retries.slotReleased();
            }
        }

        @Override public void run() {
            releaseSlot(true);
            command.run();
        }
    }

    @Override public void shutdown() { shutdownNow(); }

    @Override public List<Runnable> shutdownNow() {
        shutdown.set(true);
        controlRetries.close();
        List<Runnable> abandoned = new ArrayList<>();
        for (Runnable pending : delegate.shutdownNow()) {
            if (pending instanceof QueuedTurn turn) {
                turn.releaseSlot(false);
                abandoned.add(turn.command);
            } else {
                // Defensive: a host-supplied executor may return custom tasks.
                abandoned.add(pending);
            }
        }
        return List.copyOf(abandoned);
    }

    @Override public boolean isShutdown() { return shutdown.get(); }
    @Override public boolean isTerminated() { return delegate.isTerminated(); }

    @Override public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
