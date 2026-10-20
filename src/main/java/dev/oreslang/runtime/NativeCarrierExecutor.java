package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Bounded actor-carrier executor backed by native OS threads.
 *
 * <p>The OS carriers are created by liboresthread (pthread on Linux/macOS),
 * attached to the host VM exactly once with JNI AttachCurrentThreadAsDaemon,
 * and then repeatedly execute ordinary Runnable actor turns from this bounded
 * ready queue. Logical actors are therefore multiplexed over a much smaller
 * native carrier set; no actor owns a carrier and carrier identity is never
 * actor identity.</p>
 *
 * <p>The throughput profile allocates native pool metadata at construction but
 * creates no pthread until the first runnable turn (or an explicit prestart).
 * Once started, all bounded compensation slots for this executor are created
 * together so watchdog compensation never allocates another OS thread while
 * the runtime is already under pressure. Only {@code corePoolSize} carrier
 * slots are enabled at a time; extras remain parked in native code.</p>
 */
public final class NativeCarrierExecutor extends AbstractExecutorService implements AutoCloseable {
    private static final String LIBRARY = "oresthread";
    private static final ThreadLocal<NativeCarrierExecutor> CURRENT_EXECUTOR = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CURRENT_SLOT = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_NATIVE_THREAD_ID = new ThreadLocal<>();

    private static final Object NATIVE_LIBRARY_LOCK = new Object();
    private static volatile boolean nativeLibraryLoaded;

    private final ArrayBlockingQueue<Runnable> queue;
    // Enqueue and shutdown-drain share one short admission fence. A separate
    // tryLock path keeps reactor submissions from waiting behind shutdown or
    // an ordinary admission. Never perform nativeStart while holding this.
    private final ReentrantLock queueAdmission = new ReentrantLock();
    private final int maximumPoolSize;
    private final long nativeStackBytes;
    private final AtomicInteger corePoolSize;
    private final AtomicInteger largestPoolSize;
    private final AtomicInteger activeCount = new AtomicInteger();
    private final AtomicLong completedTaskCount = new AtomicLong();
    private final AtomicReferenceArray<Thread> carrierThreads;
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private volatile boolean nativeStarted;
    // Includes callers waiting to enter nativeLifecycleLock. Shutdown may
    // publish its flag while pthread attach/publication is still in flight;
    // termination observers must not mistake temporarily empty Java carrier
    // slots for a completed native startup/shutdown barrier.
    private final AtomicInteger bootstrapAdmissions = new AtomicInteger();
    /** Serializes lazy native bootstrap and Java->native pool calls against retirement. */
    private final Object nativeLifecycleLock = new Object();
    private final long nativeHandle;

    public NativeCarrierExecutor(
            int corePoolSize,
            int maximumPoolSize,
            int queueCapacity,
            String threadPrefix) {
        if (corePoolSize <= 0) throw new IllegalArgumentException("corePoolSize must be > 0");
        if (maximumPoolSize < corePoolSize) {
            throw new IllegalArgumentException("maximumPoolSize must be >= corePoolSize");
        }
        if (queueCapacity <= 0) throw new IllegalArgumentException("queueCapacity must be > 0");
        Objects.requireNonNull(threadPrefix, "threadPrefix");

        ensureNativeLibraryLoaded();

        this.queue = new ArrayBlockingQueue<>(queueCapacity, true);
        this.maximumPoolSize = maximumPoolSize;
        this.nativeStackBytes = configuredCarrierStackBytes();
        this.carrierThreads = new AtomicReferenceArray<>(maximumPoolSize);
        this.corePoolSize = new AtomicInteger(corePoolSize);
        this.largestPoolSize = new AtomicInteger(corePoolSize);

        long handle = nativeCreate(
                this, maximumPoolSize, corePoolSize, threadPrefix, nativeStackBytes);
        if (handle == 0L) throw new IllegalStateException("native carrier pool returned a null handle");
        this.nativeHandle = handle;

        // Throughput cold start deliberately stops here: nativeCreate owns only
        // bounded pool metadata. First runnable admission calls nativeStart().
    }

    private boolean ensureNativeStarted() {
        bootstrapAdmissions.incrementAndGet();
        try {
            synchronized (nativeLifecycleLock) {
                if (shutdown.get()) {
                    throw new RejectedExecutionException("native carrier executor is shut down");
                }
                if (nativeStarted) return false;
                try {
                    nativeStart(nativeHandle);
                    nativeStarted = true;
                    return true;
                } catch (RuntimeException | Error failure) {
                    // nativeStart may have created a partial/all carrier set
                    // before an attach failure. Fence all further admission
                    // and hand reclamation to native shutdown exactly once.
                    shutdown.set(true);
                    nativeShutdown(nativeHandle);
                    throw failure;
                }
            }
        } finally {
            bootstrapAdmissions.decrementAndGet();
        }
    }

