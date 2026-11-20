package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Fixed carrier pool with soft continuation locality
 * (contract {@code CONTINUATION_LOCALITY_V1}, rule 5).
 *
 * <p>Each carrier owns a small local queue; there is also one shared FIFO
 * injection queue. A task submitted with a preferred carrier (the carrier that
 * last ran that logical task) goes to that carrier's local queue while the
 * queue holds fewer than {@code localSlack} entries; otherwise it goes to the
 * shared queue. Nothing about this is a physical CPU binding.</p>
 *
 * <p>The preference is bounded in time. An idle carrier may steal another
 * carrier's local entry once that entry has waited {@code graceNanos}, so an
 * awaiting task never reserves a busy carrier while another carrier sits
 * idle for longer than the grace. With a grace of zero, entries are stealable
 * immediately and the pool behaves as a plain work-stealing pool.</p>
 *
 * <p>Producer and completion threads only enqueue. A task never runs inline
 * on the submitting thread. Queued work is bounded by {@code queueCapacity};
 * running tasks do not count against it, matching the bounded
 * {@code ThreadPoolExecutor} queues this replaces.</p>
 *
 * <p>A carrier checks the shared queue before its own local queue every
 * {@value #SHARED_CHECK_INTERVAL} dequeues, so a carrier that keeps
 * re-queueing work for itself cannot starve shared submissions.</p>
 */
final class LocalityCarrierPool extends AbstractExecutorService {
    /** No carrier preference: the task goes to the shared queue. */
    static final int NO_PREFERENCE = -1;
    static final int DEFAULT_LOCAL_SLACK = 2;
    static final long DEFAULT_GRACE_NANOS = TimeUnit.MICROSECONDS.toNanos(
            boundedGraceMicros(Long.getLong("ores.scheduler.locality.grace-micros", 50L)));
    static final int SHARED_CHECK_INTERVAL = 61;

    private static final int RUNNING = 0;
    private static final int SHUTDOWN = 1;
    private static final int STOP = 2;
    private static final long MAX_GRACE_MICROS = 10_000L;

    private static final ThreadLocal<Worker> CURRENT = new ThreadLocal<>();

    private static long boundedGraceMicros(long micros) {
        if (micros < 0 || micros > MAX_GRACE_MICROS) {
            throw new IllegalArgumentException(
                    "ores.scheduler.locality.grace-micros must be in [0," + MAX_GRACE_MICROS + "]");
        }
        return micros;
    }

    /** Placement and dequeue counters, for tests and observation only. */
    record Stats(long preferredPlacements, long sharedPlacements,
                 long localRuns, long sharedRuns, long steals) { }

    private final Worker[] workers;
    private final ThreadFactory factory;
    private final ConcurrentLinkedQueue<Entry> shared = new ConcurrentLinkedQueue<>();
    /** Global submission order across the shared queue and every local deque. */
    private final java.util.concurrent.atomic.AtomicLong submissions = new java.util.concurrent.atomic.AtomicLong();
    private final Semaphore queueSlots;
    private final long graceNanos;
    private final int localSlack;
    private final AtomicInteger runState = new AtomicInteger(RUNNING);
    private final AtomicInteger parkedCount = new AtomicInteger();
    private final AtomicInteger wakeCursor = new AtomicInteger();
    private final AtomicInteger liveWorkers = new AtomicInteger();
    private final CountDownLatch terminated;
    private final Object startLock = new Object();
    private boolean startClosed;
    private int startedWorkers;

    private final LongAdder preferredPlacements = new LongAdder();
    private final LongAdder sharedPlacements = new LongAdder();
    private final LongAdder localRuns = new LongAdder();
    private final LongAdder sharedRuns = new LongAdder();
    private final LongAdder steals = new LongAdder();

    LocalityCarrierPool(int carriers, int queueCapacity, ThreadFactory factory) {
        this(carriers, queueCapacity, factory, DEFAULT_GRACE_NANOS, DEFAULT_LOCAL_SLACK);
    }

    LocalityCarrierPool(int carriers, int queueCapacity, ThreadFactory factory,
                        long graceNanos, int localSlack) {
        if (carriers < 1) throw new IllegalArgumentException("carrier count must be positive");
        if (queueCapacity < 1) throw new IllegalArgumentException("queue capacity must be positive");
        if (graceNanos < 0) throw new IllegalArgumentException("grace must be non-negative");
        if (localSlack < 1) throw new IllegalArgumentException("local slack must be positive");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.queueSlots = new Semaphore(queueCapacity);
        this.graceNanos = graceNanos;
        this.localSlack = localSlack;
        this.workers = new Worker[carriers];
        for (int i = 0; i < carriers; i++) workers[i] = new Worker(i);
        this.terminated = new CountDownLatch(carriers);
    }

    /**
     * Start every carrier. Returns the number started by this call. If any
     * carrier fails to start, the pool is stopped and the failure rethrown.
     */
    int prestartAll() {
        synchronized (startLock) {
            if (startClosed) {
                if (runState.get() != RUNNING) throw new RejectedExecutionException("carrier pool is shut down");
                return 0;
            }
            int before = startedWorkers;
            try {
                for (Worker worker : workers) {
                    Thread thread = factory.newThread(worker);
                    if (thread == null) throw new IllegalStateException("carrier thread factory returned null");
                    worker.thread = thread;
                    liveWorkers.incrementAndGet();
                    startedWorkers++;
                    try {
                        thread.start();
                    } catch (RuntimeException | Error failure) {
                        liveWorkers.decrementAndGet();
                        startedWorkers--;
                        worker.thread = null;
                        throw failure;
                    }
                }
            } catch (RuntimeException | Error failure) {
                startClosed = true;
                runState.set(STOP);
                for (int i = startedWorkers; i < workers.length; i++) terminated.countDown();
                wakeAll(true);
                throw failure;
            }
            startClosed = true;
            return startedWorkers - before;
        }
    }

    int carrierCount() {
        return workers.length;
    }

    /** Carriers started and not yet exited. */
    int liveCarriers() {
        return liveWorkers.get();
    }

    /** The calling thread's carrier slot in this pool, or {@link #NO_PREFERENCE}. */
    int currentSlot() {
        Worker worker = CURRENT.get();
        return worker != null && worker.pool() == this ? worker.slot : NO_PREFERENCE;
    }

    long graceNanos() {
        return graceNanos;
    }

    Stats stats() {
        return new Stats(preferredPlacements.sum(), sharedPlacements.sum(),
                localRuns.sum(), sharedRuns.sum(), steals.sum());
    }

    @Override
    public void execute(Runnable task) {
        executeNear(task, NO_PREFERENCE);
    }

    /**
     * Enqueue {@code task}, preferring carrier {@code preferred} when it is a
     * valid slot whose local queue is within the slack. An out-of-range
     * preference only forfeits locality.
     */
    void executeNear(Runnable task, int preferred) {
        Objects.requireNonNull(task, "task");
        if (runState.get() != RUNNING) throw new RejectedExecutionException("carrier pool is shut down");
        if (!queueSlots.tryAcquire()) throw new RejectedExecutionException("carrier pool queue is full");
        Worker home = preferred >= 0 && preferred < workers.length ? workers[preferred] : null;
        if (home != null && graceNanos > 0 && home.thread != null && !home.exited) {
            if (home.localSize.incrementAndGet() <= localSlack) {
                Entry entry = new Entry(task, System.nanoTime(), submissions.incrementAndGet());
                home.local.offerLast(entry);
                preferredPlacements.increment();
                if (runState.get() == STOP && home.local.removeFirstOccurrence(entry)) {
                    home.localSize.decrementAndGet();
                    queueSlots.release();
                    throw new RejectedExecutionException("carrier pool is shut down");
                }
                if (home.parked) {
                    LockSupport.unpark(home.thread);
                } else {
                    // The home carrier is busy. Wake an idle carrier that will
                    // take the entry only if it is still queued after the grace.
                    wakeIdle(home);
                }
                return;
            }
            home.localSize.decrementAndGet();
        }
        Entry queued = new Entry(task, System.nanoTime(), submissions.incrementAndGet());
        shared.offer(queued);
        sharedPlacements.increment();
        if (runState.get() == STOP && shared.remove(queued)) {
            queueSlots.release();
            throw new RejectedExecutionException("carrier pool is shut down");
        }
        wakeIdle(null);
    }

    private void wakeIdle(Worker skip) {
        if (parkedCount.get() == 0) return;
        int n = workers.length;
        int start = Math.floorMod(wakeCursor.getAndIncrement(), n);
        for (int k = 0; k < n; k++) {
            Worker candidate = workers[(start + k) % n];
            if (candidate != skip && candidate.parked) {
                Thread thread = candidate.thread;
                if (thread != null) LockSupport.unpark(thread);
                return;
            }
        }
    }

    private void wakeAll(boolean interrupt) {
        for (Worker worker : workers) {
            Thread thread = worker.thread;
            if (thread == null) continue;
            if (interrupt) thread.interrupt();
            LockSupport.unpark(thread);
        }
    }

    @Override
    public void shutdown() {
        runState.compareAndSet(RUNNING, SHUTDOWN);
        closeStartWithoutCarriers();
        wakeAll(false);
    }

    @Override
    public List<Runnable> shutdownNow() {
        runState.set(STOP);
        closeStartWithoutCarriers();
        List<Runnable> drained = new ArrayList<>();
        for (Entry entry; (entry = shared.poll()) != null; ) {
            queueSlots.release();
            drained.add(entry.task);
        }
        for (Worker worker : workers) {
            for (Entry entry; (entry = worker.local.pollFirst()) != null; ) {
                worker.localSize.decrementAndGet();
                queueSlots.release();
                drained.add(entry.task);
            }
        }
        wakeAll(true);
        return drained;
    }

    private void closeStartWithoutCarriers() {
        synchronized (startLock) {
            if (startClosed) return;
            startClosed = true;
            for (int i = 0; i < workers.length; i++) terminated.countDown();
        }
    }

    @Override
    public boolean isShutdown() {
        return runState.get() != RUNNING;
    }

    @Override
    public boolean isTerminated() {
        return terminated.getCount() == 0;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return terminated.await(timeout, unit);
    }

    private static final class Entry {
        final Runnable task;
        final long enqueuedNanos;
        /** Global submission sequence: lower is older. */
        final long sequence;

        Entry(Runnable task, long enqueuedNanos, long sequence) {
            this.task = task;
            this.enqueuedNanos = enqueuedNanos;
            this.sequence = sequence;
        }
    }

    private final class Worker implements Runnable {
        final int slot;
        final ConcurrentLinkedDeque<Entry> local = new ConcurrentLinkedDeque<>();
        /** Reservations for {@link #local}; at least its size, never far above. */
        final AtomicInteger localSize = new AtomicInteger();
        volatile Thread thread;
        volatile boolean parked;
        volatile boolean exited;
        private int tick;

        Worker(int slot) {
            this.slot = slot;
        }

        LocalityCarrierPool pool() {
            return LocalityCarrierPool.this;
        }

        @Override
        public void run() {
            CURRENT.set(this);
            try {
                while (true) {
                    if (runState.get() == STOP) break;
                    Runnable task = next();
                    if (task == null) {
                        if (runState.get() != RUNNING && !hasAnyQueued()) break;
                        idle();
                        continue;
                    }
                    if (runState.get() != STOP) Thread.interrupted();
                    try {
                        task.run();
                    } catch (Throwable failure) {
                        report(failure);
                    }
                }
            } finally {
                exited = true;
                // A carrier that stops while others drain hands its queue on.
                for (Entry entry; (entry = local.pollFirst()) != null; ) {
                    localSize.decrementAndGet();
                    if (runState.get() == STOP) {
                        queueSlots.release();
                    } else {
                        shared.offer(entry); // keeps its submission order
                    }
                }
                CURRENT.remove();
                liveWorkers.decrementAndGet();
                terminated.countDown();
                wakeAll(false);
            }
        }

        private void report(Throwable failure) {
            Thread current = Thread.currentThread();
            Thread.UncaughtExceptionHandler handler = current.getUncaughtExceptionHandler();
            try {
                if (handler != null) handler.uncaughtException(current, failure);
            } catch (Throwable ignored) {
                // A broken handler must not take the carrier down.
            }
        }

        private Runnable next() {
            if (++tick == SHARED_CHECK_INTERVAL) {
                tick = 0;
                Runnable fromShared = pollShared();
                if (fromShared != null) return fromShared;
            }
            // Serve in submission order: locality decides WHICH carrier runs a
            // continuation, never that it overtakes older work. Without this, a
            // few tasks re-entering their local deques get about 61x the turns
            // of everything in the shared queue, and fresh CONTROL work (a new
            // root task, a Mailman dispatch) waits ~61 turns per older item.
            Entry head = local.peekFirst();
            Entry oldestShared = shared.peek();
            if (oldestShared != null && (head == null || oldestShared.sequence < head.sequence)) {
                Runnable fromShared = pollShared();
                if (fromShared != null) return fromShared;
            }
            Entry own = local.pollFirst();
            if (own != null) {
                localSize.decrementAndGet();
                queueSlots.release();
                localRuns.increment();
                return own.task;
            }
            Runnable fromShared = pollShared();
            if (fromShared != null) return fromShared;
            return steal();
        }

        private Runnable pollShared() {
            Entry entry = shared.poll();
            if (entry == null) return null;
            queueSlots.release();
            sharedRuns.increment();
            return entry.task;
        }

        private Runnable steal() {
            boolean ignoreGrace = runState.get() != RUNNING;
            long now = System.nanoTime();
            int n = workers.length;
            for (int k = 1; k < n; k++) {
                Worker victim = workers[(slot + k) % n];
                Entry head = victim.local.peekFirst();
                if (head == null) continue;
                if (!ignoreGrace && !victim.exited && now - head.enqueuedNanos < graceNanos) continue;
                if (victim.local.removeFirstOccurrence(head)) {
                    victim.localSize.decrementAndGet();
                    queueSlots.release();
                    steals.increment();
                    return head.task;
                }
            }
            return null;
        }

        /**
         * Nanos until the earliest young local entry elsewhere becomes
         * stealable; 0 when there is none; negative when work is ready now.
         */
        private long readyIn() {
            if (!local.isEmpty() || !shared.isEmpty()) return -1;
            long now = System.nanoTime();
            long soonest = 0;
            for (Worker other : workers) {
                if (other == this) continue;
                Entry head = other.local.peekFirst();
                if (head == null) continue;
                long remaining = graceNanos - (now - head.enqueuedNanos);
                if (remaining <= 0 || other.exited || runState.get() != RUNNING) return -1;
                if (soonest == 0 || remaining < soonest) soonest = remaining;
            }
            return soonest;
        }

        private boolean hasAnyQueued() {
            if (!shared.isEmpty()) return true;
            for (Worker worker : workers) if (!worker.local.isEmpty()) return true;
            return false;
        }

        private void idle() {
            parked = true;
            parkedCount.incrementAndGet();
            try {
                long wait = readyIn();
                if (wait < 0) return;
                if (runState.get() != RUNNING) return;
                // A pending interrupt makes park return at once; while the
                // pool runs, an interrupt left by a task must not cause a spin.
                Thread.interrupted();
                if (wait == 0) LockSupport.park(this);
                else LockSupport.parkNanos(this, wait);
            } finally {
                parked = false;
                parkedCount.decrementAndGet();
            }
        }
    }
}
