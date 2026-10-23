package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Context-owned host I/O, never executed on an actor or CONTROL carrier.
 *
 * Every admitted operation holds a physical context loan until its worker has
 * left work.call() AND all terminal Future callbacks have drained. A terminal
 * Future is not the proof of physical retirement. close() interrupts workers,
 * explicitly fails queued work that never started, then joins physical worker
 * exit BEFORE closing registered resources. If joining cannot be proven, the
 * context and resources remain quarantined (strongly reachable), not reclaimed.
 */
public final class AsyncNativeIo implements AutoCloseable {
    private static final long CLOSE_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final int CONTROL_CAPACITY = 16;

    private enum OperationState {
        ADMITTED, RUNNING, RETIRING, RETIRED, CANCELLED_BEFORE_START
    }

    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor controlWorkers;
    private final Set<Operation<?>> pending = new HashSet<>();
    private final Set<Operation<?>> controls = new HashSet<>();
    private final Set<AutoCloseable> resources = new HashSet<>();
    /** A resource unregistered while workers have loans stays reachable. */
    private final Set<AutoCloseable> deferredUnregister = new HashSet<>();
    private final Object shutdownLock = new Object();
    private final ThreadLocal<Boolean> executingWorker =
            ThreadLocal.withInitial(() -> false);
    private final int capacity;
    private volatile boolean closed;
    private boolean resourcesRetired;
    private Throwable incompleteReceipt;

    public AsyncNativeIo() { this(128); }

    AsyncNativeIo(int capacity) {
        this(capacity, Math.min(capacity, 32), capacity);
    }

    /** Bounded deterministic worker and queue configuration for test coverage. */
    AsyncNativeIo(int capacity, int workerCount, int queueSize) {
        if (capacity < 1 || workerCount < 1 || queueSize < 1
                || workerCount > capacity) {
            throw new IllegalArgumentException("invalid native I/O worker capacity");
        }
        this.capacity = capacity;
        workers = new ThreadPoolExecutor(workerCount, workerCount, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueSize),
                Thread.ofVirtual().name("ores-native-io-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        controlWorkers = new ThreadPoolExecutor(CONTROL_CAPACITY, CONTROL_CAPACITY, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(CONTROL_CAPACITY),
                Thread.ofVirtual().name("ores-native-io-control-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> OresFuture<T> submit(OresContext context,
            RuntimePermissions.Permission permission, String operation, Callable<T> work) {
        context.requireCapability(permission.capability(), operation);
        return submit(operation, work);
    }

    public <T> OresFuture<T> submitClose(OresContext context, Callable<T> work) {
        context.requireCapability(IsolatePolicy.Capability.NETWORK, "socket.close_async");
        return submitControl("socket.close_async", work);
    }

    synchronized <T> OresFuture<T> submitControl(String operation, Callable<T> work) {
        return admit(operation, work, controls, CONTROL_CAPACITY, controlWorkers);
    }

    synchronized <T> OresFuture<T> submit(String operation, Callable<T> work) {
        return admit(operation, work, pending, capacity, workers);
    }

    private <T> OresFuture<T> admit(String operation, Callable<T> work,
            Set<Operation<?>> admitted, int limit, ThreadPoolExecutor executor) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(work);
        OresFuture<T> result = new OresFuture<>(() -> false, () -> { });
        if (closed || admitted.size() >= limit) {
            CoreLog.record(CoreLog.Level.WARN, CoreLog.ASYNC_IO_REJECTED, 1L);
            result.failFromRuntime(new RejectedExecutionException(
                    closed ? "I/O context closed" : "I/O admission capacity exhausted"));
            return result;
        }

        Operation<T> op = new Operation<>(operation, work, result, admitted);
        admitted.add(op); // Reserve before publishing work to any executor.
        long queuedAt = CorePerf.start();
        op.queuedAt = queuedAt;
        try {
            executor.execute(op);
        } catch (RuntimeException failure) {
            // Rejected admission never owns a worker or physical resource.
            admitted.remove(op);
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_REJECTED, 1L);
            result.failFromRuntime(failure);
        }
        return result;
    }

