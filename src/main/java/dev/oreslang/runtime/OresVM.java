package dev.oreslang.runtime;

import java.util.Objects;
import java.util.ArrayDeque;
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

    private enum StartupState { NEW, STARTING, STARTED, FAILED, CLOSED }

    private final ThreadPoolExecutor controlExecutor;
    private final OresScheduler rootScheduler;
    private final RootHandoffGate rootHandoffGate;
    private final AtomicBoolean closed = new AtomicBoolean();
    // Admission may be sealed while source cleanup still owns active carriers.
    // Only a successful root drain and physical CONTROL termination retires the VM.
    private final AtomicBoolean retired = new AtomicBoolean();
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

        this.rootHandoffGate = new RootHandoffGate(
                rootAdmission == null ? controlExecutor : rootAdmission);
        this.rootScheduler = OresScheduler.runtimeOwned(
                "ores-root-scheduler",
                rootAdmission == null ? controlParallelism : 1,
                rootHandoffGate,
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


    /** Host handoff lease released only after the outer Value.execute returns. */
    public static final class HostHandoffLease implements AutoCloseable {
        private final RootHandoffGate gate;
        // The host thread that opened the physical admission scope must also
        // release it. A foreign close could otherwise dispatch queued root
        // work before the original host Value.execute had physically exited.
        private final Thread ownerThread = Thread.currentThread();
        private final AtomicBoolean released = new AtomicBoolean();
        private HostHandoffLease(RootHandoffGate gate) { this.gate = gate; }
        @Override public void close() {
            if (Thread.currentThread() != ownerThread) {
                throw new SecurityException(
                        "host handoff entry lease belongs to another thread");
            }
            if (released.compareAndSet(false, true)) {
                gate.release();
            } else {
                // A prior close may have released this host scope but failed
                // to hand queued work to a rejecting executor. Retrying must
                // never decrement hostScopes a second time.
                gate.retryHeldDrain();
            }
        }
    }

    /**
     * Bounded FIFO admission barrier. Held scheduler tasks own NO executing
     * worker and cannot call TruffleContext.enter. Nested scoped host calls
     * must all exit before the first task is delegated to CONTROL.
     */
    private static final class RootHandoffGate implements Executor {
        private final Executor downstream;
        private final ArrayDeque<Runnable> held = new ArrayDeque<>();
        private int hostScopes;
        // Root turn has claimed the entry gate and has not yet returned.
        // Host callbacks must never overlap an already claimed root turn.
        private int executingRootTurns;
        private boolean draining;
        // Close seals all new host entry BEFORE actor/CONTROL resources retire.
        // Active scopes and already-queued cleanup still drain on retry.
        private boolean sealed;

        private RootHandoffGate(Executor downstream) {
            this.downstream = Objects.requireNonNull(downstream, "downstream");
        }

        synchronized HostHandoffLease begin() {
            if (sealed) {
                throw new RejectedExecutionException(
                        "root host handoff admission is sealed");
            }
            if (executingRootTurns != 0) {
                throw new RejectedExecutionException(
                        "root guest turn already owns physical host entry");
            }
            hostScopes++;
            return new HostHandoffLease(this);
        }

        synchronized void seal() {
            sealed = true;
        }

        synchronized int heldCount() { return held.size(); }
        synchronized boolean active() {
            // This tests HOST scope ownership only. Executing root tasks
            // drain through OresScheduler.close(), where their physical
            // carrier-exit barrier is established. Treating them as host
            // scopes here rejects valid context teardown too early.
            return hostScopes > 0;
        }

        @Override public void execute(Runnable task) {
            Objects.requireNonNull(task, "task");
            synchronized (this) {
                if (hostScopes > 0 || draining || !held.isEmpty()) {
                    if (held.size() >= DEFAULT_QUEUE_CAPACITY) {
                        throw new RejectedExecutionException(
                                "host handoff root admission queue is full");
                    }
                    held.addLast(task);
                    return;
                }
            }
            submitGuarded(task, false);
        }

        /**
         * Executor acceptance is not physical guest entry. A work-stealing or
         * delayed executor may invoke this runnable after a host scope begins.
         * Claim exactly once, and requeue rather than enter the guest while
         * that host owns entry. No scheduler carrier waits for the host.
         */
        private void submitGuarded(Runnable task, boolean fromDrain) {
            AtomicBoolean started = new AtomicBoolean();
            Runnable gated = () -> {
                if (!started.compareAndSet(false, true)) return;
                synchronized (this) {
                    if (hostScopes > 0) {
                        if (held.size() >= DEFAULT_QUEUE_CAPACITY) {
                            throw new RejectedExecutionException(
                                    "late accepted root turn exceeds handoff capacity");
                        }
                        // This turn was admitted before later host-scoped turns.
                        held.addFirst(task);
                        return;
                    }
                    // Physically claim the root turn under the very same
                    // monitor used by host admission. Do not hold the
                    // monitor across guest execution or suspended cleanup.
                    executingRootTurns++;
                }
                try {
                    task.run();
                } finally {
                    synchronized (this) {
                        executingRootTurns--;
                        notifyAll();
                    }
                }
            };
            try {
                downstream.execute(gated);
            } catch (RuntimeException | Error failure) {
                // A rejecting executor may have accepted but not started
                // the runnable. CAS revokes that stale body. Conversely, an
                // inline executor can run the guest and then throw: NEVER
                // requeue a task whose physical entry already started.
                boolean revokedBeforeStart = started.compareAndSet(false, true);
                if (fromDrain) {
                    synchronized (this) {
                        if (revokedBeforeStart) held.addFirst(task);
                        // A failed drain must not leave the FIFO permanently
                        // locked. A later owner-close retry can resume it.
                        draining = false;
                    }
                }
                throw failure;
            }
        }

        private void release() {
            synchronized (this) {
                if (hostScopes <= 0) throw new IllegalStateException(
                        "host Future handoff has no active admission lease");
                if (--hostScopes != 0 || draining) return;
                draining = true;
            }
            drainHeld();
        }

        /** Retry failed downstream submission without releasing a host lease twice. */
        private void retryHeldDrain() {
            synchronized (this) {
                if (hostScopes != 0 || draining || held.isEmpty()) return;
                draining = true;
            }
            drainHeld();
        }

        private void drainHeld() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    if (hostScopes != 0) {
                        draining = false;
                        return;
                    }
                    next = held.pollFirst();
                    if (next == null) {
                        draining = false;
                        return;
                    }
                }
                // submitGuarded only retries an executor failure when
                // it revoked an actually unstarted runnable. An inline
                // executor's already-run guest body is never replayed.
                submitGuarded(next, true);
            }
        }
    }

    /** Called only after the scoped host Future ticket has been authenticated. */
    public HostHandoffLease beginHostHandoff() {
        if (!started() || closed.get()) {
            throw new RejectedExecutionException("root handoff context is unavailable");
        }
        return rootHandoffGate.begin();
    }

    void sealHostHandoffAdmission() { rootHandoffGate.seal(); }

    int queuedHostHandoffTurns() { return rootHandoffGate.heldCount(); }

    void requireHostHandoffReleased() {
        if (rootHandoffGate.active() || rootHandoffGate.heldCount() != 0) {
            throw new IllegalStateException(
                    "unfinished host Future handoff still owns root guest admission");
        }
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

    public boolean isRootSchedulerCurrent() {
        return OresScheduler.current() == rootScheduler;
    }

    @Override
    public synchronized void close() {
        if (retired.get()) return;
        // The scheduler owns cancellation cleanup. A failed first close seals
        // new admission, but it must leave its carriers and continuation queue
        // intact so outstanding finally/defer awaits can resume. Retire only
        // after physical scheduler quiescence has been observed.
        // Seal under the same gate monitor as beginHostHandoff(), so a
        // concurrent host call cannot acquire a new guest admission lease
        // after the close preflight and before scheduler retirement.
        sealHostHandoffAdmission();
        requireHostHandoffReleased();
        closed.set(true);
        startupState = StartupState.CLOSED;
        rootScheduler.close();

        // Only a fully drained scheduler may release CONTROL carriers.
        // An interrupting shutdownNow() can strand a still-owned finalizer.
        controlExecutor.shutdown();
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
        retired.set(true);
    }
}
