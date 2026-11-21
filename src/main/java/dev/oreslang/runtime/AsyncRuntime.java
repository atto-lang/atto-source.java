package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Context-owned scheduler for ordinary Oreslang async callables.
 *
 * <p>The language contract is deliberately task/future based rather than
 * thread based: callers receive an Oreslang-owned {@link OresFuture}; the backing carrier
 * is an implementation detail. The compatibility execution bridge uses virtual threads so
 * an async callable never consumes an actor dispatcher worker while it is
 * blocked in host/runtime code. A future compiler can replace this with
 * continuation/state-machine lowering without changing source semantics.</p>
 */
public final class AsyncRuntime implements AutoCloseable {
    private static final long CLOSE_WAIT_MILLIS = 500L;
    private static final ThreadLocal<Boolean> ASYNC_CARRIER =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @FunctionalInterface
    public interface TurnExecutor {
        void execute(Runnable turn);

        static TurnExecutor direct() {
            return Runnable::run;
        }
    }

    @FunctionalInterface
    public interface Task<T> {
        T run() throws Exception;
    }

    private final TurnExecutor turnExecutor;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<OresFuture<?>, TaskExit> taskExits = new ConcurrentHashMap<>();

    private static final class TaskExit {
        // 0: not entered, 1: running, 2: guest turn exited/cancelled before entry.
        private final AtomicInteger state = new AtomicInteger();
        private final CountDownLatch exited = new CountDownLatch(1);
        private volatile Thread thread;
        private volatile OresFuture<?> completion;
        private final OresFuture<Void> quiesced = new OresFuture<>();
        // Set by a structured owner BEFORE submitting its carrier.
        private Runnable retirement = () -> { };

        private void markExited() {
            try {
                // Physical retirement is part of the completion contract:
                // failure here must never be masked by Future waiter isolation.
                retirement.run();
                quiesced.completeFromRuntime(null);
            } catch (RuntimeException | Error retirementFailure) {
                quiesced.failFromRuntime(retirementFailure);
            } finally {
                exited.countDown();
            }
        }
    }


    public AsyncRuntime() {
        this(TurnExecutor.direct());
    }

    public AsyncRuntime(TurnExecutor turnExecutor) {
        this(turnExecutor, newVirtualThreadExecutor());
    }

    private static ExecutorService newVirtualThreadExecutor() {
        ThreadFactory factory = Thread.ofVirtual().name("ores-async-", 0L).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }

