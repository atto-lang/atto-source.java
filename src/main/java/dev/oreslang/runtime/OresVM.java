package dev.oreslang.runtime;

import java.util.Objects;
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
    private static final int DEFAULT_CONTROL_PARALLELISM =
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final int DEFAULT_QUEUE_CAPACITY = 65_536;
    // Resource revokers may block. They must never occupy the CONTROL pool.
    // One bounded, VM-owned executor serves all actors and supervisors.
    private static final int DEFAULT_PHYSICAL_STOP_PARALLELISM =
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final int DEFAULT_PHYSICAL_STOP_QUEUE_CAPACITY = 16_384;

    private enum StartupState { NEW, STARTING, STARTED, FAILED, CLOSED }

    private final ThreadPoolExecutor controlExecutor;
    private final ThreadPoolExecutor physicalStopExecutor;
    private final OresScheduler rootScheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile StartupState startupState = StartupState.NEW;

    private OresVM(int controlParallelism, OresScheduler.TurnExecutor turnExecutor, Executor rootAdmission) {
        ActorHeapAdmission.requireSupportedJvmMode(
                System.getProperty("ores.runtime.gc.mode", "hybrid"));
        if (controlParallelism <= 0) {
            throw new IllegalArgumentException("control parallelism must be positive");
        }

        AtomicInteger carrierId = new AtomicInteger();
        ThreadFactory factory = runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("ores-control-plane-" + carrierId.getAndIncrement())
                .unstarted(runnable);

        this.controlExecutor = new ThreadPoolExecutor(
                controlParallelism,
                controlParallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY),
                factory,
                new ThreadPoolExecutor.AbortPolicy());

        int physicalParallelism = Integer.getInteger(
                "ores.runtime.physical-stop.parallelism",
                DEFAULT_PHYSICAL_STOP_PARALLELISM);
        int physicalQueue = Integer.getInteger(
                "ores.runtime.physical-stop.queue-capacity",
                DEFAULT_PHYSICAL_STOP_QUEUE_CAPACITY);
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
            physicalStopExecutor.shutdownNow();
            controlExecutor.shutdownNow();
            throw failure;
        }
        this.rootScheduler = initializedRoot;
        try {
            startup();
        } catch (RuntimeException | Error failure) {
            rootScheduler.close();
            controlExecutor.shutdownNow();
            physicalStopExecutor.shutdownNow();
            throw failure;
        }
    }

    static OresVM create(OresScheduler.TurnExecutor turnExecutor) {
        return create(turnExecutor, null);
    }

    static OresVM create(OresScheduler.TurnExecutor turnExecutor, Executor rootAdmission) {
        return new OresVM(
                Integer.getInteger(
                        "ores.runtime.control.parallelism",
                        DEFAULT_CONTROL_PARALLELISM),
                turnExecutor,
                rootAdmission);
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
            controlExecutor.allowCoreThreadTimeOut(false);
            int required = controlExecutor.getCorePoolSize();
            int newlyStarted = controlExecutor.prestartAllCoreThreads();
            int live = controlExecutor.getPoolSize();
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
        return controlExecutor.getPoolSize();
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
        Objects.requireNonNull(work, "work");
        if (!started()) {
            throw new RejectedExecutionException(
                    "Oreslang VM physical STOP executor is not available");
        }
        physicalStopExecutor.execute(work);
    }

    public boolean isRootSchedulerCurrent() {
        return OresScheduler.current() == rootScheduler;
    }

    @Override
    public void close() {
        // A CONTROL/root task cannot wait for its own carrier to leave. Reject
        // BEFORE setting CLOSED or revoking dispatch; otherwise the scheduler's
        // own close guard throws after the VM has been irreversibly poisoned.
        // An ordinary actor/Mailman must likewise delegate blocking VM teardown
        // to its supervising host after its turn finishes.
        if (OresScheduler.current() != null
                || ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new IllegalStateException(
                    "OresVM must be closed outside actor, Mailman and scheduler turns");
        }
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            startupState = StartupState.CLOSED;
            try {
                rootScheduler.close();
            } finally {
                // ActorRuntime has already requested structured closure. A
                // failed close may leave an actor quarantined; never assume a
                // dropped queued STOP task is a physical retirement receipt.
                physicalStopExecutor.shutdownNow();
                controlExecutor.shutdownNow();
                try {
                    boolean physicalDone =
                            physicalStopExecutor.awaitTermination(5, TimeUnit.SECONDS);
                    boolean controlDone =
                            controlExecutor.awaitTermination(5, TimeUnit.SECONDS);
                    if (!controlDone || !physicalDone) {
                        throw new IllegalStateException(
                                "Oreslang VM carrier teardown incomplete: control="
                                        + controlDone + ", physicalStop=" + physicalDone);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "interrupted while closing Oreslang CONTROL scheduler",
                            interrupted);
                }
            }
        }
    }
}
