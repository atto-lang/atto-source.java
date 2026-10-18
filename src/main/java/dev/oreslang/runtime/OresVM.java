package dev.oreslang.runtime;

import java.io.IOException;
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
 * <p>The normal throughput profile cold-starts with zero VM-owned OS threads:
 * CONTROL carriers and the NIO reactor are both demand-driven. The first
 * accepted readiness watch starts reactor #0; the first runnable CONTROL turn
 * creates a carrier. An explicitly configured nonzero CONTROL minimum is a
 * warm-start policy and is prestarted before guest admission.</p>
 */
public final class OresVM implements AutoCloseable {
    // Throughput cold start: zero CONTROL carriers + zero NIO reactors.
    // This counts OresVM-owned threads only, not JVM implementation threads.
    private static final int DEFAULT_CONTROL_PARALLELISM = 0;
    private static final int DEFAULT_QUEUE_CAPACITY = 65_536;
    /** A host I/O mapping callback is not necessarily an OresScheduler turn.
     * Mark its owning CONTROL worker separately so external-host teardown
     * cannot await termination of the very thread invoking close().
     */
    private static final ThreadLocal<Boolean> ON_CONTROL_CARRIER = new ThreadLocal<>();

    static boolean onControlCarrier() {
        return Boolean.TRUE.equals(ON_CONTROL_CARRIER.get());
    }

    /** The NIO selector and runtime completion domains may publish host
     * Future notifications, but neither is an external VM teardown thread.
     * A trusted callback must not synchronously join its own selector,
     * completion pool, or the CONTROL dispatcher it may be waking.
     */
    private static final ThreadLocal<Boolean> ON_NIO_WORKER = new ThreadLocal<>();

    static boolean onNioWorker() {
        return Boolean.TRUE.equals(ON_NIO_WORKER.get());
    }

    static void runAsNioWorker(Runnable work) {
        // Completion callbacks can nest on an existing I/O worker. Clearing
        // the marker unconditionally would let the outer callback bypass the
        // no-self-join VM teardown guard after the inner scope returns.
        Boolean previous = ON_NIO_WORKER.get();
        ON_NIO_WORKER.set(Boolean.TRUE);
        try { work.run(); }
        finally {
            if (previous == null) ON_NIO_WORKER.remove();
            else ON_NIO_WORKER.set(previous);
        }
    }

    private enum StartupState { NEW, STARTING, STARTED, FAILED, CLOSED }

    private final OresCarrierBudget carrierBudget;
    private final OresCarrierPermitPool carrierPermitPool;
    private final ThreadPoolExecutor controlExecutor;
    private final OresScheduler rootScheduler;
    /** VM-owned selector domain: reactor #0 starts on first accepted watch. */
    private final NioReadinessGroup nioReadiness;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile StartupState startupState = StartupState.NEW;

    private OresVM(int controlParallelism, OresScheduler.TurnExecutor turnExecutor, Executor rootAdmission) {
        ActorHeapAdmission.requireSupportedJvmMode(
                System.getProperty("ores.runtime.gc.mode", "hybrid"));
        // Resolve before allocating any native/JVM worker: malformed budgets
        // cannot leave a partially constructed VM with resident threads.
        this.carrierBudget = OresCarrierBudget.configured();
        this.carrierPermitPool = carrierBudget == null
                ? null
                : new OresCarrierPermitPool(carrierBudget.maximum());
        AtomicInteger carrierId = new AtomicInteger();
        ThreadFactory factory = carrierPermitPool == null
                ? runnable -> Thread.ofPlatform()
                        .daemon(true)
                        .name("ores-control-plane-" + carrierId.getAndIncrement())
                        .unstarted(() -> {
                            ON_CONTROL_CARRIER.set(Boolean.TRUE);
                            try { runnable.run(); }
                            finally { ON_CONTROL_CARRIER.remove(); }
                        })
                : carrierPermitPool.threadFactory(
                        OresCarrierPermitPool.Domain.CONTROL,
                        "ores-control-plane-",
                        runnable -> () -> {
                            ON_CONTROL_CARRIER.set(Boolean.TRUE);
                            try { runnable.run(); }
                            finally { ON_CONTROL_CARRIER.remove(); }
                        });

        // CONTROL is demand-driven in the default throughput profile. A
        // configured nonzero minimum is an explicit warm-start policy. Extra
        // carriers are created only when independent runnable CONTROL turns contend.
        int maximumControlParallelism = carrierBudget == null
                ? Integer.getInteger("ores.runtime.control.max.parallelism", 20)
                : carrierBudget.controlReserve();
        validateControlBounds(controlParallelism, maximumControlParallelism);
        this.controlExecutor = new AdaptiveCarrierExecutor(
                controlParallelism, maximumControlParallelism,
                DEFAULT_QUEUE_CAPACITY, factory);

        this.rootScheduler = OresScheduler.runtimeOwned(
                "ores-root-scheduler",
                rootAdmission == null ? maximumControlParallelism : 1,
                rootAdmission == null ? controlExecutor : rootAdmission,
                turnExecutor);
        try {
            startup();
            // Build the bounded readiness domain before publishing the VM,
            // but deliberately open no Selector and start no reactor thread.
            // First accepted I/O demand starts reactor #0; later NIO scaling
            // remains separate from runnable CONTROL/actor carrier expansion.
            // Future subscribers may be host callbacks with unbounded latency.
            // Never consume CONTROL carriers to deliver NIO completions: the
            // readiness manager owns a separately bounded LAZY completion
            // domain (0 resident workers, max 2) and VM close drains it first.
            this.nioReadiness = new NioReadinessGroup(4, 8192, 1024);
        } catch (IOException ioFailure) {
            rootScheduler.close();
            controlExecutor.shutdownNow();
            throw new IllegalStateException("unable to bootstrap VM NIO reactor", ioFailure);
        } catch (RuntimeException | Error failure) {
            rootScheduler.close();
            controlExecutor.shutdownNow();
            throw failure;
        }
    }

