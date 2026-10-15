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
    /** Bounded, physically reserved admission for supervisor CONTROL turns. */
    private final Semaphore controlQueueSlots;
    private final AtomicInteger nextLane = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    ActorAffinityDispatcher(int parallelism, int capacity, int[] cpus,
                            IntFunction<ExecutorService> factory) {
        this(parallelism, capacity, 0, cpus, factory);
    }

    ActorAffinityDispatcher(int parallelism, int capacity, int controlCapacity, int[] cpus,
                            IntFunction<ExecutorService> factory) {
        if (parallelism < 1 || capacity < 1 || controlCapacity < 0)
            throw new IllegalArgumentException("invalid lane limits");
        this.cpus = cpus.clone();
        for (int cpu : cpus) NativeCarrierExecutor.requireAllowedCpu(cpu);
        this.queueSlots = new Semaphore(capacity);
        this.controlQueueSlots = new Semaphore(controlCapacity);
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
    int cpuForLane(int lane) { return cpus.length == 0 ? -1 : cpus[lane % cpus.length]; }
    boolean nativeCarriers() { return lanes.stream().allMatch(NativeCarrierExecutor.class::isInstance); }

    void executeOnLane(int lane, Runnable command) {
        executeOnLane(lane, command, queueSlots);
    }

    /** Only the supervisor CONTROL scheduler calls this; guest mail cannot. */
    void executeControlOnLane(int lane, Runnable command) {
        executeOnLane(lane, command, controlQueueSlots);
    }

    private void executeOnLane(int lane, Runnable command, Semaphore permits) {
        Objects.requireNonNull(command);
        if (lane < 0 || lane >= lanes.size()) {
            throw new IllegalArgumentException("invalid actor lane");
        }
        if (closed.get() || !permits.tryAcquire())
            throw new RejectedExecutionException("actor lane admission unavailable");
        QueuedTurn task = new QueuedTurn(command, permits);
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
        private final Semaphore permits;
        private final AtomicBoolean released = new AtomicBoolean();
        private QueuedTurn(Runnable command, Semaphore permits) {
            this.command = command;
            this.permits = permits;
        }
        private void releaseSlot() { if (released.compareAndSet(false, true)) permits.release(); }
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
