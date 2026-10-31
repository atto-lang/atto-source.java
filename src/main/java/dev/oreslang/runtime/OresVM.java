package dev.oreslang.runtime;

import java.util.Objects;
import java.util.List;
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
    // Only runtime-owned physical STOP work sets this marker. A STOP job
    // cannot await the termination of the executor carrying that same job.
    private static final ThreadLocal<OresVM> PHYSICAL_STOP_OWNER = new ThreadLocal<>();
    private static final int DEFAULT_CONTROL_PARALLELISM =
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final int DEFAULT_QUEUE_CAPACITY = 65_536;
    // Resource revokers may block. They must never occupy the CONTROL pool.
    // One bounded, VM-owned executor serves all actors and supervisors.
    private static final int DEFAULT_PHYSICAL_STOP_PARALLELISM =
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final int DEFAULT_PHYSICAL_STOP_QUEUE_CAPACITY = 16_384;

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

    private final ThreadPoolExecutor controlExecutor;
    private final ThreadPoolExecutor physicalStopExecutor;
    private final OresScheduler rootScheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    // Preserve irreversible scheduler/discard-notification failures across
    // a subsequent physical-carrier shutdown retry.
    private Throwable shutdownFailure;

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
        try {
            startup();
        } catch (RuntimeException | Error failure) {
            rootScheduler.close();
            controlExecutor.shutdownNow();
            shutdownPhysicalStops();
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

    /** Test-only deterministic executor limits; no global property mutation. */
    static OresVM createWithPhysicalStopLimits(int parallelism, int queueCapacity) {
        return new OresVM(
                Integer.getInteger("ores.runtime.control.parallelism",
                        DEFAULT_CONTROL_PARALLELISM),
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
        executePhysicalStop(work, ignored -> { });
    }

    void executePhysicalStop(Runnable work, Consumer<Throwable> neverStarted) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(neverStarted, "neverStarted");
        if (!started()) {
            throw new RejectedExecutionException(
                    "Oreslang VM physical STOP executor is not available");
        }
        physicalStopExecutor.execute(new PhysicalStopJob(() -> {
            OresVM previous = PHYSICAL_STOP_OWNER.get();
            PHYSICAL_STOP_OWNER.set(this);
            try {
                work.run();
            } finally {
                if (previous == null) PHYSICAL_STOP_OWNER.remove();
                else PHYSICAL_STOP_OWNER.set(previous);
            }
        }, neverStarted));
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

    @Override
    public void close() {
        // Never wait for the calling task's own CONTROL/STOP carrier. Reject
        // before irrevocably sealing the VM admission state.
        if (OresScheduler.current() != null
                || ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()
                || PHYSICAL_STOP_OWNER.get() == this) {
            throw new IllegalStateException(
                    "OresVM must be closed outside actor, Mailman and scheduler turns or physical STOP workers");
        }
        synchronized (this) {
            if (closed.compareAndSet(false, true)) {
                startupState = StartupState.CLOSED;
                Throwable permanentFailure = null;
                try {
                    rootScheduler.close();
                } catch (RuntimeException | Error schedulerFailure) {
                    permanentFailure = schedulerFailure;
                }
                try {
                    shutdownPhysicalStops();
                } catch (RuntimeException | Error discardFailure) {
                    if (permanentFailure == null) permanentFailure = discardFailure;
                    else if (permanentFailure != discardFailure) {
                        permanentFailure.addSuppressed(discardFailure);
                    }
                } finally {
                    // First close seals/cancels once. Repeated close must not
                    // discard already-running work or mint fresh receipts.
                    controlExecutor.shutdownNow();
                }
                shutdownFailure = permanentFailure;
            }

            // The CLOSED flag is not physical retirement. If the previous
            // attempt timed out, all later attempts must recheck both worker
            // pools before allowing the owning context to free its graph.
            try {
                boolean physicalDone =
                        physicalStopExecutor.awaitTermination(5, TimeUnit.SECONDS);
                boolean controlDone =
                        controlExecutor.awaitTermination(5, TimeUnit.SECONDS);
                if (!controlDone || !physicalDone) {
                    IllegalStateException incomplete = new IllegalStateException(
                            "Oreslang VM carrier teardown incomplete: control="
                                    + controlDone + ", physicalStop=" + physicalDone);
                    if (shutdownFailure != null) incomplete.addSuppressed(shutdownFailure);
                    throw incomplete;
                }
                if (shutdownFailure != null) {
                    throw new IllegalStateException(
                            "Oreslang VM scheduler/discard cleanup failed; physical state quarantined",
                            shutdownFailure);
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