    private final class Operation<T> implements Runnable {
        private final String operation;
        private final Callable<T> work;
        private final OresFuture<T> completion;
        private final Set<Operation<?>> owner;
        private final AtomicReference<OperationState> state =
                new AtomicReference<>(OperationState.ADMITTED);
        private long queuedAt;

        private Operation(String operation, Callable<T> work, OresFuture<T> completion,
                Set<Operation<?>> owner) {
            this.operation = operation;
            this.work = work;
            this.completion = completion;
            this.owner = owner;
        }

        @Override public void run() {
            if (!state.compareAndSet(OperationState.ADMITTED, OperationState.RUNNING)) return;
            executingWorker.set(true);
            CorePerf.end(CorePerf.ASYNC_IO_QUEUE, queuedAt);
            long workPerf = CorePerf.start();
            T value = null;
            Throwable failure = null;
            try {
                value = work.call();
            } catch (Throwable caught) {
                failure = caught;
                CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, 1L);
                SourceBoundaryTrace.record(caught, "native:io", operation, "I/O");
            } finally {
                CorePerf.end(CorePerf.ASYNC_IO_WORK, workPerf);
            }

            state.set(OperationState.RETIRING);
            try {
                // close() must not settle a RUNNING Future while its native
                // operation still holds context resources. Settlement here
                // occurs after work.call(), on the physical owning worker.
                if (closed) completion.failFromRuntime(
                        new IllegalStateException("I/O context closed"));
                else if (failure != null) completion.failFromRuntime(failure);
                else completion.completeFromRuntime(value);
            } catch (RuntimeException | Error notificationFailure) {
                recordIncomplete(notificationFailure);
                throw notificationFailure;
            } finally {
                state.set(OperationState.RETIRED);
                synchronized (AsyncNativeIo.this) {
                    owner.remove(this);
                    retireDeferredUnregisterLocked();
                    AsyncNativeIo.this.notifyAll();
                }
                executingWorker.remove();
            }
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            if (failure instanceof LinkageError fatal) throw fatal;
        }

