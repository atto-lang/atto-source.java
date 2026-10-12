package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Scheduler affinity for ordinary Oreslang tasks.
 *
 * <p>An Oreslang task owns one scheduler for its complete logical lifetime.
 * Physical carrier-thread identity is intentionally not stable: after an
 * {@code await}, the continuation may resume on any carrier owned by the same
 * scheduler.</p>
 *
 * <p>Futures do not own schedulers. A suspended task records the scheduler that
 * was executing it when it reached {@code await}; Future completion only makes
 * that task runnable again. Producer, timer, I/O, JNI, and other completion
 * threads never execute the task continuation directly.</p>
 *
 * <p>The compiler lowers an async callable to {@link Task}: a small resumable
 * state machine. Returning {@link Await} suspends the task. Returning
 * {@link Done} completes it.</p>
 */
public final class OresScheduler implements AutoCloseable {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final AtomicLong NEXT_DISPATCH_ID = new AtomicLong();
    private static final AtomicLong NEXT_PHYSICAL_RECEIPT_GENERATION = new AtomicLong();
    private static final ThreadLocal<OresScheduler> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_DISPATCH_ID = new ThreadLocal<>();
    private static final ThreadLocal<Object> CURRENT_TASK_DOMAIN = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SCHEDULER_CARRIER = new ThreadLocal<>();
    // The physical carrier remains owned through post-guest terminal Future
    // publication, after CURRENT has already been restored to its caller.
    private static final ThreadLocal<OresScheduler> ACTIVE_CARRIER_OWNER =
            new ThreadLocal<>();
    private static final int DEFAULT_QUEUE_CAPACITY = 65_536;

    @FunctionalInterface
    interface TurnExecutor {
        void execute(Runnable turn);
    }

    /** One compiler-generated async state-machine turn. */
    @FunctionalInterface
    public interface Task<T> {
        Step<T> resume(Resume resume) throws Exception;
    }

    /**
     * An unforgeable-in-guest physical completion claim for exactly one
     * scheduler-owned pull. Only the scheduler may issue a receipt. This
     * private constructor deliberately cannot be reached by guest code or
     * by a caller-supplied Future passed to the host turn pump.
     *
     * The DONE signal is published only from the scheduler task's terminal
     * completion. TaskRunner publishes that terminal state after every
     * reserved carrier has exited TurnExecutor (and thus TruffleContext.leave).
     */
    static final class PhysicalDoneReceipt {
        private final OresScheduler issuer;
        private final HostGeneratorTurnPump.OwnerToken owner;
        private final long generation;
        private final OresFuture<?> sourceTask;
        // Opaque pump Request identity, never exposed to the guest or host.
        // Null means issued without an active, authorized pump reservation.
        private final Object boundRequest;
        private final OresFuture<Void> physicalDone;
        private final AtomicBoolean redeemed = new AtomicBoolean();

        private PhysicalDoneReceipt(OresScheduler issuer,
                HostGeneratorTurnPump.OwnerToken owner, long generation,
                OresFuture<?> sourceTask, Object boundRequest,
                OresFuture<Void> physicalDone) {
            this.issuer = Objects.requireNonNull(issuer, "issuer");
            this.owner = Objects.requireNonNull(owner, "owner");
            this.generation = generation;
            this.sourceTask = Objects.requireNonNull(sourceTask, "sourceTask");
            this.boundRequest = boundRequest;
            this.physicalDone = Objects.requireNonNull(physicalDone, "physicalDone");
        }

        OresFuture<Void> physicalDone() { return physicalDone; }

        boolean claimFor(HostGeneratorTurnPump.OwnerToken expectedOwner,
                OresScheduler expectedScheduler, Object expectedRequest,
                OresFuture<?> expectedResult, OresFuture<?> expectedPhysicalDone) {
            // A proof must be bound to the original task AND may authorize
            // precisely one pull. Reusing an already-DONE task to advance the
            // next request would be a physical turn replay.
            return generation > 0 && owner.equals(expectedOwner)
                    && issuer == expectedScheduler && boundRequest != null
                    && boundRequest == expectedRequest
                    && sourceTask == expectedResult
                    && physicalDone == expectedPhysicalDone
                    && redeemed.compareAndSet(false, true);
        }
    }