    /**
     * Package-private adversarial executor seam. Tests can delay, reject or
     * fail admission deterministically without changing guest semantics.
     */
    AsyncRuntime(TurnExecutor turnExecutor, ExecutorService executor) {
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * True only while a host-owned async carrier is entering/executing guest
     * code. Guest source cannot toggle this marker or create these carriers.
     */
    public static boolean isAsyncCarrierThread() {
        return Boolean.TRUE.equals(ASYNC_CARRIER.get());
    }

    /**
     * Cooperatively return the current runtime-owned async/generator virtual
     * thread to its scheduler without changing the source-level return type.
     *
     * <p>Ordinary source tasks lower `rt cooperate` to OresScheduler.Cooperate.
     * Generator activations use this bridge because their compatibility
     * lowering is currently a runtime-owned virtual-thread producer.</p>
     */
    public static void cooperateCurrentCarrier() {
        if (!isAsyncCarrierThread()) {
            throw new IllegalStateException(
                    "rt cooperate requires a runtime-owned async/generator carrier");
        }
        Thread.yield();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Schedule one async callable and return immediately with a composable
     * future. Cancellation is propagated to the backing task and therefore
     * interrupts the current virtual carrier when possible.
     */
    public <T> OresFuture<T> submit(Task<T> task) {
        return submitInternal(task, new TaskExit());
    }

    /**
     * Task-handle form used by canonical structured ownership. The Task shell
     * and physical-exit witness are allocated before executor admission; after
     * admission only reference assignment remains, so allocation failure
     * cannot orphan executable work outside its Task handle.
     */
    public <T> OresTask<T> submitTask(Task<T> task) {
        Objects.requireNonNull(task, "task");
        TaskAdmission<T> admitted = prepareTaskAdmission();
        submitPreparedTask(admitted, task);
        return admitted.owner;
    }

    /**
     * Compiler/runtime-only physical ownership reservation. A scope owns the
     * Task shell and quiescence fence before dispatch, so cancellation and
     * close can join a producer even if executor admission blocks.
     */
    static final class TaskAdmission<T> {
        private final AsyncRuntime runtime;
        private final TaskExit exit = new TaskExit();
        final OresTask<T> owner = OresTask.provisional(exit.quiesced);
        private boolean started;
        private boolean retirementInstalled;

        private TaskAdmission(AsyncRuntime runtime) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
        }

        // Installation and admission claim share one monitor. No caller may
        // replace an owner-release hook after dispatch starts or install it
        // twice. The executor and guest callbacks run outside this monitor.
        synchronized void installRetirement(Runnable hook) {
            Objects.requireNonNull(hook, "hook");
            if (started || retirementInstalled)
                throw new IllegalStateException(
                        "Task retirement must be installed once before dispatch");
            exit.retirement = hook;
            retirementInstalled = true;
        }

        private synchronized boolean claimStart(AsyncRuntime requester) {
            if (requester != runtime)
                throw new IllegalArgumentException(
                        "Task admission belongs to a different AsyncRuntime");
            if (started) return false;
            started = true;
            return true;
        }
    }

    <T> TaskAdmission<T> prepareTaskAdmission() {
        return new TaskAdmission<>(this);
    }

    /**
     * Failed scope reservation may have inserted an ownership slot before a
     * hostile collection threw. Retire the unstarted owner exactly once so
     * close() never waits forever on a provisional physical-exit fence.
     */
    void abandonPreparedTask(TaskAdmission<?> admission) {
        Objects.requireNonNull(admission, "admission");
        if (!admission.claimStart(this))
            throw new IllegalStateException("Task admission already attempted");
        if (admission.exit.state.compareAndSet(0, 2))
            admission.exit.markExited();
    }

    <T> void submitPreparedTask(TaskAdmission<T> admission, Task<T> task) {
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(task, "task");
        if (!admission.claimStart(this))
            throw new IllegalStateException("Task admission already attempted");
        try {
            OresFuture<T> completion = submitInternal(task, admission.exit);
            // If the owner was cancelled while the host executor was
            // admitting it, binding replays cancellation without scope locks.
            admission.owner.attachCompletion(completion);
        } catch (RuntimeException | Error failure) {
            // A pre-dispatch failure has no carrier to retire the fence.
            // Never claim physical exit when a carrier already entered.
            if (admission.exit.state.compareAndSet(0, 2)) {
                OresFuture<?> registered = admission.exit.completion;
                admission.exit.markExited();
                if (registered != null) taskExits.remove(registered, admission.exit);
            }
            throw failure;
        }
    }

    /** Supervised discarded-handle form; failure is never silently ignored. */
    public void submitDetached(
            Task<?> task, java.util.function.Consumer<? super Throwable> onFailure) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(onFailure, "onFailure");
        OresFuture<?> future = submit(task);
        future.whenCompleteRuntime((value, failure) -> {
            if (failure != null) onFailure.accept(failure);
        });
    }

    private <T> OresFuture<T> submitInternal(Task<T> task, TaskExit exit) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(exit, "exit");
        if (closed.get()) throw new RejectedExecutionException("async runtime is closed");

        AtomicReference<Future<?>> backing = new AtomicReference<>();
        OresFuture<T> completion = new OresFuture<>(() -> {
            Future<?> carrier = backing.getAndSet(null);
            if (carrier != null) carrier.cancel(true);
            if (exit.state.compareAndSet(0, 2)) {
                exit.markExited();
                taskExits.remove(exit.completion, exit);
            }
        });
        exit.completion = completion;
        // Map growth happens before executor admission. If it fails, no guest
        // frame has been submitted and no physical-exit ownership is stranded.
        taskExits.put(completion, exit);

        final Future<?> scheduled;
        try {
            scheduled = executor.submit(() -> {
                if (!exit.state.compareAndSet(0, 1)) {
                    // A cancelling host won pre-entry retirement. It owns
                    // map removal only AFTER the physical fence settles.
                    return;
                }
                exit.thread = Thread.currentThread();
                try {
                    runTask(task, completion);
                } finally {
                    // TurnExecutor has returned, including its context leave.
                    backing.set(null);
                    exit.state.set(2);
                    exit.markExited();
                    taskExits.remove(completion, exit);
                }
            });
        } catch (RuntimeException | Error admissionFailure) {
            // A rejection, executor-factory failure or allocation fault
            // happens before a scheduled carrier is returned. A concurrent
            // runtime close may already have revoked the registered Future.
            // Only the winning 0 -> 2 transition may retire its owner;
            // keep the registration until that retirement has finished.
            if (exit.state.compareAndSet(0, 2)) {
                exit.markExited();
                taskExits.remove(completion, exit);
            }
            if (admissionFailure instanceof RejectedExecutionException rejected)
                throw new RejectedExecutionException("async runtime is closed", rejected);
            throw admissionFailure;
        }
        backing.set(scheduled);
        if (completion.isCancelled()) {
            scheduled.cancel(true);
            if (exit.state.compareAndSet(0, 2)) {
                exit.markExited();
                taskExits.remove(completion, exit);
            }
        }

