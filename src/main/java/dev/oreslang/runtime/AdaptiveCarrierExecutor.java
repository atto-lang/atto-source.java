package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded JVM execution-carrier pool which creates a worker before growing its
 * ready queue when all existing workers are busy. It never grows in response
 * to a suspended Oreslang task or an outstanding I/O operation alone.
 *
 * <p>Only runnable turns are submitted here. Admission and the growth decision
 * are linearized by a short host-only monitor. Neither I/O nor guest callbacks
 * execute inside this monitor. Tasks are never executed on the submitting thread.
 *
 * <p>The initial core size is the minimum resident capacity. Temporary workers
 * are retired after their configured keepalive when demand subsides.
 */
final class AdaptiveCarrierExecutor extends ThreadPoolExecutor {
    private final int minimum;
    private final Object admission = new Object();
    // All submitted runnable turns, including queued and not-yet-active turns.
    // Guarded by admission: getActiveCount() is only an approximation and can
    // lag behind thread creation during a burst.
    private int admittedTurns;

    AdaptiveCarrierExecutor(int minimum, int maximum, int queueCapacity,
                            ThreadFactory factory) {
        super(validateMinimum(minimum), validateMaximum(minimum, maximum),
                30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(validateCapacity(queueCapacity)),
                Objects.requireNonNull(factory, "factory"), new AbortPolicy());
        this.minimum = minimum;
        allowCoreThreadTimeOut(false);
    }

    private static int validateMinimum(int minimum) {
        if (minimum < 1) throw new IllegalArgumentException("minimum carriers must be >= 1");
        return minimum;
    }

    private static int validateMaximum(int minimum, int maximum) {
        if (maximum < minimum || maximum > 256)
            throw new IllegalArgumentException("maximum carriers must be between min and 256");
        return maximum;
    }

    private static int validateCapacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("ready queue capacity must be >= 1");
        return capacity;
    }

    int minimumCarriers() { return minimum; }

    @Override public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (admission) {
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
        }
    }

    @Override protected void afterExecute(Runnable task, Throwable failure) {
        try {
            super.afterExecute(task, failure);
        } finally {
            synchronized (admission) {
                if (admittedTurns <= 0)
                    throw new IllegalStateException("carrier runnable credit underflow");
                int remaining = --admittedTurns;
                if (!isShutdown()) {
                    int target = Math.min(getMaximumPoolSize(),
                            Math.max(minimum, remaining));
                    if (getCorePoolSize() > target) setCorePoolSize(target);
                }
            }
        }
    }
}
