package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * Stable actor lanes. CPU binding, when requested, is mandatory rather than a hint.
 *
 * <p>With {@code demandGrowth} (the runtime's default lane mode), lane
 * carriers start lazily: none exist until the first actor is admitted, which
 * starts lane 0. New actors are placed on already-started lanes, and another
 * lane starts only when a turn is dispatched while every started lane is busy.
 * Spawning idle actors therefore never adds carriers; runnable work does, up
 * to {@code parallelism}. Explicit CPU binding keeps strict placement and
 * starts each pinned lane when its first actor is admitted.</p>
 *
 * <p>Per-lane counters are stored as padded columns (one counter per cache
 * line) so carriers on different lanes do not false-share on dispatch.</p>
 */
final class ActorAffinityDispatcher extends AbstractExecutorService {
    /** Ints per padded slot: 32 * 4 bytes = 128 bytes, one Apple/ARM64 cache line. */
    private static final int PAD = 32;

    private final List<ExecutorService> lanes;
    private final int[] cpus;
    private final boolean demandGrowth;
    private final Semaphore queueSlots;
    /** Turns admitted to a lane but not yet entered; column indexed lane * PAD. */
    private final AtomicIntegerArray queuedByLane;
    /** Turns currently executing on a lane; only maintained with demandGrowth. */
    private final AtomicIntegerArray runningByLane;
    private final int softSpillThreshold;
    private final AtomicLong spills = new AtomicLong();
    private final AtomicLong laneStartFailures = new AtomicLong();
    private final AtomicInteger nextLane = new AtomicInteger();
    private final AtomicInteger spillProbeSeed = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object growLock = new Object();
    /** Started lanes form the prefix [0, startedLanes) under demandGrowth. */
    private volatile int startedLanes;

    ActorAffinityDispatcher(int parallelism, int capacity, int[] cpus,
                            IntFunction<ExecutorService> factory) {
        this(parallelism, capacity, cpus, factory, false);
    }

    ActorAffinityDispatcher(int parallelism, int capacity, int[] cpus,
                            IntFunction<ExecutorService> factory, boolean demandGrowth) {
        if (parallelism < 1 || capacity < 1) throw new IllegalArgumentException("invalid lane limits");
        this.cpus = cpus.clone();
        for (int cpu : cpus) NativeCarrierExecutor.requireAllowedCpu(cpu);
        // Pinned lanes are strict placement; demand growth would move actors.
        this.demandGrowth = demandGrowth && cpus.length == 0;
        this.queueSlots = new Semaphore(capacity);
        this.queuedByLane = new AtomicIntegerArray(parallelism * PAD);
        this.runningByLane = new AtomicIntegerArray(this.demandGrowth ? parallelism * PAD : 0);
        this.softSpillThreshold = Math.max(1, capacity / parallelism);
        var created = new ArrayList<ExecutorService>();
        try {
            for (int i = 0; i < parallelism; i++) created.add(Objects.requireNonNull(factory.apply(i)));
        } catch (RuntimeException | Error failure) {
            created.forEach(ExecutorService::shutdownNow);
            throw failure;
        }
        lanes = List.copyOf(created);
        this.startedLanes = this.demandGrowth ? 0 : parallelism;
    }

    int reserveLane() {
        int span = demandGrowth ? Math.max(1, startedLanes) : lanes.size();
        return Math.floorMod(nextLane.getAndIncrement(), span);
    }
    int cpuForLane(int lane) { return cpus.length == 0 ? -1 : cpus[lane % cpus.length]; }
    boolean nativeCarriers() { return lanes.stream().allMatch(NativeCarrierExecutor.class::isInstance); }
    long softSpills() { return spills.get(); }
    long laneStartFailures() { return laneStartFailures.get(); }
    int parallelism() { return lanes.size(); }

    /** True when an actor should adopt the lane its turn was moved to. */
    boolean rehomesOnSpill() { return demandGrowth; }

    /** Lanes whose carrier has been started (demand growth) or all lanes otherwise. */
    int startedLanes() { return startedLanes; }

    /** OS carriers actually started across all lanes (diagnostics; O(lanes)). */
    int startedCarriers() {
        int total = 0;
        for (ExecutorService lane : lanes) {
            if (lane instanceof NativeCarrierExecutor nativeLane) {
                if (nativeLane.isStarted()) total += nativeLane.getMaximumPoolSize();
            } else if (lane instanceof ThreadPoolExecutor pool) {
                total += pool.getPoolSize();
            }
        }
        return total;
    }

    /**
     * Start the carrier for {@code lane} before an actor placed there is
     * published. Under demand growth this starts the prefix up to that lane;
     * with fixed lanes it starts just that lane. Idempotent.
     *
     * @throws IllegalStateException if a carrier could not be created
     * @throws RejectedExecutionException if the dispatcher is closed
     */
    void ensureLaneStarted(int lane) {
        if (lane < 0 || lane >= lanes.size()) {
            throw new IllegalArgumentException("invalid actor dispatch lane");
        }
        // The admission bootstrap and close share one lock. The caller must
        // not publish an actor after accepting a successful start on a lane
        // that was concurrently shut down.
        synchronized (growLock) {
            if (closed.get()) throw new RejectedExecutionException("actor lanes closed");
            if (!demandGrowth) {
                startLaneCarrier(lanes.get(lane));
                return;
            }
            while (startedLanes <= lane) {
                int next = startedLanes;
                startLaneCarrier(lanes.get(next));
                startedLanes = next + 1;
            }
        }
    }

    private static void startLaneCarrier(ExecutorService lane) {
        if (lane instanceof NativeCarrierExecutor nativeLane) {
            nativeLane.ensureStarted();
        } else if (lane instanceof ThreadPoolExecutor pool && pool.getPoolSize() == 0) {
            // prestartCoreThread() may return false after shutdown. This must
            // fail first-actor admission rather than return a dead ActorRef.
            if (!pool.prestartCoreThread() && pool.getPoolSize() == 0) {
                throw new RejectedExecutionException("actor lane worker failed to start");
            }
        }
    }

    /** Start the next lane; returns its index, or -1 when none remain or start failed. */
    private int tryGrow() {
        synchronized (growLock) {
            int next = startedLanes;
            if (next >= lanes.size() || closed.get()) return -1;
            try {
                startLaneCarrier(lanes.get(next));
            } catch (IllegalStateException | RejectedExecutionException startFailure) {
                // Out of OS threads: keep serving on the lanes we already have.
                laneStartFailures.incrementAndGet();
                return -1;
            }
            startedLanes = next + 1;
            return next;
        }
    }

    private boolean laneBusy(int lane) {
        int slot = lane * PAD;
        return queuedByLane.get(slot) > 0 || runningByLane.get(slot) > 0;
    }

    private int selectLane(int preferred) {
        if (preferred < 0 || preferred >= lanes.size()) {
            throw new IllegalArgumentException("invalid actor dispatch lane");
        }
        if (demandGrowth) return selectGrowingLane(preferred);
        // Explicit CPU binding is strict, not a best-effort migration hint.
        if (cpus.length != 0) return preferred;
        int queued = queuedByLane.get(preferred * PAD);
        if (queued < softSpillThreshold) return preferred;
        // Only a congested soft lane probes other queues. Bound cache-line
        // reads to 64 total; rotated sampling avoids a permanent hot prefix.
        return leastQueued(preferred, queued, lanes.size());
    }

    private int selectGrowingLane(int preferred) {
        int started = startedLanes;
        if (started == 0) {
            ensureLaneStarted(0);
            started = startedLanes;
        }
        if (preferred < started && !laneBusy(preferred)) return preferred;
        for (int i = 0; i < started; i++) {
            if (!laneBusy(i)) return i;
        }
        if (started < lanes.size()) {
            int grown = tryGrow();
            if (grown >= 0) return grown;
            started = startedLanes;
        }
        int home = preferred < started ? preferred : 0;
        int homeQueued = queuedByLane.get(home * PAD);
        if (homeQueued < softSpillThreshold) return home;
        return leastQueued(home, homeQueued, started);
    }

    /** Bounded probe of the first {@code span} lanes in the padded counter column. */
    private int leastQueued(int preferred, int preferredQueued, int span) {
        return SoftLaneSelector.select(preferred, preferredQueued, queuedByLane, PAD, span,
                spillProbeSeed.getAndIncrement());
    }

    /**
     * Dispatch a turn for an actor whose home is {@code lane}.
     *
     * @return the lane the turn was actually placed on
     */
    int executeOnLane(int lane, Runnable command) {
        Objects.requireNonNull(command);
        if (closed.get() || !queueSlots.tryAcquire()) throw new RejectedExecutionException("actor lane queue unavailable");
        // This is a dispatch-placement choice, not an ownership transfer:
        // ActorCell's physical execution lease still guards serial entry.
        int target;
        try {
            target = selectLane(lane);
        } catch (RuntimeException | Error failure) {
            queueSlots.release();
            throw failure;
        }
        QueuedTurn task = new QueuedTurn(command, target);
        queuedByLane.incrementAndGet(target * PAD);
        try {
            if (closed.get()) throw new RejectedExecutionException("actor lanes closed");
            lanes.get(target).execute(task);
            if (target != lane) spills.incrementAndGet();
            return target;
        } catch (RuntimeException | Error failure) {
            task.rejectAdmission();
            throw failure;
        }
    }

    private final class QueuedTurn implements Runnable {
        private final Runnable command;
        private final int assignedLane;
        private final AtomicBoolean released = new AtomicBoolean();
        // 0=pending, 1=entered, 2=rejected. The task itself is the lock:
        // no additional per-dispatch claim allocation in the actor hot path.
        private int claim;
        private QueuedTurn(Runnable command, int assignedLane) {
            this.command = command;
            this.assignedLane = assignedLane;
        }
        private void releaseSlot() {
            if (released.compareAndSet(false, true)) {
                queuedByLane.decrementAndGet(assignedLane * PAD);
                queueSlots.release();
            }
        }
        private void rejectAdmission() {
            synchronized (this) {
                if (claim == 0) claim = 2;
            }
            releaseSlot();
        }
        @Override public void run() {
            synchronized (this) {
                if (claim != 0) return;
                claim = 1;
            }
            if (!demandGrowth) {
                releaseSlot();
                command.run();
                return;
            }
            // Mark running before leaving the queued count so the lane never
            // looks idle while this turn is still on its carrier.
            int slot = assignedLane * PAD;
            runningByLane.incrementAndGet(slot);
            try {
                releaseSlot();
                command.run();
            } finally {
                runningByLane.decrementAndGet(slot);
            }
        }
    }

    @Override public void execute(Runnable command) { executeOnLane(reserveLane(), command); }
    @Override public void shutdown() { shutdownNow(); }
    @Override public List<Runnable> shutdownNow() {
        synchronized (growLock) {
            closed.set(true);
            var abandoned = new ArrayList<Runnable>();
            for (var lane : lanes) {
                // A lane may request dispatcher shutdown from its own active
                // carrier. NativeCarrierExecutor.shutdownNow() deliberately
                // rejects a self-interrupt; use its cooperative shutdown path
                // and still account for every queued turn and queue permit.
                List<Runnable> pendingTurns = lane instanceof NativeCarrierExecutor nativeLane
                        && nativeLane.isCurrentCarrierThread()
                        ? nativeLane.requestShutdownFromCarrier()
                        : lane.shutdownNow();
                for (var pending : pendingTurns) {
                    QueuedTurn task = (QueuedTurn) pending;
                    task.rejectAdmission();
                    abandoned.add(task.command);
                }
            }
            return List.copyOf(abandoned);
        }
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