    static void validateControlBounds(int minimum, int maximum) {
        if (minimum < 0 || minimum > 20
                || maximum < 1 || maximum < minimum || maximum > 20)
            throw new IllegalArgumentException(
                    "CONTROL carrier limits must satisfy 0 <= minimum <= maximum <= 20 and maximum >= 1");
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
     * VM bootstrap barrier. The default zero-minimum throughput profile starts
     * no CONTROL carrier. If a host explicitly configures a nonzero warm
     * minimum, those carriers are prestarted before STARTED becomes visible.
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

    /** Trusted runtime only; guest access requires its own network grant. */
    NioReadinessGroup nioReadiness() {
        if (!started()) throw new IllegalStateException("NIO VM is not running");
        return nioReadiness;
    }

    int nioReactorCount() { return nioReadiness.reactorCount(); }

    OresCarrierBudget carrierBudget() { return carrierBudget; }

    OresCarrierPermitPool carrierPermitPool() { return carrierPermitPool; }

    /** Test/host diagnostic for OresVM-owned runtime threads only. JVM GC,
     * JIT/JVMCI and embedder threads are intentionally outside this census.
     */
    int ownedRuntimeThreadCount() {
        return controlCarrierCount()
                + nioReadiness.reactorCount()
                + nioReadiness.completionCarrierCount();
    }

    /** CPU-only NIO result conversion, never a guest turn or a selector task.
     * This bounded CONTROL pool cannot execute submitted work inline.
     * A shutdown/overload rejection is handled by the caller's Future bridge.
     */
    void dispatchHostIoMapping(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (closed.get() || !started())
            throw new RejectedExecutionException("VM CONTROL mapping dispatcher is closed");
        controlExecutor.execute(task);
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

    public boolean isRootSchedulerCurrent() {
        return OresScheduler.current() == rootScheduler;
    }

    @Override
    public void close() {
        // External trusted host only. Carrier callbacks must never wait for
        // their own scheduler or selector to terminate.
        if (OresScheduler.current() != null
                || onControlCarrier()
                || onNioWorker()
                || ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new IllegalStateException(
                    "OresVM must be closed outside actor, Mailman and scheduler turns");
        }
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            startupState = StartupState.CLOSED;
            // First fence NIO registrations, then wait for its terminal
            // completion publications while CONTROL is still alive.
            nioReadiness.close();
            try {
                if (!nioReadiness.awaitStopped(5, TimeUnit.SECONDS))
                    throw new IllegalStateException(
                            "Oreslang NIO reactors did not quiesce before CONTROL shutdown");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while closing Oreslang NIO reactor", interrupted);
            } finally {
                try {
                    rootScheduler.close();
                } finally {
                    controlExecutor.shutdownNow();
                    try {
                        if (!controlExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Oreslang CONTROL scheduler carriers did not terminate");
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
}
