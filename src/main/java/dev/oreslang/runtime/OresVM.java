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
 * <p>The master CONTROL scheduler thread starts eagerly, but root-turn
 * carriers are configured with zero live threads. A root turn starts its
 * carrier on demand and never executes on the master CONTROL thread.
 * Isolated roots preserve their caller-owned admission executor.</p>
 */
public final class OresVM implements AutoCloseable {
    private static final int DEFAULT_CONTROL_PARALLELISM =
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final int DEFAULT_QUEUE_CAPACITY = 65_536;

    private enum StartupState { NEW, STARTING, STARTED, FAILED, CLOSED }

    private final ThreadPoolExecutor controlExecutor;
    private final OresScheduler rootScheduler;
    private final OresVMMasterControlLoop masterScheduler = new OresVMMasterControlLoop();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile StartupState startupState = StartupState.NEW;

    private OresVM(int controlParallelism, OresScheduler.TurnExecutor turnExecutor, Executor rootAdmission) {
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

        this.rootScheduler = OresScheduler.runtimeOwned(
                "ores-root-scheduler",
                rootAdmission == null ? controlParallelism : 1,
                rootAdmission == null ? controlExecutor : rootAdmission,
                turnExecutor);
        try {
            masterScheduler.start();
            startup();
        } catch (RuntimeException | Error failure) {
            masterScheduler.shutdown();
            rootScheduler.close();
            controlExecutor.shutdownNow();
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
     * VM bootstrap barrier. STARTED means the master CONTROL loop has
     * started and the root-carrier executor is configured, not that any
     * guest-capable carrier has already been created.
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
            // No control carrier is prestarted. ThreadPoolExecutor owns the
            // admission/activation boundary: execute() starts the first core
            // carrier without running root guest code on master CONTROL.
            if (masterScheduler.startedThreads() != 1) {
                throw new IllegalStateException(
                        "OresVM master scheduler startup barrier incomplete");
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

    int masterSchedulerThreadCount() {
        return masterScheduler.startedThreads();
    }

    void attachActorRuntime(ActorRuntime actors) {
        masterScheduler.attach(actors);
        actors.setMasterPoolWakeup(masterScheduler::signal);
    }

    public OresScheduler rootScheduler() {
        if (!started()) {
            throw new IllegalStateException(
                    "Oreslang VM root scheduler was requested before startup");
        }
        return rootScheduler;
    }

    /**
     * Admit runtime CONTROL work through the owned root scheduler.
     *
     * Mailmen and other control services share root CONTROL admission.
     * First work lazily starts a root carrier. In strict isolates the
     * caller-owned guest admission executor remains authoritative, rather
     * than running guest callbacks on the master thread.
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

    public boolean isRootSchedulerCurrent() {
        return OresScheduler.current() == rootScheduler;
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        startupState = StartupState.CLOSED;
        masterScheduler.shutdown();
        try {
            rootScheduler.close();
        } finally {
            controlExecutor.shutdownNow();
            try {
                if (!controlExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Oreslang CONTROL scheduler carriers did not terminate");
                }
                if (!masterScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "OresVM master scheduler CONTROL thread did not terminate");
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
