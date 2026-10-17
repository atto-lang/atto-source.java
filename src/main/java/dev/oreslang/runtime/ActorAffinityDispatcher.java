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
    private final AtomicInteger nextLane = new AtomicInteger();
    private final AtomicInteger nextHostLane = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    ActorAffinityDispatcher(int parallelism, int capacity, int[] cpus,
                            IntFunction<ExecutorService> factory) {
        if (parallelism < 1 || capacity < 1) throw new IllegalArgumentException("invalid lane limits");
        this.cpus = cpus.clone();
        for (int cpu : cpus) NativeCarrierExecutor.requireAllowedCpu(cpu);
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
        for (int offset = 0; offset < count; ++offset) {
            int index = (start + offset) % count;
            ExecutorService lane = lanes.get(index);
            boolean nativeReady = lane instanceof NativeCarrierExecutor nativeLane
                    && nativeLane.getStartedCarrierCount() > 0;
            boolean javaReady = lane instanceof java.util.concurrent.ThreadPoolExecutor pool
                    && pool.getPoolSize() > 0;
            if (nativeReady || javaReady) {
                executeOnLane(index, notification);
                return;
            }
        }
        // Deterministic cold lane avoids bootstrapping a different native
        // pthread on each queued completion during the first admission burst.
        executeOnLane(0, notification);
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

    void executeOnLane(int lane, Runnable command) {
        Objects.requireNonNull(command);
        if (closed.get() || !queueSlots.tryAcquire()) throw new RejectedExecutionException("actor lane queue unavailable");
        QueuedTurn task = new QueuedTurn(command);
        try {
            if (closed.get()) throw new RejectedExecutionException("actor lanes closed");
            lanes.get(lane).execute(task);
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
