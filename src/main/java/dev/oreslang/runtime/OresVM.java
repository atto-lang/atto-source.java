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
 * <p>Exactly one logical pool scheduler exists per execution class. The VM
 * establishes all admission queues and lifecycle state eagerly, but no
 * CONTROL carrier needs to exist before the first runnable task. An actor or
 * ActorGroup never owns an OS carrier, and a supervisor's control turns use
 * the shared CONTROL scheduling domain.</p>
 */
public final class OresVM implements AutoCloseable {
    private static final int DEFAULT_CONTROL_PARALLELISM = 5;
    private static final int DEFAULT_QUEUE_CAPACITY = 65_536;

    private enum StartupState { NEW, STARTING, STARTED, FAILED, CLOSED }

    private final ThreadPoolExecutor controlExecutor;
    private final OresScheduler rootScheduler;
    /** A pool scheduler is logical metadata, not an OS thread or a group scheduler. */
    public enum Pool { CONTROL, SHARED, TRUSTED_PRIVATE, UNTRUSTED }

    public final class PoolScheduler {
        private final Pool pool;
        private volatile int maximumCarriers;

        private PoolScheduler(Pool pool, int maximumCarriers) {
            this.pool = pool;
            this.maximumCarriers = maximumCarriers;
        }

        public Pool domain() { return pool; }
        public int maximumCarriers() { return maximumCarriers; }

        /** Snapshot of currently created physical carriers, not actor count. */
        public int createdCarriers() {
            if (pool == Pool.CONTROL) return controlExecutor.getPoolSize();
            ActorRuntime runtime = ownedActors;
            if (runtime == null) return 0;
            return runtime.startedCarrierCount(switch (pool) {
                case SHARED -> ActorRuntime.ActorKind.SHARED;
                case TRUSTED_PRIVATE -> ActorRuntime.ActorKind.PRIVATE;
                case UNTRUSTED -> ActorRuntime.ActorKind.UNTRUSTED;
                default -> throw new AssertionError(pool);
            });
        }
    }

    private final PoolScheduler controlPool;
    private final PoolScheduler sharedPool;
    private final PoolScheduler privatePool;
    private final PoolScheduler untrustedPool;

    public PoolScheduler poolScheduler(Pool domain) {
        return switch (Objects.requireNonNull(domain, "domain")) {
            case CONTROL -> controlPool;
            case SHARED -> sharedPool;
            case TRUSTED_PRIVATE -> privatePool;
            case UNTRUSTED -> untrustedPool;
        };
    }

    /** The VM owns the actor runtime and, transitively, its three carrier pools. */
    private volatile ActorRuntime ownedActors;
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
                .unstarted(OresScheduler.markVmCarrier(runnable));

        this.controlExecutor = new ThreadPoolExecutor(
                controlParallelism,
                controlParallelism,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY),
                factory,
                new ThreadPoolExecutor.AbortPolicy());

        this.controlExecutor.allowCoreThreadTimeOut(true);
        ActorRuntime.DispatcherConfig defaults = ActorRuntime.DispatcherConfig.defaults();
        this.controlPool = new PoolScheduler(Pool.CONTROL, controlParallelism);
        this.sharedPool = new PoolScheduler(Pool.SHARED, defaults.sharedParallelism());
        this.privatePool = new PoolScheduler(Pool.TRUSTED_PRIVATE, defaults.privateParallelism());
        this.untrustedPool = new PoolScheduler(
                Pool.UNTRUSTED, Integer.getInteger("ores.runtime.actor.untrusted.parallelism", 5));

        this.rootScheduler = OresScheduler.runtimeOwned(
                "ores-root-scheduler",
                rootAdmission == null ? controlParallelism : 1,
                rootAdmission == null ? controlExecutor : rootAdmission,
                turnExecutor);
        try {
            startup();
        } catch (RuntimeException | Error failure) {
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
     * Attach the per-context actor runtime to this VM's lifetime. The actor runtime
     * owns the PRIVATE, SHARED and UNTRUSTED dispatchers; OresVM is their final
     * lifecycle authority and closes them before stopping CONTROL.
     *
     * <p>Each VM admits exactly one ActorRuntime. The separate untrusted carrier
     * pool is not a security boundary: hostile tenants require independent
     * Graal sandbox/isolate domains, not merely different worker threads.</p>
     */
    synchronized ActorRuntime createActorRuntime(
            IsolatePolicy policy,
            ActorRuntime.DispatcherConfig config,
            ActorRuntime.TurnExecutor guestTurn) {
        if (closed.get() || !started()) {
            throw new IllegalStateException("Oreslang VM cannot admit actors before startup or after close");
        }
        if (ownedActors != null) {
            throw new IllegalStateException("Oreslang VM already owns an ActorRuntime");
        }
        ActorRuntime runtime = new ActorRuntime(
                Objects.requireNonNull(policy, "policy"),
                Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(guestTurn, "guestTurn"),
                this::executeControl);
        privatePool.maximumCarriers = runtime.carrierParallelism(ActorRuntime.ActorKind.PRIVATE);
        sharedPool.maximumCarriers = runtime.carrierParallelism(ActorRuntime.ActorKind.SHARED);
        untrustedPool.maximumCarriers = runtime.carrierParallelism(ActorRuntime.ActorKind.UNTRUSTED);
        ownedActors = runtime;
        return runtime;
    }

    /**
     * VM bootstrap barrier: validates the logical scheduler without starting
     * physical CONTROL workers. A first root/control task triggers lazy startup.
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
            if (controlExecutor.isShutdown()) {
                throw new IllegalStateException("CONTROL pool shut down before startup");
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

    public boolean isRootSchedulerCurrent() {
        return OresScheduler.current() == rootScheduler;
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;

        RuntimeException failure = null;
        // Actor teardown may still dispatch supervisor CONTROL messages.
        // Keep CONTROL available until all actor carriers have exited.
        try {
            if (ownedActors != null) ownedActors.close();
        } catch (RuntimeException actorFailure) {
            failure = actorFailure;
        } finally {
            startupState = StartupState.CLOSED;
            try {
                rootScheduler.close();
            } catch (RuntimeException rootFailure) {
                if (failure == null) failure = rootFailure;
                else failure.addSuppressed(rootFailure);
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
                            "interrupted while closing Oreslang CONTROL scheduler", interrupted);
                } catch (RuntimeException controlFailure) {
                    if (failure == null) failure = controlFailure;
                    else failure.addSuppressed(controlFailure);
                }
            }
        }
        if (failure != null) throw failure;
    }
}