    /**
     * Issue a scheduler-owned physical DONE lease for a compiler-lowered
     * generator pull. No independent producer is created. A failed or
     * cancelled public result is still physically DONE after TaskRunner's
     * final carrier-exit barrier has published terminal completion.
     *
     * This is an internal primitive, not authorization to enable the
     * legacy host async-generator API.
     */
    <T> HostGeneratorTurnPump.Turn<T> startCertifiedGeneratorTurn(
            HostGeneratorTurnPump.OwnerToken owner, Task<T> task) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(task, "task");
        // If a pump has reserved a certified pull on this thread, claim its
        // exact one-use issuance lease BEFORE scheduling any guest task.
        // Outside that scope the receipt is deliberately unredeemable.
        Object boundRequest = HostGeneratorTurnPump.bindCertifiedIssuance(owner, this);
        if (boundRequest == null) {
            // No root-owned pump reservation means NO scheduler producer may
            // start. Reject before generating a task, guest side effects, or
            // physical receipt; later rejection of redemption is too late.
            throw new SecurityException(
                    "certified generator start requires an active pump reservation");
        }
        long generation = NEXT_PHYSICAL_RECEIPT_GENERATION.incrementAndGet();
        TaskRunner<T> runner = startRunner(task);
        OresFuture<T> result = runner.completion;
        OresFuture<Void> physicalDone = new OresFuture<>();
        result.whenCompleteRuntime((ignored, failure) -> {
            // Terminal completion alone is insufficient if TruffleContext.leave
            // (or the turn executor containing it) threw. The scheduler still
            // publishes task failure after draining leases, but that must not
            // be mistaken for successful physical DONE evidence.
            if (runner.physicalExitUnproven.get()) {
                physicalDone.failFromRuntime(new IllegalStateException(
                        "guest turn executor failed: physical context exit not proven",
                        runner.physicalExitFailure.get()));
            } else {
                physicalDone.completeFromRuntime(null);
            }
        });
        return new HostGeneratorTurnPump.Turn<>(result,
                new PhysicalDoneReceipt(this, owner, generation, result,
                        boundRequest, physicalDone));
    }

    /** Cancellation-aware compiler frames can suspend during lexical cleanup. */
    public interface CancellationAwareTask<T> extends Task<T> {
        boolean cleanupInProgress();

        /** Host threads set a flag only; never execute guest cleanup. */
        void cancellationRequested(TaskCancelledException reason);
    }

    /** Internal cancellation control signal; never a guest catchable exception. */
    public static final class TaskCancelledException
            extends java.util.concurrent.CancellationException {
        public TaskCancelledException() {
            super("OresScheduler source task cancellation requested");
        }
    }

    /** Result of one resumable task turn. */
    public sealed interface Step<T> permits Done, Await, Cooperate { }

    /** The async task has produced its final value. */
    public record Done<T>(T value) implements Step<T> { }

    /**
     * The async task is suspended on a Future. The scheduler, not the Future,
     * owns the continuation placement.
     */
    public record Await<T>(OresFuture<?> future) implements Step<T> {
        public Await {
            Objects.requireNonNull(future, "future");
        }
    }

    /**
     * Cooperative scheduler handoff. This produces no language value and
     * resumes the same task only after the current carrier turn has unwound.
     */
    public record Cooperate<T>() implements Step<T> { }

    /**
     * Input delivered to a compiler-generated state machine when it starts or
     * resumes after an await.
     */
    public record Resume(boolean initial, Object value, Throwable failure) {
        private static Resume initialResume() {
            return new Resume(true, null, null);
        }

        private static Resume completed(Object value, Throwable failure) {
            return new Resume(false, value, failure);
        }
    }

    private final String name;
    private final int parallelism;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final TurnExecutor turnExecutor;
    private final Set<TaskRunner<?>> tasks = ConcurrentHashMap.newKeySet();
    // Guard re-entrant close from callbacks published synchronously while a
    // host caller is draining queued/cancelled tasks. Guarded by this monitor.
    private Thread activeCloseThread;
    private final AtomicBoolean closed = new AtomicBoolean();
    // Read/written only while holding this scheduler's synchronized close().
    // Retains the original failed-drain exception for concurrent host callers.
    private Throwable closeFailure;
    private static final long CLOSE_DRAIN_NANOS = TimeUnit.SECONDS.toNanos(5);

    /**
     * Create a user-owned scheduler with exactly {@code parallelism} carrier
     * threads and a bounded ready queue.
     */
    public OresScheduler(int parallelism) {
        this(parallelism, DEFAULT_QUEUE_CAPACITY, Runnable::run);
    }

    public OresScheduler(int parallelism, int queueCapacity) {
        this(parallelism, queueCapacity, Runnable::run);
    }

    private OresScheduler(
            int parallelism,
            int queueCapacity,
            TurnExecutor turnExecutor) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("scheduler parallelism must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("scheduler queue capacity must be positive");
        }

        this.name = "ores-user-scheduler-" + NEXT_ID.incrementAndGet();
        this.parallelism = parallelism;
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");

        AtomicInteger carrierId = new AtomicInteger();
        ThreadFactory factory = task -> Thread.ofPlatform()
                .daemon(true)
                .name(name + "-carrier-" + carrierId.getAndIncrement())
                .unstarted(() -> {
                    SCHEDULER_CARRIER.set(Boolean.TRUE);
                    try {
                        task.run();
                    } finally {
                        SCHEDULER_CARRIER.remove();
                    }
                });
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        pool.prestartAllCoreThreads();
        this.executor = pool;
        this.ownedExecutor = pool;
    }

    private OresScheduler(
            String name,
            int parallelism,
            Executor executor,
            ExecutorService ownedExecutor,
            TurnExecutor turnExecutor) {
        this.name = Objects.requireNonNull(name, "name");
        if (parallelism <= 0) {
            throw new IllegalArgumentException("scheduler parallelism must be positive");
        }
        this.parallelism = parallelism;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = ownedExecutor;
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");
    }

    /**
     * Runtime-owned scheduler facade over an existing VM executor. Closing the
     * facade cancels its tasks but does not shut down the shared VM executor.
     */
    static OresScheduler runtimeOwned(
            String name,
            int parallelism,
            Executor executor) {
        return runtimeOwned(name, parallelism, executor, Runnable::run);
    }

    /**
     * Runtime-owned scheduler whose physical carrier dispatch is distinct from
     * guest-turn admission. The carrier executor owns only where the task runs;
     * the turn executor owns the entered language/context boundary.
     *
     * <p>This separation is critical for await completion publication:
     * {@link TaskRunner#afterCarrierTurn()} runs after {@code turnExecutor}
     * returns, so a terminal Future cannot become externally visible until the
     * guest turn has completely left its Truffle context.</p>
     */
    static OresScheduler runtimeOwned(
            String name,
            int parallelism,
            Executor executor,
            TurnExecutor turnExecutor) {
        return new OresScheduler(
                name,
                parallelism,
                executor,
                null,
                turnExecutor);
    }

    /**
     * Context-owned scheduler with private carriers. Each guest turn is wrapped
     * by the owning language context before scheduler binding is installed.
     */
    static OresScheduler managed(
            int parallelism,
            TurnExecutor turnExecutor) {
        return new OresScheduler(
                parallelism,
                DEFAULT_QUEUE_CAPACITY,
                turnExecutor);
    }

    /** True only on private carriers owned by user-created OresSchedulers. */
    public static boolean isSchedulerCarrierThread() {
        return Boolean.TRUE.equals(SCHEDULER_CARRIER.get());
    }

    public String name() {
        return name;
    }

    public int parallelism() {
        return parallelism;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Scheduler currently executing this Ores task turn, if any. */
    public static OresScheduler current() {
        return CURRENT.get();
    }

    public static OresScheduler requireCurrent() {
        OresScheduler scheduler = CURRENT.get();
        if (scheduler == null) {
            throw new IllegalStateException(
                    "operation requires an executing OresScheduler task");
        }
        return scheduler;
    }

    /**
     * Stable logical execution-domain token for the currently running
     * scheduler task, or {@code null} outside a scheduler task.
     *
     * <p>The token survives await/resume carrier migration and is intentionally
     * distinct for concurrent tasks sharing the same OresScheduler.</p>
     */
    public static Object currentTaskDomain() {
        return CURRENT_TASK_DOMAIN.get();
    }

    /**
     * Identifier for the current scheduler dispatch turn, or {@code 0} outside
     * an OresScheduler dispatch. A continuation resumed after {@code await}
     * always observes a different dispatch id, even when the scheduler chooses
     * the same physical carrier thread immediately.
     */
    public static long currentDispatchId() {
        Long id = CURRENT_DISPATCH_ID.get();
        return id == null ? 0L : id;
    }

    public static <T> Step<T> done(T value) {
        return new Done<>(value);
    }

    public static <T> Step<T> await(OresFuture<?> future) {
        return new Await<>(Objects.requireNonNull(future, "future"));
    }

    /** Lowering target for source-level `rt cooperate`. */
    public static <T> Step<T> cooperate() {
        return new Cooperate<>();
    }

    /**
     * Start one compiler-lowered async task on this scheduler.
     *
     * <p>The returned Future represents the whole task. Awaiting it from another
     * scheduler does not move the waiter onto this scheduler.</p>
     */
    public <T> OresFuture<T> start(Task<T> task) {
        return startRunner(task).completion;
    }

    private <T> TaskRunner<T> startRunner(Task<T> task) {
        Objects.requireNonNull(task, "task");
        // Admission and close share the same monitor. No new task may slip
        // into the live registry after close() snapshots cancellation targets.
        TaskRunner<T> runner;
        synchronized (tasks) {
            ensureOpen();
            runner = new TaskRunner<>(task);
            tasks.add(runner);
        }
        // close() may win after ensureOpen() and before registration. Do not
        // admit a new task after close snapshots and drains live tasks.
        if (closed.get()) {
            runner.failBeforeStart(new RejectedExecutionException(
                    "OresScheduler " + name + " closed during task admission"));
            return runner;
        }
        try {
            runner.scheduleInitial();
        } catch (RuntimeException | Error failure) {
            runner.failBeforeStart(failure);
        }
        return runner;
    }

    /**
     * Runtime/host bridge for non-suspending work. Source-level synchronous
     * lambdas may lower to this path.
     */
    public <T> OresFuture<T> startSync(Callable<? extends T> task) {
        Objects.requireNonNull(task, "task");
        AtomicBoolean entered = new AtomicBoolean();
        return start(resume -> {
            if (!resume.initial() || !entered.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "synchronous scheduler task was resumed more than once");
            }
            return done(task.call());
        });
    }

    /**
     * Bind an already-admitted VM CONTROL/root turn to this scheduler without
     * hopping threads. Used by the legacy root-task bridge while source-level
     * main/async lowering moves to {@link #start(Task)}.
     */
    void runBound(Runnable turn) {
        Objects.requireNonNull(turn, "turn");
        ensureOpen();
        OresScheduler prior = CURRENT.get();
        Long priorDispatch = CURRENT_DISPATCH_ID.get();
        CURRENT.set(this);
        CURRENT_DISPATCH_ID.set(NEXT_DISPATCH_ID.incrementAndGet());
        try {
            turn.run();
        } finally {
            if (priorDispatch == null) {
                CURRENT_DISPATCH_ID.remove();
            } else {
                CURRENT_DISPATCH_ID.set(priorDispatch);
            }
            if (prior == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prior);
            }
        }
    }

    /** Unlike public runBound(), admits only work belonging to an existing TaskRunner. */
    private void runBoundAdmittedTask(Runnable turn) {
        OresScheduler prior = CURRENT.get();
        Long priorDispatch = CURRENT_DISPATCH_ID.get();
        CURRENT.set(this);
        CURRENT_DISPATCH_ID.set(NEXT_DISPATCH_ID.incrementAndGet());
        try {
            turn.run();
        } finally {
            if (priorDispatch == null) CURRENT_DISPATCH_ID.remove();
            else CURRENT_DISPATCH_ID.set(priorDispatch);
            if (prior == null) CURRENT.remove();
            else CURRENT.set(prior);
        }
    }

    private void executeTurn(Runnable turn,
            java.util.function.Consumer<Boolean> afterTurn,
            java.util.function.Consumer<Throwable> admissionFailure,
            java.util.function.BooleanSupplier enterContext) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(afterTurn, "afterTurn");
        // Existing admitted tasks may resume cancellation cleanup after close().
        executor.execute(() -> {
            boolean priorCarrier = Boolean.TRUE.equals(SCHEDULER_CARRIER.get());
            OresScheduler priorOwner = ACTIVE_CARRIER_OWNER.get();
            SCHEDULER_CARRIER.set(Boolean.TRUE);
            ACTIVE_CARRIER_OWNER.set(this);
            boolean physicalExitConfirmed = true;
            try {
                // A queued cancellation may revoke its unclaimed carrier
                // ticket. In that case the already-submitted Runnable is a
                // no-op and must never enter the Truffle/VM context.
                if (enterContext.getAsBoolean()) {
                    physicalExitConfirmed = false;
                    turnExecutor.execute(() -> runBoundAdmittedTask(turn));
                    // A normal return from the context-owned turn executor
                    // certifies that its TruffleContext.leave completed.
                    physicalExitConfirmed = true;
                }
            } catch (RuntimeException | Error failure) {
                // Context admission can fail before runTurn starts. Settle the
                // owning task instead of leaving the host waiting forever.
                admissionFailure.accept(failure);
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                if (failure instanceof LinkageError fatal) throw fatal;
            } finally {
                if (priorCarrier) {
                    SCHEDULER_CARRIER.set(Boolean.TRUE);
                } else {
                    SCHEDULER_CARRIER.remove();
                }
                try {
                    afterTurn.accept(physicalExitConfirmed);
                } finally {
                    if (priorOwner == null) ACTIVE_CARRIER_OWNER.remove();
                    else ACTIVE_CARRIER_OWNER.set(priorOwner);
                }
            }
        });
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new RejectedExecutionException(
                    "OresScheduler " + name + " is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (CURRENT.get() == this || ACTIVE_CARRIER_OWNER.get() == this) {
            throw new IllegalStateException(
                    "OresScheduler cannot be closed from its own task turn or "
                            + "terminal Future callback on an owned carrier; "
                            + "close from an outside/root scheduler task");
        }

        if (activeCloseThread == Thread.currentThread()) {
            throw new IllegalStateException(
                    "OresScheduler close cannot be re-entered from a terminal "
                            + "Future callback during its own cleanup drain");
        }
        activeCloseThread = Thread.currentThread();
        try {
        // Closed seals new admission; it is not a proof of quiescence.
        // A failed first close can be retried after an owned defer/finally
        // resolves. Concurrent close callers serialize on this monitor.
        if (!closed.get()) {
            Set<TaskRunner<?>> closingTasks;
            synchronized (tasks) {
                if (!closed.compareAndSet(false, true)) {
                    throw new IllegalStateException("scheduler close admission lost its owner");
                }
                closingTasks = Set.copyOf(tasks);
            }
            for (TaskRunner<?> task : closingTasks) {
                task.cancelFromSchedulerClose();
            }
        }

        long deadline = System.nanoTime() + CLOSE_DRAIN_NANOS;
        synchronized (tasks) {
            while (!tasks.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    TimeUnit.NANOSECONDS.timedWait(tasks, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    closeFailure = new IllegalStateException(
                            "interrupted during cancellation cleanup drain in " + name,
                            interrupted);
                    throw (IllegalStateException) closeFailure;
                }
            }
            if (!tasks.isEmpty()) {
                // Do not failTerminal() unfinished guest finalizers or shut
                // down the only carrier capable of re-entering them.
                closeFailure = new IllegalStateException(
                        "OresScheduler " + name + " still owns unfinished cancellation cleanup");
                throw (IllegalStateException) closeFailure;
            }
        }

        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
            try {
                if (!ownedExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    closeFailure = new IllegalStateException(
                            "OresScheduler " + name + " carriers did not terminate");
                    throw (IllegalStateException) closeFailure;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                closeFailure = new IllegalStateException(
                        "interrupted while retiring OresScheduler " + name, interrupted);
                throw (IllegalStateException) closeFailure;
            }
        }
        closeFailure = null;
        } finally {
            activeCloseThread = null;
        }
    }

    private final class TaskRunner<T> implements OresFuture.TaggedRuntimeWaiter {
        private static final int NEW = 0;
        private static final int QUEUED = 1;
        private static final int RUNNING = 2;
        private static final int WAITING = 3;
        private static final int TERMINAL = 4;

        private final Task<T> task;
        private final java.util.Map<String, dev.oreslang.interop.MixedInteropBridge.Invoker>
                interopScope = dev.oreslang.interop.MixedInteropBridge.capture();
        private final AtomicInteger phase = new AtomicInteger(NEW);
        private final AtomicBoolean executing = new AtomicBoolean();
        // Permanent negative evidence for this task, including guest leave
        // failure; no later cancellation or retry may turn it into DONE.
        private final AtomicBoolean physicalExitUnproven = new AtomicBoolean();
        private final AtomicReference<Throwable> physicalExitFailure =
                new AtomicReference<>();
        // Reserved before QUEUED publication; released after VM carrier exit.
        private final AtomicInteger outstandingCarrierTurns = new AtomicInteger();
        private final AtomicReference<CarrierLease> queuedCarrierLease =
                new AtomicReference<>();

        /** CAS arbitration between VM admission and revocation of queued work. */
        private final class CarrierLease {
            // 0 reserved/queued, 1 context entry claimed, 2 revoked.
            private final AtomicInteger admission = new AtomicInteger();
            private final AtomicBoolean released = new AtomicBoolean();

            boolean enterContext() {
                boolean entered = admission.compareAndSet(0, 1);
                if (entered) queuedCarrierLease.compareAndSet(this, null);
                return entered;
            }

            boolean revoke() {
                if (!admission.compareAndSet(0, 2)) return false;
                queuedCarrierLease.compareAndSet(this, null);
                release();
                return true;
            }

            void release() {
                queuedCarrierLease.compareAndSet(this, null);
                if (released.compareAndSet(false, true)) releaseCarrierReservation();
            }
        }

        private CarrierLease reserveCarrier() {
            CarrierLease lease = new CarrierLease();
            outstandingCarrierTurns.incrementAndGet();
            queuedCarrierLease.set(lease);
            return lease;
        }
        private final AtomicReference<Resume> pendingResume =
                new AtomicReference<>(Resume.initialResume());
        private final AtomicReference<TerminalOutcome<T>> terminalOutcome =
                new AtomicReference<>();
        private final AtomicReference<OresFuture.RuntimeWaiterRegistration<?>>
                activeAwaitRegistration = new AtomicReference<>();
        private final OresFuture<T> completion;
        // A claimed callback from an abandoned Future may arrive after a
        // cancellation continuation has entered a *different* cleanup await.
        // The generation and pending-resume publication are one operation:
        // stale producers cannot inject an old payload into that cleanup.
        private final Object awaitDeliveryLock = new Object();
        private long awaitGeneration;
        // Cancellation admission and terminal outcome arbitration must be
        // indivisible. The atomic phase transitions alone were insufficient:
        // cancel() could succeed after finish() selected a success outcome.
        private final Object terminalAdmissionLock = new Object();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean everStarted = new AtomicBoolean();
        private final TaskCancelledException cancellation = new TaskCancelledException();

        private TaskRunner(Task<T> task) {
            this.task = task;
            this.completion = new OresFuture<>(
                    this::admitCancellation, this::onCancellationRequested, true);
        }

        private boolean admitCancellation() {
            synchronized (terminalAdmissionLock) {
                if (phase.get() == TERMINAL || cancelRequested.get()) {
                    return false;
                }
                cancelRequested.set(true);
                return true;
            }
        }

        /**
         * Never enter a guest source task merely to cancel it before its first
         * dispatch. Once a source frame has started, only a lane-owned unwind
         * may complete cancellation; QUEUED can mean a pending cleanup turn.
         */
        private boolean cancelWithoutExecutingWhenIdle(boolean requireUnstarted) {
            synchronized (terminalAdmissionLock) {
                if (requireUnstarted && everStarted.get()) return false;
                int observed = phase.get();
                if (observed != NEW && observed != QUEUED && observed != WAITING) {
                    return false;
                }
                if (observed == QUEUED) {
                    // Do not settle a carrier that has already claimed VM entry.
                    // A still-queued carrier can be revoked without executing
                    // any guest code or consuming the actor CONTROL queue.
                    CarrierLease lease = queuedCarrierLease.get();
                    if (lease == null || !lease.revoke()) return false;
                }
                if (!phase.compareAndSet(observed, TERMINAL)) return false;
                terminalOutcome.set(new TerminalCancelled<T>(cancellation));
            }
            // A cancelled WAITING task must not retain the shared producer.
            // Unregister it before terminal completion can unblock context
            // teardown or synchronous Future observers.
            detachActiveAwaitRegistration();
            if (!executing.get()) publishTerminalIfReady();
            return true;
        }

        private boolean isCleaningUp() {
            return task instanceof CancellationAwareTask<?> aware
                    && aware.cleanupInProgress();
        }

        private void onCancellationRequested() {
            try {
                if (task instanceof CancellationAwareTask<?> aware) {
                    aware.cancellationRequested(cancellation);
                    if (cancelWithoutExecutingWhenIdle(true)) {
                        return;
                    }
                } else {
                    // Tasks without lexical cleanup can be cancelled directly
                    // while idle. Compare-and-set prevents a stale WAITING or
                    // QUEUED read from aborting a newly RUNNING guest turn.
                    if (cancelWithoutExecutingWhenIdle(false)) {
                        return;
                    }
                }
                boolean wakeCancelled = false;
                synchronized (awaitDeliveryLock) {
                    if (phase.get() == WAITING && !isCleaningUp()) {
                        ++awaitGeneration;
                        pendingResume.set(Resume.completed(null, cancellation));
                        wakeCancelled = true;
                    }
                }
                if (wakeCancelled) {
                    detachActiveAwaitRegistration();
                    scheduleReadyResume();
                }
            } catch (RuntimeException | Error failure) {
                // OresFuture's cancellation hook suppresses callback failures.
                // Settle the scheduler task explicitly instead of stranding it.
                failTerminal(failure);
            }
        }

        private void scheduleInitial() {
            CarrierLease lease;
            synchronized (terminalAdmissionLock) {
                // The reservation and QUEUED transition are one admission:
                // close/cancel cannot observe QUEUED without its revocable
                // lease, or a second producer replace the published lease.
                if (phase.get() != NEW)
                    throw new IllegalStateException("scheduler task was already started");
                lease = reserveCarrier();
                phase.set(QUEUED);
            }
            enqueueTurn(lease);
        }

        private void enqueueTurn(CarrierLease lease) {
            try {
                executeTurn(() -> {
                    try (var scope = dev.oreslang.interop.MixedInteropBridge.open(interopScope)) {
                        runTurn();
                    }
                }, normalExit -> afterCarrierTurn(lease::release, normalExit),
                        failure -> {
                            physicalExitFailure.compareAndSet(null, failure);
                            failTerminal(failure);
                        }, lease::enterContext);
            } catch (RuntimeException | Error rejected) {
                lease.release();
                failTerminal(rejected);
                throw rejected;
            }
        }

        private void releaseCarrierReservation() {
            if (outstandingCarrierTurns.decrementAndGet() < 0)
                throw new IllegalStateException("scheduler carrier reservation underflow");
            publishTerminalIfReady();
        }

        private void runTurn() {
            if (!phase.compareAndSet(QUEUED, RUNNING)) return;
            if (!executing.compareAndSet(false, true)) {
                failTerminal(new IllegalStateException(
                        "OresScheduler task execution lease violation"));
                return;
            }

            everStarted.set(true);
            Object priorTaskDomain = CURRENT_TASK_DOMAIN.get();
            CURRENT_TASK_DOMAIN.set(this);
            try {
                detachActiveAwaitRegistration();
                Resume resume = pendingResume.getAndSet(null);
                if (resume == null) {
                    failTerminal(new IllegalStateException(
                            "scheduler resumed a task without an await result"));
                    return;
                }

                if (cancelRequested.get() && !isCleaningUp()) {
                    if (resume.initial() || !(task instanceof CancellationAwareTask<?>)) {
                        failTerminal(cancellation);
                        return;
                    }
                    resume = Resume.completed(null, cancellation);
                }

                final Step<T> step;
                try {
                    step = Objects.requireNonNull(
                            task.resume(resume),
                            "OresScheduler task returned null Step");
                } catch (Throwable failure) {
                    failTerminal(failure);
                    if (failure instanceof VirtualMachineError fatal) throw fatal;
                    if (failure instanceof ThreadDeath fatal) throw fatal;
                    if (failure instanceof LinkageError fatal) throw fatal;
                    return;
                }

                if (step instanceof Done<?> done) {
                    @SuppressWarnings("unchecked")
                    T value = (T) done.value();
                    finish(value);
                    return;
                }

                if (step instanceof Await<?> await) {
                    armAwait(await.future());
                    return;
                }

                if (step instanceof Cooperate<?>) {
                    armCooperate();
                    return;
                }

                failTerminal(new IllegalStateException(
                        "unknown OresScheduler task step " + step.getClass().getName()));
            } finally {
                if (priorTaskDomain == null) {
                    CURRENT_TASK_DOMAIN.remove();
                } else {
                    CURRENT_TASK_DOMAIN.set(priorTaskDomain);
                }
            }
        }

        /**
         * Runs only after the scheduler binding has been removed and the
         * carrier has completely unwound the logical guest turn. Publishing a
         * task Future earlier can let a host close its Polyglot Context while
         * this carrier is still executing guest continuation code.
         */
        private void afterCarrierTurn(Runnable release, boolean physicalExitConfirmed) {
            if (!physicalExitConfirmed) physicalExitUnproven.set(true);
            executing.set(false);
            release.run();
            scheduleReadyResume();
        }

        private void armCooperate() {
            Resume resume = cancelRequested.get() && !isCleaningUp()
                    ? Resume.completed(null, cancellation)
                    : Resume.completed(null, null);
            if (!pendingResume.compareAndSet(null, resume)) {
                failTerminal(new IllegalStateException(
                        "cooperate attempted to publish more than one resume"));
                return;
            }
            if (!phase.compareAndSet(RUNNING, WAITING)) {
                pendingResume.compareAndSet(resume, null);
            }
            // afterCarrierTurn() owns requeueing so this dispatch fully
            // unwinds before the continuation becomes runnable again.
        }

        private void armAwait(OresFuture<?> awaited) {
            if (!phase.compareAndSet(RUNNING, WAITING)) {
                return;
            }
            final long generation;
            synchronized (awaitDeliveryLock) {
                generation = ++awaitGeneration;
                if (cancelRequested.get() && !isCleaningUp()) {
                    pendingResume.set(Resume.completed(null, cancellation));
                    return;
                }
            }

            try {
                // Hot path: terminal Futures are immutable. Observe their
                // already-published terminal state directly and enqueue the
                // continuation for a fresh dispatch without allocating a
                // waiter, queue node, registration, or capturing callback.
                //
                // This deliberately does NOT resume inline. executing is still
                // true until afterCarrierTurn(), so deliverAwaitCompletion()
                // can only make the task ready; the scheduler re-enters it
                // through a later dispatch after the current stack unwinds.
                Object terminal = awaited.runtimeTerminalStateOrNull();
                if (terminal != null) {
                    deliverAwaitCompletion(
                            generation,
                            OresFuture.runtimeTerminalValue(terminal),
                            OresFuture.runtimeTerminalFailure(terminal));
                    return;
                }

                // The reusable tagged callback carries the registration's
                // immutable await generation, without allocating a lambda
                // for each pending suspension. A claimed *older* waiter can
                // never deliver into the current lexical cleanup await.
                OresFuture.RuntimeWaiterRegistration<?> registration =
                        awaited.whenCompleteRuntimeCancellableTagged(this, generation);

                OresFuture.RuntimeWaiterRegistration<?> previous =
                        activeAwaitRegistration.getAndSet(registration);
                if (previous != null) previous.detach();

                // Settlement may race between the terminal observation above
                // and waiter publication. whenCompleteRuntimeCancellable()
                // handles that race and may call accept(...) synchronously.
                // If it did, clear the already-claimed registration now.
                if (phase.get() != WAITING || pendingResume.get() != null) {
                    detachActiveAwaitRegistration();
                }
            } catch (RuntimeException | Error registrationFailure) {
                failTerminal(registrationFailure);
            }
        }

        @Override
        public void accept(long generation, Object value, Throwable failure) {
            deliverAwaitCompletion(generation, value, failure);
        }

        private void deliverAwaitCompletion(long generation, Object value, Throwable failure) {
            boolean duplicate = false;
            synchronized (awaitDeliveryLock) {
                // In-flight callbacks from detached waits may already have
                // claimed their registration. Epoch matching is still needed
                // even if detach reported success for the other waiters.
                if (generation != awaitGeneration || phase.get() == TERMINAL
                        || (cancelRequested.get() && !isCleaningUp())) return;

                Resume resume = Resume.completed(
                        value, failure == null ? null : OresFuture.unwrap(failure));
                if (!pendingResume.compareAndSet(null, resume)) {
                    if (cancelRequested.get() && !isCleaningUp()) return;
                    duplicate = true;
                } else if (phase.get() == TERMINAL) {
                    pendingResume.compareAndSet(resume, null);
                    return;
                }
            }
            if (duplicate) {
                failTerminal(new IllegalStateException(
                        "await delivered more than one resume to the same task"));
                return;
            }
            scheduleReadyResume();
        }

        /**
         * Producer completion may happen on any thread. It may only make this
         * task runnable. The continuation itself executes after the prior turn
         * released its execution lease and only through this scheduler.
         */
        private void scheduleReadyResume() {
            if (executing.get()) return;
            if (pendingResume.get() == null) return;
            CarrierLease lease;
            synchronized (terminalAdmissionLock) {
                if (executing.get() || pendingResume.get() == null
                        || phase.get() != WAITING) return;
                // Producers and carrier-exit callbacks may both try to
                // requeue this WAITING task. Serializing lease installation
                // with QUEUED publication prevents a losing reservation from
                // clobbering the winner's revocable queuedCarrierLease.
                lease = reserveCarrier();
                phase.set(QUEUED);
            }
            try {
                enqueueTurn(lease);
            } catch (RejectedExecutionException rejected) {
                // enqueueTurn already failed the task and released its lease.
            }
        }

        private void detachActiveAwaitRegistration() {
            OresFuture.RuntimeWaiterRegistration<?> registration =
                    activeAwaitRegistration.getAndSet(null);
            if (registration != null) registration.detach();
        }

        private void finish(T value) {
            detachActiveAwaitRegistration();
            synchronized (terminalAdmissionLock) {
                if (!phase.compareAndSet(RUNNING, TERMINAL)) return;
                terminalOutcome.set(cancelRequested.get()
                        ? new TerminalCancelled<T>(cancellation)
                        : new TerminalSuccess<>(value));
            }
            // Keep this task in the live registry until afterCarrierTurn()
            // publishes completion. Otherwise close() may dispose a shared
            // context while guest code is still leaving its carrier turn.
        }

        private void failTerminal(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            detachActiveAwaitRegistration();
            synchronized (terminalAdmissionLock) {
                int observed;
                do {
                    observed = phase.get();
                    if (observed == TERMINAL) return;
                } while (!phase.compareAndSet(observed, TERMINAL));

                // A lexical cleanup failure wins over cancellation, but the
                // original cancellation must remain inspectable. Preserve it
                // once, without self-suppression or duplicate suppressed
                // reasons from nested defer/finally propagation.
                // Cancellation is identified by this task's exact token,
                // never merely by the exception class. A foreign task may
                // throw its own cancellation during cleanup, and a shared
                // cleanup error may be thrown by multiple cancelling tasks.
                // Each owner must retain its own cancellation provenance.
                if (cancelRequested.get() && failure != cancellation) {
                    boolean alreadyPreserved = false;
                    for (Throwable prior : failure.getSuppressed()) {
                        if (prior == cancellation) {
                            alreadyPreserved = true;
                            break;
                        }
                    }
                    if (!alreadyPreserved) failure.addSuppressed(cancellation);
                }
                terminalOutcome.compareAndSet(null,
                        failure == cancellation
                                ? new TerminalCancelled<T>(cancellation)
                                : new TerminalFailure<T>(failure));
            }
            if (!executing.get()) publishTerminalIfReady();
        }

        private void publishTerminalIfReady() {
            // A terminal *phase* is not proof that the carrier has left the
            // entered guest context. The task remains registered until its
            // completion is published outside that execution lease.
            if (phase.get() != TERMINAL || executing.get()
                    || outstandingCarrierTurns.get() != 0) return;
            if (completion.isDone()) {
                removeFromTasks();
                return;
            }
            TerminalOutcome<T> outcome = terminalOutcome.get();
            if (outcome == null) return;
            try {
                if (outcome instanceof TerminalSuccess<?> success) {
                    @SuppressWarnings("unchecked")
                    T value = (T) success.value();
                    completion.completeFromRuntime(value);
                } else if (outcome instanceof TerminalFailure<?> failure) {
                    completion.failFromRuntime(failure.failure());
                } else if (outcome instanceof TerminalCancelled<?> cancelled) {
                    completion.cancelFromRuntime(cancelled.reason());
                }
            } finally {
                // Never tell close() the task drained until the terminal
                // callback has settled (including synchronous observers).
                if (completion.isDone()) removeFromTasks();
            }
        }

        private void failBeforeStart(Throwable failure) {
            failTerminal(failure);
        }

        private void removeFromTasks() {
            synchronized (tasks) {
                tasks.remove(this);
                tasks.notifyAll();
            }
        }

        private void cancelFromSchedulerClose() {
            if (admitCancellation()) onCancellationRequested();
        }
    }

    private sealed interface TerminalOutcome<T>
            permits TerminalSuccess, TerminalFailure, TerminalCancelled { }

    private record TerminalSuccess<T>(T value) implements TerminalOutcome<T> { }

    private record TerminalCancelled<T>(
            TaskCancelledException reason) implements TerminalOutcome<T> { }

    private record TerminalFailure<T>(Throwable failure)
            implements TerminalOutcome<T> {
        private TerminalFailure {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
