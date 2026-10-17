package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded JVM execution-carrier pool which creates a worker before growing its
 * ready queue when all existing workers are busy. It never grows in response
 * to a suspended Oreslang task or an outstanding I/O operation alone.
 *
 * <p>Only runnable turns are submitted here. Admission and the growth decision
 * are linearized by a short host-only monitor. Neither I/O nor guest callbacks
 * execute inside this monitor. Tasks are never executed on the submitting thread.
 *
 * <p>The initial core size is the minimum resident capacity and may be zero.
 * A zero-minimum pool creates its first carrier only when runnable work is
 * admitted. Temporary workers are retired after their configured keepalive
 * when demand subsides.
 */
final class AdaptiveCarrierExecutor extends ThreadPoolExecutor {
    private static final ThreadLocal<Boolean> FORBID_WORKER_START =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final int minimum;
    private final ReentrantLock admission = new ReentrantLock();
    // All submitted runnable turns, including queued and not-yet-active turns.
    // Guarded by admission: getActiveCount() is only an approximation and can
    // lag behind thread creation during a burst.
    private int admittedTurns;
    private volatile Runnable afterTaskHook;

    /** Host-only bounded completion-drain signal, never a guest callback. */
    void onTaskCompleted(Runnable hook) { afterTaskHook = hook; }

    AdaptiveCarrierExecutor(int minimum, int maximum, int queueCapacity,
                            ThreadFactory factory) {
        super(validateMinimum(minimum), validateMaximum(minimum, maximum),
                30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(validateCapacity(queueCapacity)),
                noGrowthGuard(Objects.requireNonNull(factory, "factory")), new AbortPolicy());
        this.minimum = minimum;
        allowCoreThreadTimeOut(false);
    }

    private static int validateMinimum(int minimum) {
        if (minimum < 0) throw new IllegalArgumentException("minimum carriers must be >= 0");
        return minimum;
    }

    private static int validateMaximum(int minimum, int maximum) {
        if (maximum < 1 || maximum < minimum || maximum > 256)
            throw new IllegalArgumentException(
                    "maximum carriers must be between max(1,min) and 256");
        return maximum;
    }

    private static int validateCapacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("ready queue capacity must be >= 1");
        return capacity;
    }

    private static ThreadFactory noGrowthGuard(ThreadFactory delegate) {
        return task -> {
            if (Boolean.TRUE.equals(FORBID_WORKER_START.get()))
                throw new RejectedExecutionException(
                        "carrier creation forbidden for no-growth admission");
            return delegate.newThread(task);
        };
    }

    /**
     * Admit runnable host work only when it can reuse an already-created JVM
     * carrier. This path never raises corePoolSize and synchronously forbids
     * ThreadFactory worker creation, so reactor-originated completions cannot
     * call Thread.start(). Saturation or a race to zero workers rejects and the
     * NIO owner retains the completion for an off-reactor retry.
     */
    void executeWithoutCarrierGrowth(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (!admission.tryLock())
            throw new RejectedExecutionException("JVM carrier admission is busy");
        try {
            if (isShutdown())
                throw new RejectedExecutionException("carrier pool stopped");
            if (getPoolSize() == 0)
                throw new RejectedExecutionException("no warm JVM carrier available");
            if (admittedTurns == Integer.MAX_VALUE)
                throw new RejectedExecutionException("runnable admission counter exhausted");

            NoGrowthTurn queued = new NoGrowthTurn(task);
            boolean previous = Boolean.TRUE.equals(FORBID_WORKER_START.get());
            FORBID_WORKER_START.set(Boolean.TRUE);
            try {
                super.execute(queued);
                admittedTurns++;
            } catch (RuntimeException rejected) {
                // A guarded ThreadFactory failure can happen after queue offer
                // only if the last worker disappeared concurrently. NoGrowthTurn
                // retains Object identity equality, so rollback cannot remove a
                // different value-equal host notification.
                getQueue().remove(queued);
                throw rejected;
            } finally {
                if (previous) FORBID_WORKER_START.set(Boolean.TRUE);
                else FORBID_WORKER_START.remove();
            }
        } finally {
            admission.unlock();
        }
    }

    int minimumCarriers() { return minimum; }

    private static final class NoGrowthTurn implements Runnable {
        private final Runnable command;
        private NoGrowthTurn(Runnable command) { this.command = command; }
        @Override public void run() { command.run(); }
    }

    @Override public List<Runnable> shutdownNow() {
        admission.lock();
        try {
            List<Runnable> drained = super.shutdownNow();
            if (drained.size() > admittedTurns)
                throw new IllegalStateException("carrier runnable credit underflow during shutdown");
            admittedTurns -= drained.size();

            ArrayList<Runnable> abandoned = new ArrayList<>(drained.size());
            for (Runnable task : drained) {
                abandoned.add(task instanceof NoGrowthTurn wrapper
                        ? wrapper.command : task);
            }
            return List.copyOf(abandoned);
        } finally {
            admission.unlock();
        }
    }

    @Override public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        admission.lock();
        try {
            if (isShutdown()) throw new RejectedExecutionException("carrier pool stopped");
            // Track actual admitted runnable demand, not snapshots of busy
            // workers. A new worker may not yet appear ACTIVE when a burst
            // submits its next turn. Snapshot-based scaling can therefore
            // strand ready work at 18/20 while the other two cores stay idle.
            if (admittedTurns == Integer.MAX_VALUE)
                throw new RejectedExecutionException("runnable admission counter exhausted");
            int demand = admittedTurns + 1;
            int target = Math.min(getMaximumPoolSize(), Math.max(minimum, demand));
            if (getCorePoolSize() < target) setCorePoolSize(target);
            try {
                super.execute(task);
                admittedTurns = demand;
            } catch (RuntimeException rejected) {
                int priorTarget = Math.min(getMaximumPoolSize(),
                        Math.max(minimum, admittedTurns));
                if (!isShutdown() && getCorePoolSize() > priorTarget)
                    setCorePoolSize(priorTarget);
                throw rejected;
            }
        } finally {
            admission.unlock();
        }
    }

    @Override protected void afterExecute(Runnable task, Throwable failure) {
        try {
            super.afterExecute(task, failure);
        } finally {
            admission.lock();
            try {
                if (admittedTurns <= 0)
                    throw new IllegalStateException("carrier runnable credit underflow");
                int remaining = --admittedTurns;
                if (!isShutdown()) {
                    int target = Math.min(getMaximumPoolSize(),
                            Math.max(minimum, remaining));
                    if (getCorePoolSize() > target) setCorePoolSize(target);
                }
            } finally {
                admission.unlock();
            }
        }
        // Never invoke this while admission is locked: it can submit the
        // next bounded NIO completion turn via execute().
        Runnable callback = afterTaskHook;
        if (callback != null) callback.run();
    }
}
