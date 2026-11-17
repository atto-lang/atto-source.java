package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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
 * <p>Construction loads liboresthread but creates no pthread. The native pool
 * is created on {@link #ensureStarted()}, which actor admission calls before
 * an actor is published, or on the first {@link #execute(Runnable)} as a
 * backstop. A runtime that never spawns an actor therefore never pays for
 * actor carriers.</p>
 *
 * <p>Once started, all max carriers are created together so watchdog
 * compensation never has to allocate an OS thread while the runtime is
 * already under pressure. Only {@code corePoolSize} carrier slots are enabled
 * at a time. Extra carriers are parked in native code and are enabled/disabled
 * by the bounded compensation policy in {@link ActorRuntime}.</p>
 */
public final class NativeCarrierExecutor extends AbstractExecutorService implements AutoCloseable {
    private static final String LIBRARY = "oresthread";
    private static final ThreadLocal<NativeCarrierExecutor> CURRENT_EXECUTOR = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CURRENT_SLOT = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_NATIVE_THREAD_ID = new ThreadLocal<>();

    private static final Object NATIVE_LIBRARY_LOCK = new Object();
    private static volatile boolean nativeLibraryLoaded;

    private final ArrayBlockingQueue<Runnable> queue;
    private final int maximumPoolSize;
    private final long nativeStackBytes;
    private final AtomicInteger corePoolSize;
    private final AtomicInteger largestPoolSize;
    private final AtomicInteger activeCount = new AtomicInteger();
    private final AtomicLong completedTaskCount = new AtomicLong();
    private final AtomicReferenceArray<Thread> carrierThreads;
    /** A JNI attach is not yet a Java carrier-loop entry. */
    private final CountDownLatch carrierLoopsEntered;
    /** Signaled by the JNI reaper only after all associated pthread_join calls. */
    private final CountDownLatch physicalRetirement = new CountDownLatch(1);
    private final AtomicBoolean shutdown = new AtomicBoolean();
    /** Serializes Java->native pool calls against asynchronous native retirement. */
    private final Object nativeLifecycleLock = new Object();
    private final String threadPrefix;
    /** 0 until the native pool is created; published before JNI workers enter. */
    private volatile long nativeHandle;
    /** Published only after every JNI carrier attachment has completed successfully. */
    private volatile boolean startedSuccessfully;
    /** Stops concurrent termination observation from preceding first JNI entry. */
    private volatile boolean startupInProgress;
    /** Failed JNI attachment/start cannot certify complete carrier retirement. */
    private volatile boolean failedNativeStartup;

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
        this.carrierLoopsEntered = new CountDownLatch(maximumPoolSize);
        this.corePoolSize = new AtomicInteger(corePoolSize);
        this.largestPoolSize = new AtomicInteger(0);
        this.threadPrefix = threadPrefix;
    }

    /** True only once native startup/attachment has completed successfully. */
    public boolean isStarted() {
        return startedSuccessfully;
    }

    /**
     * Create and start this pool's pthreads if that has not happened yet.
     * Idempotent and safe under concurrent callers: exactly one successful
     * native pool may be created. A fully rolled-back pthread_create failure
     * may be retried; a partial JNI attach/start failure poisons the executor
     * and must never leave zombie JVM-attached carriers eligible for reuse.
     *
     * @return true if this call created the pool, false if it already existed
     * @throws RejectedExecutionException if the executor is shut down
     * @throws IllegalStateException if the native pool could not be created
     */
    public boolean ensureStarted() {
        // Shutdown must win over an already-published native handle. Serialize
        // the check with both first creation and native retirement.
        synchronized (nativeLifecycleLock) {
            if (shutdown.get()) {
                throw new RejectedExecutionException("native carrier executor is shut down");
            }
            if (nativeHandle != 0L) return false;
            startupInProgress = true;
            try {
                int desired = corePoolSize.get();
                long handle = nativeCreate(
                        this, maximumPoolSize, desired, threadPrefix, nativeStackBytes);
                if (handle == 0L) {
                    throw new IllegalStateException("native carrier pool returned a null handle");
                }
                // Publish before nativeStart: carriers read nativeHandle in their loop.
                nativeHandle = handle;
                boolean started = false;
                try {
                    nativeStart(handle);
                    // C reports successful JNI attachments before invoking Java.
                    // A close/awaitTermination between those two operations
                    // must not mistake all-null carrierThreads for retirement.
                    try {
                        if (!carrierLoopsEntered.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "native carrier JNI loops did not all enter after attachment");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "native carrier JNI startup interrupted", interrupted);
                    }
                    if (shutdown.get()) {
                        throw new RejectedExecutionException(
                                "native carrier executor closed during startup");
                    }
                    started = true;
                } finally {
                    if (!started) {
                        // A subset of carriers may already have attached.
                        // Reuse of this executor could overlap retired handles.
                        shutdown.set(true);
                        failedNativeStartup = true;
                        nativeShutdown(handle);
                        nativeHandle = 0L;
                    }
                }
                largestPoolSize.accumulateAndGet(desired, Math::max);
                startedSuccessfully = true;
                return true;
            } finally {
                startupInProgress = false;
            }
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
        // Publish the Java callback registration before bootstrap can return.
        carrierLoopsEntered.countDown();
        try {
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

    /** Invoked by liboresthread's reaper after joining all native carriers. */
    @SuppressWarnings("unused") // invoked through registered JNI reachability metadata
    private void nativeCarriersJoined() {
        physicalRetirement.countDown();
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

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        // A published nativeHandle is not proof that JNI attachment finished.
        // Hold the lifecycle lock across first start, admission and shutdown's
        // drain so a partly started pool can never accept work.
        synchronized (nativeLifecycleLock) {
            if (shutdown.get()) throw new RejectedExecutionException(
                    "native carrier executor is shut down");
            if (!startedSuccessfully) {
                try {
                    ensureStarted();
                } catch (IllegalStateException startFailure) {
                    RejectedExecutionException rejected = new RejectedExecutionException(
                            "native carrier executor could not start its carriers");
                    rejected.initCause(startFailure);
                    throw rejected;
                }
            }
            if (shutdown.get() || !queue.offer(task)) {
                throw new RejectedExecutionException(
                        shutdown.get() ? "native carrier executor is shut down"
                                : "native carrier ready queue is full");
            }
        }
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
    List<Runnable> requestShutdownFromCarrier() {
        if (!isCurrentCarrierThread()) {
            throw new IllegalStateException(
                    "requestShutdownFromCarrier must run on this executor's native carrier");
        }
        // Return never-started turns so a parent lane dispatcher can release
        // their admission permits and account for every abandoned command.
        return beginShutdown(false);
    }

    boolean isCurrentCarrierThread() {
        return CURRENT_EXECUTOR.get() == this;
    }

    private List<Runnable> beginShutdown(boolean interruptActiveCarriers) {
        if (!shutdown.compareAndSet(false, true)) return List.of();
        ArrayList<Runnable> abandoned = new ArrayList<>();

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
            // Take the same lock as execute(): every submitted task is either
            // already claimed by a worker or returned in this exact drain.
            queue.drainTo(abandoned);
            // Native shutdown is non-blocking: it marks the pool closed and
            // hands joins/reclamation to a native reaper so an uncooperative
            // guest stack can never hold this caller hostage. A pool that
            // never started has no native state to release.
            long handle = nativeHandle;
            if (handle != 0L) nativeShutdown(handle);
        }
        return List.copyOf(abandoned);
    }

    /**
     * Await physical pthread joins, not merely the end of Java carrier loops.
     * The native reaper signals physicalRetirement only after all pthread_join
     * calls return. A failed reaper startup/VM reattachment must time out
     * conservatively rather than falsely certify physical retirement.
     */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
        if (!shutdown.get()) return false;
        // Pool construction and a nativeCreate() failure allocate no live JNI
        // pool: no reaper will ever be required in these two cases.
        if (!startupInProgress && nativeHandle == 0L && !failedNativeStartup) return true;
        return physicalRetirement.await(timeout, unit);
    }

    public boolean isQueueEmpty() { return queue.isEmpty(); }

    @Override
    public void close() {
        shutdownNow();
    }

    /** Host-only diagnostic: native Java-loop registrations, not pthread joins. */
    int registeredCarrierCount() {
        int count = 0;
        for (int slot = 0; slot < carrierThreads.length(); slot++) {
            if (carrierThreads.get(slot) != null) count++;
        }
        return count;
    }

    public int getActiveCount() { return activeCount.get(); }
    public int getQueueSize() { return queue.size(); }
    public long getCompletedTaskCount() { return completedTaskCount.get(); }
    public int getLargestPoolSize() { return largestPoolSize.get(); }
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
        if (!shutdown.get() || startupInProgress) return false;
        return (nativeHandle == 0L && !failedNativeStartup)
                || physicalRetirement.getCount() == 0L;
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
            long handle = nativeHandle;
            // Before start, the new size is simply used when the pool is created.
            if (handle != 0L) {
                largestPoolSize.accumulateAndGet(value, Math::max);
                nativeSetDesired(handle, value);
            }
        }
    }

    /**
     * Starts the native pool if it has not started yet. All bounded
     * compensation carriers are created together at that point, so once the
     * pool exists there is nothing more to allocate here.
     */
    public boolean prestartCoreThread() {
        return ensureStarted();
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
        if (slot < 0 || slot >= maximumPoolSize || shutdown.get() || nativeHandle == 0L) return 0L;
        synchronized (nativeLifecycleLock) {
            long handle = nativeHandle;
            if (shutdown.get() || handle == 0L) return 0L;
            return nativeCarrierCpuTimeNanos(handle, slot);
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
    private static native long nativeCurrentThreadCpuNanos();
    private static native long nativeCarrierCpuTimeNanos(long handle, int slot);
}