    static void requireAllowedCpu(int cpu) {
        ensureNativeLibraryLoaded();
        int error = nativeCpuAffinity(cpu, false);
        if (error != 0) throw new IllegalArgumentException(
                "hard CPU affinity unavailable for CPU " + cpu + " (OS error " + error + ")");
    }

    static void bindCurrentThreadToCpu(int cpu) {
        int error = nativeCpuAffinity(cpu, true);
        if (error != 0) throw new IllegalStateException(
                "hard CPU affinity failed for CPU " + cpu + " (OS error " + error + ")");
    }

    static int currentCpu() {
        ensureNativeLibraryLoaded();
        return nativeCurrentCpu();
    }

    private static native int nativeCurrentCpu();
    private static native int nativeCpuAffinity(int cpu, boolean bind);

    static void ensureNativeLibraryLoaded() {
        if (nativeLibraryLoaded) return;
        synchronized (NATIVE_LIBRARY_LOCK) {
            if (nativeLibraryLoaded) return;
            loadNativeLibrary();
            nativeLibraryLoaded = true;
        }
    }

    private static long configuredCarrierStackBytes() {
        long configured = Long.getLong("ores.actor.carrier-stack-bytes", 2L * 1024 * 1024);
        if (configured < 256L * 1024 || configured > 64L * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "ores.actor.carrier-stack-bytes must be between 262144 and 67108864");
        }
        return configured;
    }

    private static void loadNativeLibrary() {
        String explicit = System.getProperty("ores.thread.native.path");
        if (explicit != null && !explicit.isBlank()) {
            System.load(Path.of(explicit).toAbsolutePath().normalize().toString());
            return;
        }

        Path local = Path.of("target", "native", System.mapLibraryName(LIBRARY))
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(local)) {
            System.load(local.toString());
            return;
        }

        System.loadLibrary(LIBRARY);
    }

    /**
     * Native pthread entry callback. One invocation lives for one physical
     * carrier's lifetime; many unrelated actor/root Runnables pass through it.
     */
    @SuppressWarnings("unused") // invoked from JNI
    private void nativeCarrierLoop(int slot) {
        CURRENT_EXECUTOR.set(this);
        CURRENT_SLOT.set(slot);
        CURRENT_NATIVE_THREAD_ID.set(nativeCurrentThreadId());
        carrierThreads.set(slot, Thread.currentThread());
        try {
            // nativeStart() is the publication barrier for first runnable
            // admission. Signal only after this Java Thread identity is
            // visible so execute()/diagnostics/shutdown cannot observe an
            // already-created pthread as "not started".
            nativeCarrierReady(nativeHandle, slot);
            while (!shutdown.get()) {
                nativeAwaitEnabled(nativeHandle, slot);
                if (shutdown.get()) break;

                Runnable task;
                try {
                    // A finite wait lets a carrier observe a requested shrink
                    // after it becomes idle without an extra JVM helper thread.
                    task = queue.poll(50L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    // Watchdog interruption belongs to the actor/root turn that
                    // was active on this carrier. Never let the interrupt bit
                    // poison the next unrelated actor turn.
                    Thread.interrupted();
                    if (shutdown.get()) break;
                    continue;
                }
                if (task == null) continue;

                activeCount.incrementAndGet();
                try {
                    task.run();
                } catch (Throwable failure) {
                    // ActorRuntime catches ordinary turn failures itself. This
                    // is the executor's final containment boundary, equivalent
                    // to a ThreadPoolExecutor worker's uncaught-exception path.
                    dispatchUncaught(failure);
                } finally {
                    activeCount.decrementAndGet();
                    completedTaskCount.incrementAndGet();
                    Thread.interrupted();
                }
            }
        } finally {
            carrierThreads.set(slot, null);
            CURRENT_NATIVE_THREAD_ID.remove();
            CURRENT_SLOT.remove();
            CURRENT_EXECUTOR.remove();
        }
    }

    private static void dispatchUncaught(Throwable failure) {
        Thread current = Thread.currentThread();
        Thread.UncaughtExceptionHandler handler = current.getUncaughtExceptionHandler();
        if (handler == null) handler = Thread.getDefaultUncaughtExceptionHandler();
        if (handler != null) {
            try {
                handler.uncaughtException(current, failure);
            } catch (Throwable ignored) {
                // Keep the native carrier alive; ActorRuntime's own watchdog
                // and fail-stop state are the authoritative control plane.
            }
        }
    }

    /**
     * Volatile publication fence, not a census of Java Thread identities.
     *
     * A pthread may have attached and published carrierThreads[slot] while
     * nativeStart() is still waiting for the remaining pthreads under
     * nativeLifecycleLock. Such a partially started pool is NOT eligible
     * for reactor-originated host notification admission.
     */
    boolean readyForNoBootstrapAdmission() {
        return nativeStarted && !shutdown.get();
    }

    /**
     * Bounded host-only enqueue without JNI bootstrap or the native lifecycle
     * monitor. The reactor caller must handle rejection by retaining its
     * completion reservation and retrying after a carrier-capacity signal.
     *
     * The queue offer still uses ArrayBlockingQueue's short internal lock. It
     * must NEVER call ensureNativeStarted(), nativeStart(), or start threads.
     * This is not a wait-free queue and not a real-time hot-path guarantee.
     */
    void executeWithoutBootstrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (!readyForNoBootstrapAdmission())
            throw new RejectedExecutionException("native carrier has not finished bootstrap");
        enqueueIfOpen(task, true);
    }

    /** Enqueue and shutdown drain are linearized by queueAdmission. This
     * avoids removing the wrong runnable during rollback when different
     * submitted tasks implement value-based equals(). An admission that wins
     * before shutdown is accepted and will either run or be drained by
     * shutdownNow(); no task can sneak in after its final drain.
     */
    private void enqueueIfOpen(Runnable task, boolean noParking) {
        if (noParking) {
            if (!queueAdmission.tryLock())
                throw new RejectedExecutionException("native carrier admission is busy");
        } else {
            queueAdmission.lock();
        }
        try {
            if (shutdown.get())
                throw new RejectedExecutionException("native carrier executor is shut down");
            if (!queue.offer(task))
                throw new RejectedExecutionException("native carrier ready queue is full");
        } finally {
            queueAdmission.unlock();
        }
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (shutdown.get())
            throw new RejectedExecutionException("native carrier executor is shut down");
        ensureNativeStarted();
        enqueueIfOpen(task, false);
    }

    public boolean remove(Runnable task) {
        return queue.remove(task);
    }

    @Override
    public List<Runnable> shutdownNow() {
        if (isCurrentCarrierThread()) {
            throw new IllegalStateException(
                    "native carrier executor cannot synchronously interrupt itself; use requestShutdownFromCarrier()");
        }
        return beginShutdown(true);
    }

    /**
     * Cooperative one-way shutdown for code currently executing on one of this
     * executor's own native carriers. No Java interrupt is injected into the
     * active turn; once that turn unwinds, the carrier observes the shutdown
     * flag and exits through the native reaper path.
     */
    void requestShutdownFromCarrier() {
        if (!isCurrentCarrierThread()) {
            throw new IllegalStateException(
                    "requestShutdownFromCarrier must run on this executor's native carrier");
        }
        beginShutdown(false);
    }

    boolean isCurrentCarrierThread() {
        return CURRENT_EXECUTOR.get() == this;
    }

    private List<Runnable> beginShutdown(boolean interruptActiveCarriers) {
        if (!shutdown.compareAndSet(false, true)) return List.of();
        ArrayList<Runnable> abandoned = new ArrayList<>();
        queueAdmission.lock();
        try {
            queue.drainTo(abandoned);
        } finally {
            queueAdmission.unlock();
        }

        if (interruptActiveCarriers) {
            // Match ThreadPoolExecutor.shutdownNow(): signal any active carrier
            // before native retirement. This is cooperative Java interruption,
            // never unsafe pthread_cancel().
            for (int slot = 0; slot < carrierThreads.length(); slot++) {
                Thread carrier = carrierThreads.get(slot);
                if (carrier != null) carrier.interrupt();
            }
        }

        synchronized (nativeLifecycleLock) {
            // Native shutdown is non-blocking: it marks the pool closed and
            // hands joins/reclamation to a native reaper so an uncooperative
            // guest stack can never hold this caller hostage.
            nativeShutdown(nativeHandle);
        }
        return List.copyOf(abandoned);
    }

    /**
     * Wait until every attached pthread carrier has exited its Java loop.
     * Native shutdown itself is deliberately non-blocking; callers that own a
     * scheduler can use this bounded observation without joining arbitrary
     * guest stacks in native code.
     */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;
        for (;;) {
            // Neither "no attached carriers" nor a finished Java loop alone
            // suffices while nativeStart() is still inside its publication
            // barrier. Await termination only after shutdown and the end of
            // every potentially native-starting admission.
            if (isTerminated()) return true;
            if (nanos == 0L || System.nanoTime() >= deadline) return false;
            long remaining = deadline - System.nanoTime();
            long millis = Math.max(1L, Math.min(10L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            Thread.sleep(millis);
        }
    }

    public boolean isQueueEmpty() { return queue.isEmpty(); }

    @Override
    public void close() {
        shutdownNow();
    }

    public int getActiveCount() { return activeCount.get(); }
    public int getQueueSize() { return queue.size(); }
    public long getCompletedTaskCount() { return completedTaskCount.get(); }
    public int getLargestPoolSize() { return largestPoolSize.get(); }
    /** Attached carrier count for diagnostics/tests; cold throughput startup is zero. */
    public int getStartedCarrierCount() {
        int count = 0;
        for (int slot = 0; slot < carrierThreads.length(); slot++) {
            if (carrierThreads.get(slot) != null) count++;
        }
        return count;
    }
    public int getMaximumPoolSize() { return maximumPoolSize; }
    public long getNativeStackBytes() { return nativeStackBytes; }
    public int getCorePoolSize() { return corePoolSize.get(); }
    @Override
    public void shutdown() {
        shutdownNow();
    }

    @Override
    public boolean isShutdown() { return shutdown.get(); }

    @Override
    public boolean isTerminated() {
        if (!shutdown.get() || bootstrapAdmissions.get() != 0) return false;
        for (int slot = 0; slot < carrierThreads.length(); slot++) {
            if (carrierThreads.get(slot) != null) return false;
        }
        return true;
    }

    public void setCorePoolSize(int value) {
        if (value <= 0 || value > maximumPoolSize) {
            throw new IllegalArgumentException(
                    "corePoolSize must be in [1," + maximumPoolSize + "]");
        }
        if (shutdown.get()) throw new RejectedExecutionException("native carrier executor is shut down");
        synchronized (nativeLifecycleLock) {
            if (shutdown.get()) {
                throw new RejectedExecutionException("native carrier executor is shut down");
            }
            corePoolSize.set(value);
            largestPoolSize.accumulateAndGet(value, Math::max);
            nativeSetDesired(nativeHandle, value);
        }
    }

    /**
     * Explicit warm/RT preparation hook. Throughput mode normally leaves the
     * pool cold until execute(); this starts the bounded native carrier set now.
     */
    public boolean prestartCoreThread() {
        return ensureNativeStarted();
    }

    public static boolean isNativeCarrierThread() {
        return CURRENT_EXECUTOR.get() != null;
    }

    /** Diagnostic-only native pthread identity; never an actor identity. */
    public static long currentNativeThreadId() {
        Long id = CURRENT_NATIVE_THREAD_ID.get();
        return id == null ? 0L : id;
    }

    /** Diagnostic-only carrier slot within its pool. */
    public static int currentCarrierSlot() {
        Integer slot = CURRENT_SLOT.get();
        return slot == null ? -1 : slot;
    }

    /** CPU consumed by the current native carrier, excluding time descheduled. */
    public static long currentCarrierCpuTimeNanos() {
        return isNativeCarrierThread() ? nativeCurrentThreadCpuNanos() : 0L;
    }

    /** CPU consumed by a specific carrier slot in this pool, for watchdog accounting. */
    public long carrierCpuTimeNanos(int slot) {
        if (slot < 0 || slot >= maximumPoolSize || shutdown.get() || !nativeStarted) return 0L;
        synchronized (nativeLifecycleLock) {
            if (shutdown.get()) return 0L;
            return nativeCarrierCpuTimeNanos(nativeHandle, slot);
        }
    }

    private static native long nativeCreate(
            NativeCarrierExecutor executor,
            int maxThreads,
            int desiredThreads,
            String threadPrefix,
            long stackBytes);
    private static native void nativeStart(long handle);
    private static native void nativeSetDesired(long handle, int desiredThreads);
    private static native void nativeAwaitEnabled(long handle, int slot);
    private static native void nativeShutdown(long handle);
    private static native long nativeCurrentThreadId();
    private static native void nativeCarrierReady(long handle, int slot);
    private static native long nativeCurrentThreadCpuNanos();
    private static native long nativeCarrierCpuTimeNanos(long handle, int slot);
}
