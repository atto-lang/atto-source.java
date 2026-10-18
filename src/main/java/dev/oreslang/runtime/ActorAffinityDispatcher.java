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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/** Stable actor lanes. CPU binding, when requested, is mandatory rather than a hint. */
final class ActorAffinityDispatcher extends AbstractExecutorService {
    private final List<ExecutorService> lanes;
    private final int[] cpus;
    private final Semaphore queueSlots;
    private final int queueCapacity;
    private final AtomicInteger nextLane = new AtomicInteger();
    private final AtomicInteger nextHostLane = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    ActorAffinityDispatcher(int parallelism, int capacity, int[] cpus,
                            IntFunction<ExecutorService> factory) {
        if (parallelism < 1 || capacity < 1) throw new IllegalArgumentException("invalid lane limits");
        this.cpus = cpus.clone();
        for (int cpu : cpus) NativeCarrierExecutor.requireAllowedCpu(cpu);
        this.queueCapacity = capacity;
        this.queueSlots = new Semaphore(capacity);
        var created = new ArrayList<ExecutorService>();
        try {
            for (int i = 0; i < parallelism; i++) created.add(Objects.requireNonNull(factory.apply(i)));
        } catch (RuntimeException | Error failure) {
            created.forEach(ExecutorService::shutdownNow);
            throw failure;
        }
        lanes = List.copyOf(created);
    }

    int reserveLane() { return Math.floorMod(nextLane.getAndIncrement(), lanes.size()); }

    /** Host-only NIO notifications may not cause a new native lane bootstrap
     * from a SELECTOR thread. First bootstrap targets lane zero and is
     * performed by the off-reactor admission helper. Subsequent notifications
     * prefer already-live lanes, so JNI publication/Thread.start for a cold
     * affinity lane is never part of normal selector notification delivery.
     *
     * This does NOT invoke guest actor receive and does not weaken per-actor
     * lane placement. A saturated warm lane rejects via bounded queue slots;
     * the NIO manager retains its accepted notification for a later retry.
     */
    void executeHostOnWarmLane(Runnable notification) {
        Objects.requireNonNull(notification, "notification");
        int count = lanes.size();
        int start = Math.floorMod(nextHostLane.getAndIncrement(), count);
        boolean reactor = NioReadinessGroup.onReactorThread();
        int best = -1;
        int bestLoad = Integer.MAX_VALUE;
        for (int offset = 0; offset < count; ++offset) {
            int index = (start + offset) % count;
            int load = verifiedWarmHostLoad(lanes.get(index), reactor);
            if (load < 0) continue;
            if (load < bestLoad) {
                best = index;
                bestLoad = load;
                if (load == 0) break;
            }
        }

        RejectedExecutionException lastRejection = null;
        if (best >= 0) {
            try {
                executeOnLane(best, notification, true);
                return;
            } catch (RejectedExecutionException rejected) {
                lastRejection = rejected;
            }

            // Load is only a snapshot. The selected warm lane can race to a
            // full queue or a contended no-growth admission lock after the
            // scan. Do not strand runnable completion work while another
            // verified warm lane can accept it now.
            for (int offset = 0; offset < count; ++offset) {
                int index = (start + offset) % count;
                if (index == best || verifiedWarmHostLoad(lanes.get(index), reactor) < 0)
                    continue;
                try {
                    executeOnLane(index, notification, true);
                    return;
                } catch (RejectedExecutionException rejected) {
                    lastRejection = rejected;
                }
            }
        }

        if (lastRejection != null) throw lastRejection;

        // The first completion is submitted by the off-selector bootstrap
        // helper. If every verified lane is still cold (or already retiring),
        // a selector must NEVER trigger nativeStart() or Thread.start().
        if (reactor)
            throw new RejectedExecutionException(
                    "no fully started actor carrier available for reactor completion");
        executeOnLane(0, notification);
    }