        // Close may race submission after the initial check.
        if (closed.get()) completion.cancel(true);
        return completion;
    }

    /** Cancellation settles a Future before its carrier leaves the guest turn. */
    void awaitTaskExit(OresFuture<?> task) {
        TaskExit exit = taskExits.get(task);
        if (exit == null || exit.thread == Thread.currentThread()) return;
        boolean interrupted = false;
        for (;;) {
            try { exit.exited.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private <T> void runTask(Task<T> task, OresFuture<T> completion) {
        if (completion.isCancelled()) return;

        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ASYNC_CARRIER.set(Boolean.TRUE);
        try {
            turnExecutor.execute(() -> {
                if (completion.isCancelled()) return;
                try {
                    value.set(task.run());
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
        } catch (Throwable thrown) {
            failure.compareAndSet(null, thrown);
        } finally {
            ASYNC_CARRIER.remove();
        }

        if (completion.isCancelled()) return;
        Throwable thrown = failure.get();
        if (thrown == null) completion.completeFromRuntime(value.get());
        else completion.failFromRuntime(thrown);
    }

    /**
     * Blocking bridge used only where the interpreter has not yet lowered an
     * await to a continuation. It preserves interruption/cancellation and
     * unwraps the original failure instead of leaking CompletionException.
     *
     * <p>An actor carrier is never allowed to park here. Actor await must be
     * lowered to mailbox-turn suspension/resumption.</p>
     */
    public static <T> T await(OresFuture<T> future) {
        Objects.requireNonNull(future, "future");
        if (ActorRuntime.inActorExecution() && !future.isDone()) {
            throw new IllegalStateException(
                    "await would block an actor dispatcher carrier; actor continuation lowering must suspend/resume the mailbox turn");
        }
        return blockingGet(future);
    }

    /**
     * Host-interop bridge only. CompletionStage is normalized immediately into
     * OresFuture so host callback scheduling never becomes Oreslang semantics.
     */
    public static <T> T await(CompletionStage<T> stage) {
        return await(OresFuture.from(Objects.requireNonNull(stage, "stage")));
    }

    private static <T> T blockingGet(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("await interrupted");
            cancelled.initCause(interrupted);
            throw cancelled;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        }
    }

    @Override
    public void close() {
        if (isAsyncCarrierThread() || ActorRuntime.inActorExecution()
                || OresScheduler.isSchedulerCarrierThread()
                || OresScheduler.current() != null) {
            throw new IllegalStateException(
                    "AsyncRuntime.close cannot block an actor, Task, or scheduler turn");
        }
        // The first caller owns shutdown initiation, but EVERY caller owns an
        // independent physical-quiescence join. Merely observing closed=true
        // must never permit VM teardown while carriers are still executing.
        Throwable notificationFailure = null;
        if (closed.compareAndSet(false, true)) {
            java.util.ArrayList<Runnable> notifications = new java.util.ArrayList<>();
            // Revoke sibling Task producers before delivering potentially
            // blocking host observers. A racing submit that registered after
            // this snapshot independently observes closed and cancels itself.
            for (OresFuture<?> completion : taskExits.keySet()) {
                Runnable delivery = completion.cancelDeferredNotification();
                if (delivery != null) notifications.add(delivery);
            }
            executor.shutdownNow();
            for (Runnable delivery : notifications) {
                // Failed host accounting must not suppress sibling delivery
                // or prevent the physical termination join.
                try {
                    delivery.run();
                } catch (RuntimeException | Error failure) {
                    if (notificationFailure == null) notificationFailure = failure;
                    else if (notificationFailure != failure)
                        notificationFailure.addSuppressed(failure);
                }
            }
        }

        boolean interrupted = false;
        final long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MILLIS);
        try {
            for (;;) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L && !executor.isTerminated()) {
                    throw new IllegalStateException(
                            "async runtime did not terminate all tasks after cancellation");
                }
                try {
                    if (executor.awaitTermination(Math.max(0L, remaining),
                            TimeUnit.NANOSECONDS)) break;
                } catch (InterruptedException ignored) {
                    // An interrupt does not prove physical cleanup. Continue
                    // joining up to the configured deadline and restore the
                    // caller's interrupt once the barrier has settled.
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (notificationFailure instanceof RuntimeException failure) throw failure;
        if (notificationFailure instanceof Error error) throw error;
    }

}
