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
            // The JDK's standard bounded-queue policy expands only when full.
            // Raise core size before submit instead, but only on runnable demand.
            // Initial lazy startup remains limited to the configured minimum.
            int live = getPoolSize();
            if (live >= minimum && getActiveCount() >= live
                    && getCorePoolSize() < getMaximumPoolSize()) {
                setCorePoolSize(getCorePoolSize() + 1);
            }
            super.execute(task);
        }
    }

    @Override protected void afterExecute(Runnable task, Throwable failure) {
        try {
            super.afterExecute(task, failure);
        } finally {
            synchronized (admission) {
                // Excess workers can retire after keepalive. A queued task or
                // busy population above minimum retains the expanded capacity.
                if (!isShutdown() && getQueue().isEmpty()
                        && getActiveCount() <= minimum && getCorePoolSize() > minimum) {
                    setCorePoolSize(minimum);
                }
            }
        }
    }
}