    private static int verifiedWarmHostLoad(ExecutorService lane, boolean reactor) {
        if (lane instanceof NativeCarrierExecutor nativeLane) {
            if (!nativeLane.readyForNoBootstrapAdmission()) return -1;
            return nativeLane.getActiveCount() + nativeLane.getQueueSize();
        }
        if (lane instanceof AdaptiveCarrierExecutor javaLane) {
            if (!javaLane.readyForNoGrowthAdmission()) return -1;
            return javaLane.getActiveCount() + javaLane.getQueue().size();
        }
        if (lane instanceof java.util.concurrent.ThreadPoolExecutor pool) {
            // Generic pools are usable by the trusted off-reactor bootstrap,
            // but cannot prove no ThreadFactory/inline callback behavior to a
            // selector.
            if (reactor || pool.getPoolSize() == 0) return -1;
            return pool.getActiveCount() + pool.getQueue().size();
        }
        return -1;
    }
    int cpuForLane(int lane) { return cpus.length == 0 ? -1 : cpus[lane % cpus.length]; }
    boolean nativeCarriers() { return lanes.stream().allMatch(NativeCarrierExecutor.class::isInstance); }
    int startedCarrierCount() {
        int count = 0;
        for (var lane : lanes) {
            if (lane instanceof NativeCarrierExecutor nativeLane) {
                count += nativeLane.getStartedCarrierCount();
            } else if (lane instanceof java.util.concurrent.ThreadPoolExecutor pool) {
                count += pool.getPoolSize();
            }
        }
        return count;
    }

    int activeCarrierCount() {
        int count = 0;
        for (var lane : lanes) {
            if (lane instanceof NativeCarrierExecutor nativeLane) {
                count += nativeLane.getActiveCount();
            } else if (lane instanceof java.util.concurrent.ThreadPoolExecutor pool) {
                count += pool.getActiveCount();
            }
        }
        return count;
    }

    int queuedTaskCount() {
        return queueCapacity - queueSlots.availablePermits();
    }

    int queueCapacity() { return queueCapacity; }

    int maximumCarrierCount() {
        int count = 0;
        for (var lane : lanes) {
            if (lane instanceof NativeCarrierExecutor nativeLane) {
                count += nativeLane.getMaximumPoolSize();
            } else if (lane instanceof java.util.concurrent.ThreadPoolExecutor pool) {
                count += pool.getMaximumPoolSize();
            } else {
                count += 1;
            }
        }
        return count;
    }

    void executeOnLane(int lane, Runnable command) {
        executeOnLane(lane, command, false);
    }

    private void executeOnLane(int lane, Runnable command, boolean noBootstrap) {
        Objects.requireNonNull(command);
        if (lane < 0 || lane >= lanes.size())
            throw new IllegalArgumentException("actor lane outside configured range");
        if (closed.get() || !queueSlots.tryAcquire())
            throw new RejectedExecutionException("actor lane queue unavailable");
        QueuedTurn task = new QueuedTurn(command);
        try {
            if (closed.get()) throw new RejectedExecutionException("actor lanes closed");
            ExecutorService executor = lanes.get(lane);
            if (noBootstrap && executor instanceof NativeCarrierExecutor nativeLane)
                nativeLane.executeWithoutBootstrap(task);
            else if (noBootstrap && executor instanceof AdaptiveCarrierExecutor javaLane)
                javaLane.executeWithoutCarrierGrowth(task);
            else if (noBootstrap && NioReadinessGroup.onReactorThread())
                // A generic ExecutorService (including a regular
                // ThreadPoolExecutor) cannot prove that execute() will not
                // synchronously invoke ThreadFactory/Thread.start or run
                // callbacks inline. An unverified host lane is not a legal
                // selector submission destination.
                throw new RejectedExecutionException(
                        "reactor completion requires a verified no-bootstrap carrier lane");
            else
                executor.execute(task);
        } catch (RuntimeException | Error failure) {
            task.releaseSlot();
            throw failure;
        }
    }

    private final class QueuedTurn implements Runnable {
        private final Runnable command;
        private final AtomicBoolean released = new AtomicBoolean();
        private QueuedTurn(Runnable command) { this.command = command; }
        private void releaseSlot() { if (released.compareAndSet(false, true)) queueSlots.release(); }
        @Override public void run() {
            releaseSlot();
            command.run();
        }
    }

    @Override public void execute(Runnable command) { executeOnLane(reserveLane(), command); }
    @Override public void shutdown() { shutdownNow(); }
    @Override public List<Runnable> shutdownNow() {
        closed.set(true);
        var abandoned = new ArrayList<Runnable>();
        for (var lane : lanes) for (var pending : lane.shutdownNow()) {
            QueuedTurn task = (QueuedTurn) pending;
            task.releaseSlot();
            abandoned.add(task.command);
        }
        return List.copyOf(abandoned);
    }
    @Override public boolean isShutdown() { return closed.get(); }
    @Override public boolean isTerminated() { return lanes.stream().allMatch(ExecutorService::isTerminated); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long budget = unit.toNanos(timeout);
        long start = System.nanoTime();
        for (var lane : lanes) {
            if (!lane.awaitTermination(Math.max(0, budget - (System.nanoTime() - start)), TimeUnit.NANOSECONDS)) return false;
        }
        return true;
    }
}
