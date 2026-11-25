package dev.oreslang.runtime;

import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VM-owned asynchronous root runtime.
 *
 * <p>The CONTROL pool is deliberately prestarted during VM construction. Guest
 * execution is not admitted until this barrier is complete, so the first
 * {@code main} turn can safely use Futures, timers, channel/select wakeups and
 * root continuations without lazily creating runtime infrastructure.</p>
 */
public final class OresVM implements AutoCloseable {
    // Two VM-owned CONTROL workers are enough for the cold startup barrier,
    // independent of the number of host CPUs. Extra idle CONTROL workers do
    // not improve actor carrier availability, which is managed separately.
    // Explicit -Dores.runtime.control.parallelism overrides remain supported.
    // This counts Oreslang CONTROL workers, not all JVM or OS threads.
    private static final int DEFAULT_CONTROL_PARALLELISM = 2;
    // Do not allow a malformed/hostile runtime property to request an
    // unbounded number of eagerly created platform threads.
    private static final int MAX_CONTROL_PARALLELISM = 128;
    private static final int DEFAULT_QUEUE_CAPACITY = 65_536;
    // Resource revokers may block. They must never occupy the CONTROL pool.
    // One bounded, VM-owned executor serves all actors and supervisors.
    private static final int DEFAULT_PHYSICAL_STOP_PARALLELISM =
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final int DEFAULT_PHYSICAL_STOP_QUEUE_CAPACITY = 16_384;

    /** NIO selector/completion plumbing is host runtime code, never guest execution. */
    private static final ThreadLocal<Boolean> ON_NIO_WORKER = new ThreadLocal<>();

    static boolean onNioWorker() {
        return Boolean.TRUE.equals(ON_NIO_WORKER.get());
    }

