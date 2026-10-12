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
        private final AtomicBoolean released = new AtomicBoolean();
        private HostHandoffLease(RootHandoffGate gate) { this.gate = gate; }
        @Override public void close() {
            if (released.compareAndSet(false, true)) gate.release();
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
        private boolean draining;

        private RootHandoffGate(Executor downstream) {
            this.downstream = Objects.requireNonNull(downstream, "downstream");
        }

        synchronized HostHandoffLease begin() {
            hostScopes++;
            return new HostHandoffLease(this);
        }

        synchronized int heldCount() { return held.size(); }
        synchronized boolean active() { return hostScopes > 0; }

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
            downstream.execute(task);
        }

        private void release() {
            synchronized (this) {
                if (hostScopes <= 0) throw new IllegalStateException(
                        "host Future handoff has no active admission lease");
                if (--hostScopes != 0 || draining) return;
                draining = true;
            }
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
                try {
                    downstream.execute(next);
                } catch (RuntimeException | Error failure) {
                    synchronized (this) {
                        // Preserve scheduler ownership on rejection; do NOT
                        // convert a failed submission into a physical DONE.
                        held.addFirst(next);
                        draining = false;
                    }
                    throw failure;
                }
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