        private void cancelledBeforeStart(Throwable failure) {
            if (!state.compareAndSet(OperationState.ADMITTED,
                    OperationState.CANCELLED_BEFORE_START)) return;
            // A Future terminal subscriber may block indefinitely. Deliver
            // never-started cancellation from a detached host notification
            // carrier, never on the close() caller or CONTROL thread. At most
            // capacity + CONTROL_CAPACITY such notifications can be admitted.
            try {
                Thread.ofVirtual().name("ores-native-io-discard-").start(() -> {
                    try {
                        completion.failFromRuntime(failure);
                    } catch (RuntimeException | Error notificationFailure) {
                        recordIncomplete(notificationFailure);
                    } finally {
                        synchronized (AsyncNativeIo.this) {
                            owner.remove(this);
                            retireDeferredUnregisterLocked();
                            AsyncNativeIo.this.notifyAll();
                        }
                    }
                });
            } catch (RuntimeException | Error startFailure) {
                // No notification occurred. Preserve this operation in owner
                // and quarantine the context rather than forging retirement.
                recordIncomplete(startFailure);
            }
        }
    }

    private synchronized void recordIncomplete(Throwable failure) {
        if (incompleteReceipt == null) incompleteReceipt = failure;
        else if (incompleteReceipt != failure) incompleteReceipt.addSuppressed(failure);
    }

    private void retireDeferredUnregisterLocked() {
        // Existing socket close/unregister may race another active read.
        // Keep the resource strongly reachable until every context loan ends.
        if (pending.isEmpty() && controls.isEmpty()) deferredUnregister.clear();
    }

    synchronized void register(AutoCloseable resource) throws Exception {
        Objects.requireNonNull(resource);
        if (closed) {
            // A RUNNING connect may create a resource after close() sealed
            // admission. Closing it on this worker/monitor would release a
            // native handle before all context loans have exited. Retain it
            // for the post-join resource sweep and reject publication.
            resources.add(resource);
            throw new IllegalStateException("I/O context closed");
        }
        resources.add(resource);
    }

    synchronized void unregister(AutoCloseable resource) {
        if (resources.remove(resource) && (!pending.isEmpty() || !controls.isEmpty())) {
            deferredUnregister.add(resource);
        }
    }

    private void reportDiscarded(List<Runnable> discarded) {
        Throwable callbackFailure = null;
        for (Runnable runnable : discarded) {
            try {
                if (!(runnable instanceof AsyncNativeIo.Operation<?> op)) {
                    throw new IllegalStateException("untracked native I/O queue entry discarded");
                }
                op.cancelledBeforeStart(new RejectedExecutionException(
                        "I/O worker cancelled before start by context close"));
            } catch (RuntimeException | Error failure) {
                if (callbackFailure == null) callbackFailure = failure;
                else if (callbackFailure != failure) callbackFailure.addSuppressed(failure);
            }
        }
        if (callbackFailure != null) recordIncomplete(callbackFailure);
    }

    @Override public void close() {
        if (Boolean.TRUE.equals(executingWorker.get())) {
            throw new IllegalStateException(
                    "native I/O worker cannot join its own physical retirement");
        }
        synchronized (shutdownLock) {
            final boolean first;
            synchronized (this) {
                first = !closed;
                closed = true; // Seal both admission lanes before any interruption.
            }
            if (first) {
                reportDiscarded(workers.shutdownNow());
                reportDiscarded(controlWorkers.shutdownNow());
            }

            long deadline = System.nanoTime() + CLOSE_WAIT_NANOS;
            boolean interrupted = false;
            boolean joined = false;
            try {
                long wait = Math.max(0L, deadline - System.nanoTime());
                boolean dataDone = workers.awaitTermination(wait, TimeUnit.NANOSECONDS);
                wait = Math.max(0L, deadline - System.nanoTime());
                boolean controlDone = controlWorkers.awaitTermination(wait, TimeUnit.NANOSECONDS);
                if (dataDone && controlDone) {
                    synchronized (this) {
                        // shutdownNow() may have produced bounded independent
                        // never-started notification tasks. Those also own
                        // physical callback receipts until their last terminal
                        // subscriber has returned. Wait only to the deadline.
                        while ((!pending.isEmpty() || !controls.isEmpty())
                                && incompleteReceipt == null) {
                            wait = deadline - System.nanoTime();
                            if (wait <= 0) break;
                            TimeUnit.NANOSECONDS.timedWait(this, wait);
                        }
                        joined = pending.isEmpty() && controls.isEmpty()
                                && incompleteReceipt == null;
                    }
                }
            } catch (InterruptedException failure) {
                interrupted = true;
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (!joined) {
                // NEVER clear resource ownership, settle running Futures, or
                // publish successful retirement when a physical worker is live.
                throw new IllegalStateException(
                        "native I/O context physical cleanup incomplete; resources quarantined",
                        incompleteReceipt);
            }

            List<AutoCloseable> closing;
            synchronized (this) {
                if (resourcesRetired) return;
                closing = new ArrayList<>(resources);
            }
            Throwable closeFailure = null;
            for (AutoCloseable resource : closing) {
                try {
                    resource.close();
                    synchronized (this) { resources.remove(resource); }
                } catch (Exception failure) {
                    if (closeFailure == null) closeFailure = failure;
                    else if (closeFailure != failure) closeFailure.addSuppressed(failure);
                }
            }
            synchronized (this) {
                if (closeFailure == null && resources.isEmpty()) resourcesRetired = true;
            }
            if (closeFailure != null) {
                throw new IllegalStateException(
                        "native I/O resource close failed; context quarantined",
                        closeFailure);
            }
        }
    }
}