    static void runAsNioWorker(Runnable work) {
        Objects.requireNonNull(work, "work");
        Boolean previous = ON_NIO_WORKER.get();
        ON_NIO_WORKER.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            if (previous == null) ON_NIO_WORKER.remove();
            else ON_NIO_WORKER.set(previous);
        }
    }

    private enum StartupState { NEW, STARTING, STARTED, FAILED, CLOSED }

    /**
     * The queue-owned lifecycle has one atomic physical-start decision.
     * shutdownNow may discard ADMITTED work; it may not claim a RUNNING
     * revoker has retired merely because an interrupt was requested.
     */
    private enum PhysicalStopState {
        ADMITTED, RUNNING, RETIRED, CANCELLED_BEFORE_START
    }

    private static final class PhysicalStopJob implements Runnable {
        private final Runnable body;
        private final Consumer<Throwable> discarded;
        private final AtomicReference<PhysicalStopState> state =
                new AtomicReference<>(PhysicalStopState.ADMITTED);

        private PhysicalStopJob(Runnable body, Consumer<Throwable> discarded) {
            this.body = Objects.requireNonNull(body, "body");
            this.discarded = Objects.requireNonNull(discarded, "discarded");
        }

        @Override public void run() {
            if (!state.compareAndSet(
                    PhysicalStopState.ADMITTED, PhysicalStopState.RUNNING)) return;
            try {
                body.run();
            } finally {
                // ActorRuntime's own body finally reports the physical
                // dispatch receipt only after child.cancel() has returned.
                state.set(PhysicalStopState.RETIRED);
            }
        }

        private void cancelBeforeStart(Throwable cause) {
            if (state.compareAndSet(PhysicalStopState.ADMITTED,
                    PhysicalStopState.CANCELLED_BEFORE_START)) {
                discarded.accept(cause);
            }
        }
    }

    /** Root and CONTROL carriers; root-task continuations prefer their last carrier. */
    private final LocalityCarrierPool controlExecutor;
    private final ThreadPoolExecutor physicalStopExecutor;
    private final OresScheduler rootScheduler;
    private final NioReadinessGroup nioReadiness;
    /**
     * VM-owned NIO2 completion carriers for AsyncNioTcp. Created on first use,
     * bounded, named and marked as NIO workers: completion handlers only
     * settle OresFutures, and the continuation resumes through its scheduler.
     * Without this, NIO2 used the JVM-wide default group, an unbounded pool the
     * VM neither owned nor shut down.
     */
    private final Object nioCompletionLock = new Object();
    private java.nio.channels.AsynchronousChannelGroup nioCompletionGroup;
    private static final int DEFAULT_NIO_COMPLETION_THREADS = 2;
    private static final int MAX_NIO_COMPLETION_THREADS = 64;
    private final Set<VmNioTcpListener> nioTcpListeners =
            ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile StartupState startupState = StartupState.NEW;

    private OresVM(int controlParallelism, OresScheduler.TurnExecutor turnExecutor,
            Executor rootAdmission) {
        this(controlParallelism, turnExecutor, rootAdmission,
                Integer.getInteger("ores.runtime.physical-stop.parallelism",
                        DEFAULT_PHYSICAL_STOP_PARALLELISM),
                Integer.getInteger("ores.runtime.physical-stop.queue-capacity",
                        DEFAULT_PHYSICAL_STOP_QUEUE_CAPACITY));
    }

    private OresVM(int controlParallelism, OresScheduler.TurnExecutor turnExecutor,
            Executor rootAdmission, int physicalParallelism, int physicalQueue) {
        ActorHeapAdmission.requireSupportedJvmMode(
                System.getProperty("ores.runtime.gc.mode", "hybrid"));
        ActorHeapAdmission.requireSupportedTenantIsolationMode(
                System.getProperty("ores.runtime.tenant.isolation", "trusted-inprocess"));
        if (controlParallelism < 1 || controlParallelism > MAX_CONTROL_PARALLELISM) {
            throw new IllegalArgumentException(
                    "control parallelism must be in [1," + MAX_CONTROL_PARALLELISM + "]");
        }

        AtomicInteger carrierId = new AtomicInteger();
        ThreadFactory factory = runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("ores-control-plane-" + carrierId.getAndIncrement())
                .stackSize(LogicalStackQuota.CARRIER_STACK_BYTES)
                .unstarted(LogicalStackQuota.carrierBody(
                        LogicalStackQuota.CARRIER_STACK_BYTES, runnable));

        this.controlExecutor = new LocalityCarrierPool(
                controlParallelism, DEFAULT_QUEUE_CAPACITY, factory);

        // Limits are fixed at VM startup; no mutable global/test properties
        // may affect physical STOP capacity after admission.
        if (physicalParallelism <= 0 || physicalParallelism > 128
                || physicalQueue <= 0 || physicalQueue > 65_536) {
            controlExecutor.shutdownNow();
            throw new IllegalArgumentException(
                    "invalid bounded OresVM physical STOP executor configuration");
        }
        ThreadFactory physicalFactory = Thread.ofVirtual()
                .name("ores-physical-stop-", 0)
                .factory();
        this.physicalStopExecutor = new ThreadPoolExecutor(
                physicalParallelism, physicalParallelism, 0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(physicalQueue),
                physicalFactory,
                new ThreadPoolExecutor.AbortPolicy());

        // Scheduler creation itself can fail before startup(). Ensure the
        // newly allocated physical and CONTROL workers are not leaked.
        final OresScheduler initializedRoot;
        try {
            initializedRoot = OresScheduler.runtimeOwned(
                    "ores-root-scheduler",
                    rootAdmission == null ? controlParallelism : 1,
                    rootAdmission == null ? controlExecutor : rootAdmission,
                    turnExecutor);
        } catch (RuntimeException | Error failure) {
            shutdownPhysicalStops();
            controlExecutor.shutdownNow();
            throw failure;
        }
        this.rootScheduler = initializedRoot;
        // Construction owns no selector or reactor thread. The first accepted
        // readiness watch starts the VM-owned reactor lazily.
        this.nioReadiness = new NioReadinessGroup();
        try {
            startup();
        } catch (RuntimeException | Error failure) {
            rootScheduler.close();
            controlExecutor.shutdownNow();
            shutdownPhysicalStops();
            throw failure;
        }
    }

    /**
     * Resolve the process-level override once at VM creation, using a bounded
     * decimal integer. Unlike Integer.getInteger, malformed overrides must
     * fail closed rather than silently fall back to the default.
     */
    static int configuredControlParallelism(String configured) {
        if (configured == null) return DEFAULT_CONTROL_PARALLELISM;
        final int requested;
        try {
            requested = Integer.parseInt(configured);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    "ores.runtime.control.parallelism must be a decimal integer in [1,"
                            + MAX_CONTROL_PARALLELISM + "]", malformed);
        }
        if (requested < 1 || requested > MAX_CONTROL_PARALLELISM) {
            throw new IllegalArgumentException(
                    "ores.runtime.control.parallelism must be in [1,"
                            + MAX_CONTROL_PARALLELISM + "]");
        }
        return requested;
    }

    static OresVM create(OresScheduler.TurnExecutor turnExecutor) {
        return create(turnExecutor, null);
    }

    static OresVM create(OresScheduler.TurnExecutor turnExecutor, Executor rootAdmission) {
        return createConfigured(turnExecutor, rootAdmission,
                System.getProperty("ores.runtime.control.parallelism"));
    }

    /**
     * Shared production factory path, with explicit configuration for
     * deterministic tests; no test needs to change process-wide properties.
     */
    static OresVM createConfigured(OresScheduler.TurnExecutor turnExecutor,
            Executor rootAdmission, String configuredControl) {
        return new OresVM(
                configuredControlParallelism(configuredControl),
                turnExecutor, rootAdmission);
    }

    /** Test-only deterministic executor limits; no global property mutation. */
    static OresVM createWithPhysicalStopLimits(int parallelism, int queueCapacity) {
        return new OresVM(
                configuredControlParallelism(
                        System.getProperty("ores.runtime.control.parallelism")),
                Runnable::run,
                null, parallelism, queueCapacity);
    }

    /**
     * VM bootstrap barrier. The STARTED state is visible only after every
     * configured CONTROL core carrier has been successfully prestarted.
     */
    synchronized void startup() {
        if (startupState == StartupState.CLOSED) {
            throw new IllegalStateException("Oreslang VM is closed");
        }
        switch (startupState) {
            case STARTED -> { return; }
            case STARTING -> throw new IllegalStateException(
                    "recursive Oreslang VM scheduler bootstrap");
            case FAILED -> throw new IllegalStateException(
                    "Oreslang VM scheduler bootstrap previously failed");
            case NEW -> startupState = StartupState.STARTING;
            default -> throw new AssertionError(startupState);
        }

        try {
            int required = controlExecutor.carrierCount();
            int newlyStarted = controlExecutor.prestartAll();
            int live = controlExecutor.liveCarriers();
            if (live != required) {
                throw new IllegalStateException(
                        "CONTROL dispatcher bootstrap incomplete: required="
                                + required + ", live=" + live
                                + ", newlyStarted=" + newlyStarted);
            }
            startupState = StartupState.STARTED;
        } catch (RuntimeException | Error failure) {
            startupState = StartupState.FAILED;
            throw failure;
        }
    }

    boolean started() {
        return startupState == StartupState.STARTED;
    }

    int controlCarrierCount() {
        return controlExecutor.liveCarriers();
    }

    /** Trusted runtime-only shared readiness domain; guest access is permission-gated above it. */
    NioReadinessGroup nioReadiness() {
        if (!started()) throw new IllegalStateException("Oreslang VM is not running");
        return nioReadiness;
    }

    int nioReactorCount() {
        return nioReadiness.reactorCount();
    }

    /** The VM-owned NIO2 channel group, created on first use. */
    java.nio.channels.AsynchronousChannelGroup nioCompletionGroup() throws java.io.IOException {
        if (!started()) throw new IllegalStateException("Oreslang VM is not running");
        synchronized (nioCompletionLock) {
            if (closed.get()) throw new IllegalStateException("Oreslang VM is closed");
            if (nioCompletionGroup == null) {
                int threads = configuredNioCompletionThreads(
                        System.getProperty("ores.runtime.nio.completion-threads"));
                AtomicInteger completionId = new AtomicInteger();
                ThreadFactory completionFactory = runnable -> Thread.ofPlatform()
                        .daemon(true)
                        .name("ores-nio-completion-" + completionId.getAndIncrement())
                        .unstarted(() -> runAsNioWorker(runnable));
                nioCompletionGroup = java.nio.channels.AsynchronousChannelGroup
                        .withFixedThreadPool(threads, completionFactory);
            }
            return nioCompletionGroup;
        }
    }

    static int configuredNioCompletionThreads(String configured) {
        if (configured == null) return DEFAULT_NIO_COMPLETION_THREADS;
        final int requested;
        try {
            requested = Integer.parseInt(configured.trim());
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    "ores.runtime.nio.completion-threads must be a decimal integer in [1,"
                            + MAX_NIO_COMPLETION_THREADS + "]", malformed);
        }
        if (requested < 1 || requested > MAX_NIO_COMPLETION_THREADS) {
            throw new IllegalArgumentException(
                    "ores.runtime.nio.completion-threads must be in [1,"
                            + MAX_NIO_COMPLETION_THREADS + "]");
        }
        return requested;
    }

    /** True once the completion group exists and has not terminated (tests). */
    boolean nioCompletionGroupLive() {
        synchronized (nioCompletionLock) {
            return nioCompletionGroup != null && !nioCompletionGroup.isTerminated();
        }
    }

    /**
     * Runtime-only descriptor admission after OresContext has checked NET
     * capability/permission. The close race is fenced without a VM monitor:
     * a listener created just before CLOSED is observed closes itself again.
     */
    VmNioTcpListener openNioTcpLoopback(
            int port, int backlog, int maxConnections, int maxPending)
            throws IOException {
        if (!started() || closed.get()) {
            throw new RejectedExecutionException("Oreslang VM is not running");
        }
        AtomicReference<VmNioTcpListener> owner = new AtomicReference<>();
        VmNioTcpListener listener = VmNioTcpListener.openOwned(
                nioReadiness,
                port,
                backlog,
                maxConnections,
                maxPending,
                () -> {
                    VmNioTcpListener terminated = owner.get();
                    if (terminated != null) nioTcpListeners.remove(terminated);
                });
        owner.set(listener);
        nioTcpListeners.add(listener);
        if (!started() || closed.get()) {
            listener.close();
            nioTcpListeners.remove(listener);
            throw new RejectedExecutionException(
                    "Oreslang VM closed during TCP listener admission");
        }
        return listener;
    }

    int nioTcpListenerCount() {
        return nioTcpListeners.size();
    }

    public OresScheduler rootScheduler() {
        if (!started()) {
            throw new IllegalStateException(
                    "Oreslang VM root scheduler was requested before startup");
        }
        return rootScheduler;
    }

    /**
     * Admit runtime CONTROL work through the already-owned root scheduler.
     *
     * Mailmen and other control services share the VM root/CONTROL admission
     * boundary. In a strict isolate this preserves the isolate's JNI/root-turn
     * admission executor rather than hopping guest work onto an unadmitted
     * native carrier.
     */
    OresFuture<Void> executeControl(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (!started()) {
            throw new RejectedExecutionException(
                    "Oreslang VM CONTROL dispatcher is not available");
        }
        return rootScheduler.startSync(() -> {
            task.run();
            return null;
        });
    }

    /**
     * Only physical lifecycle teardown is admitted here. In contrast to
     * executeControl, tasks can block on native/I/O revokers without starving
     * the VM's zero-guest CONTROL admission lane. Work is bounded and no
     * executor is created per actor. Rejected submissions are reported to
     * ActorRuntime, which keeps its fail-closed retirement barrier.
     */
    void executePhysicalStop(Runnable work) {
        executePhysicalStop(work, ignored -> { });
    }

    void executePhysicalStop(Runnable work, Consumer<Throwable> neverStarted) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(neverStarted, "neverStarted");
        if (!started()) {
            throw new RejectedExecutionException(
                    "Oreslang VM physical STOP executor is not available");
        }
        physicalStopExecutor.execute(new PhysicalStopJob(work, neverStarted));
    }

    /**
     * ThreadPoolExecutor.shutdownNow() returns queued jobs it never ran.
     * Cancel their ADMITTED states and deliver an explicit FAILURE receipt.
     * A concurrent worker can win RUNNING before this CAS: its own finally
     * owns the completion, and shutdown cannot pretend it physically exited.
     */
    private void shutdownPhysicalStops() {
        List<Runnable> discarded = physicalStopExecutor.shutdownNow();
        Throwable callbackFailure = null;
        for (Runnable pending : discarded) {
            try {
                if (!(pending instanceof PhysicalStopJob job)) {
                    throw new IllegalStateException(
                            "untracked physical STOP job discarded by shutdownNow");
                }
                job.cancelBeforeStart(new RejectedExecutionException(
                        "VM closed before physical STOP worker started"));
            } catch (RuntimeException | Error failure) {
                if (callbackFailure == null) callbackFailure = failure;
                else if (callbackFailure != failure) callbackFailure.addSuppressed(failure);
            }
        }
        if (callbackFailure != null) {
            throw new IllegalStateException(
                    "VM physical STOP discard notification failed; quarantine required",
                    callbackFailure);
        }
    }

    public boolean isRootSchedulerCurrent() {
        return OresScheduler.current() == rootScheduler;
    }

    /**
     * Fence readiness admission and prove the selector has physically exited
     * before actor/root CONTROL teardown. Return the failure so the remaining
     * VM-owned executors are still retired even when NIO quiescence fails.
     */
    private RuntimeException closeNioReadiness() {
        List<VmNioTcpListener> listeners = List.copyOf(nioTcpListeners);
        for (VmNioTcpListener listener : listeners) listener.close();
        nioReadiness.close();
        // Closing the group closes every channel still open in it, failing
        // their pending operations; join its carriers like the selector.
        java.nio.channels.AsynchronousChannelGroup group;
        synchronized (nioCompletionLock) {
            group = nioCompletionGroup;
        }
        // Recorded, not returned: the selector below must still be joined.
        RuntimeException groupFailure = null;
        if (group != null) {
            try {
                group.shutdownNow();
                if (!group.awaitTermination(5, TimeUnit.SECONDS)) {
                    groupFailure = new IllegalStateException(
                            "Oreslang VM NIO completion group did not terminate");
                }
            } catch (java.io.IOException failure) {
                groupFailure = new IllegalStateException(
                        "Oreslang VM NIO completion group close failed", failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                groupFailure = new IllegalStateException(
                        "interrupted closing NIO completion group", interrupted);
            }
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            long left = deadline - System.nanoTime();
            if (left <= 0 || !nioReadiness.awaitStopped(
                    left, TimeUnit.NANOSECONDS)) {
                return new IllegalStateException(
                        "Oreslang NIO reactor did not terminate before CONTROL shutdown");
            }
            for (VmNioTcpListener listener : listeners) {
                left = deadline - System.nanoTime();
                if (left <= 0 || !listener.awaitTerminated(
                        left, TimeUnit.NANOSECONDS)) {
                    return new IllegalStateException(
                            "Oreslang TCP listener did not retire before CONTROL shutdown");
                }
            }
            nioTcpListeners.removeAll(listeners);
            return groupFailure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new IllegalStateException(
                    "interrupted while closing Oreslang NIO resources", interrupted);
        }
    }

    @Override
    public void close() {
        // A CONTROL/root task cannot wait for its own carrier to leave. Reject
        // BEFORE setting CLOSED or revoking dispatch; otherwise the scheduler's
        // own close guard throws after the VM has been irreversibly poisoned.
        // An ordinary actor/Mailman must likewise delegate blocking VM teardown
        // to its supervising host after its turn finishes.
        if (OresScheduler.current() != null
                || onNioWorker()
                || ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new IllegalStateException(
                    "OresVM must be closed outside actor, Mailman and scheduler turns");
        }
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            startupState = StartupState.CLOSED;
            RuntimeException nioFailure = closeNioReadiness();
            try {
                try {
                    rootScheduler.close();
                } catch (RuntimeException | Error failure) {
                    if (nioFailure != null) failure.addSuppressed(nioFailure);
                    throw failure;
                }
            } finally {
                // ActorRuntime has already requested structured closure. A
                // failed close may leave an actor quarantined; never assume a
                // dropped queued STOP task is a physical retirement receipt.
                RuntimeException physicalShutdownFailure = null;
                try {
                    shutdownPhysicalStops();
                } catch (RuntimeException failure) {
                    physicalShutdownFailure = failure;
                } finally {
                    // Even a broken discard observer cannot prevent CONTROL
                    // carrier teardown or poison later shutdown attempts.
                    controlExecutor.shutdownNow();
                }
                try {
                    boolean physicalDone =
                            physicalStopExecutor.awaitTermination(5, TimeUnit.SECONDS);
                    boolean controlDone =
                            controlExecutor.awaitTermination(5, TimeUnit.SECONDS);
                    if (!controlDone || !physicalDone) {
                        IllegalStateException incomplete = new IllegalStateException(
                                "Oreslang VM carrier teardown incomplete: control="
                                        + controlDone + ", physicalStop=" + physicalDone);
                        if (physicalShutdownFailure != null) {
                            incomplete.addSuppressed(physicalShutdownFailure);
                        }
                        if (nioFailure != null) incomplete.addSuppressed(nioFailure);
                        throw incomplete;
                    }
                    if (physicalShutdownFailure != null) {
                        if (nioFailure != null) physicalShutdownFailure.addSuppressed(nioFailure);
                        throw physicalShutdownFailure;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IllegalStateException failure = new IllegalStateException(
                            "interrupted while closing Oreslang CONTROL scheduler",
                            interrupted);
                    if (nioFailure != null) failure.addSuppressed(nioFailure);
                    throw failure;
                }
            }
            if (nioFailure != null) throw nioFailure;
        }
    }
}
