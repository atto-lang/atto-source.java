package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Context-owned host I/O. Never invokes guest code or parks an actor carrier.
 * The underlying filesystem/socket calls may block a host worker. Admission is
 * bounded independently of virtual thread implementation and has no caller-runs path.
 */
public final class AsyncNativeIo implements AutoCloseable {
    private final ExecutorService workers;
    private final Set<Operation<?>> admittedWork = new HashSet<>();
    private final Set<AutoCloseable> closingResources = new HashSet<>();
    private final OresFuture<Void> physicalShutdown = new OresFuture<>(() -> false, () -> { });
    private boolean closeScanPending;
    private boolean resourceCloseFinished;
    private boolean physicalShutdownPublished;
    private Throwable resourceCloseFailure;
    private final Set<OresFuture<?>> pending = new HashSet<>();
    private final Set<OresFuture<?>> controls = new HashSet<>();
    private final Set<AutoCloseable> resources = new HashSet<>();
    private final int capacity;
    private boolean closed;

    public AsyncNativeIo() { this(128); }
    AsyncNativeIo(int capacity) {
        this(capacity, Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ores-native-io-", 0).factory()));
    }

    /** Test-only deterministic worker scheduler for never-started shutdown races. */
    AsyncNativeIo(int capacity, ExecutorService workers) {
        if (capacity < 1) throw new IllegalArgumentException("I/O capacity must be positive");
        this.capacity = capacity;
        this.workers = java.util.Objects.requireNonNull(workers, "workers");
    }

    /** Host-only quiescence receipt: success means callbacks AND closes finished. */
    OresFuture<Void> physicalShutdown() { return physicalShutdown; }

    /** Retains failed close resources for supervisor quarantine diagnostics. */
    synchronized Throwable physicalShutdownFailure() { return resourceCloseFailure; }

    private enum Phase { ADMITTED, RUNNING, RETIRING, RETIRED, CANCELLED_BEFORE_START }

    private final class Operation<T> implements Runnable {
        private final String operation;
        private final Callable<T> work;
        private final OresFuture<T> result;
        private final Set<OresFuture<?>> admissionSet;
        private final long queued;
        private Phase phase = Phase.ADMITTED; // guarded by AsyncNativeIo.this

        Operation(String operation, Callable<T> work, OresFuture<T> result,
                Set<OresFuture<?>> admissionSet, long queued) {
            this.operation = operation;
            this.work = work;
            this.result = result;
            this.admissionSet = admissionSet;
            this.queued = queued;
        }

        /** Called ONLY on a runnable returned by shutdownNow: not started. */
        void cancelBeforeStart() {
            synchronized (AsyncNativeIo.this) {
                if (phase != Phase.ADMITTED) return;
                phase = Phase.RETIRING;
            }
            settle(new RejectedExecutionException(
                    "native I/O cancelled before worker admission"), null, true);
        }

        @Override public void run() {
            boolean accepted;
            synchronized (AsyncNativeIo.this) {
                accepted = phase == Phase.ADMITTED && !closed;
                if (phase != Phase.ADMITTED) return;
                phase = accepted ? Phase.RUNNING : Phase.RETIRING;
            }
            if (!accepted) {
                settle(new RejectedExecutionException(
                        "native I/O worker not started before context close"), null, true);
                return;
            }
            CorePerf.end(CorePerf.ASYNC_IO_QUEUE, queued);
            long workPerf = CorePerf.start();
            T value = null;
            Throwable failure = null;
            try {
                value = work.call();
            } catch (Throwable thrown) {
                failure = thrown;
            } finally {
                CorePerf.end(CorePerf.ASYNC_IO_WORK, workPerf);
            }
            synchronized (AsyncNativeIo.this) { phase = Phase.RETIRING; }
            settle(failure, value, false);
        }

        private void settle(Throwable failure, T value, boolean neverStarted) {
            try {
                // The worker has either provably never entered Callable, or
                // finished its host I/O and perf finalizer. Keep this Operation
                // tracked until inline Future terminal subscribers finish.
                final boolean shutdown;
                synchronized (AsyncNativeIo.this) { shutdown = closed; }
                if (failure != null) {
                    if (!neverStarted) {
                        CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, 1L);
                        SourceBoundaryTrace.record(failure, "native:io", operation, "I/O");
                    }
                    result.failFromRuntime(failure);
                } else if (shutdown) {
                    result.failFromRuntime(new IllegalStateException(
                            "I/O context closed after physical operation exit"));
                } else {
                    result.completeFromRuntime(value);
                }
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                if (failure instanceof LinkageError fatal) throw fatal;
            } catch (RuntimeException | Error notificationFailure) {
                // Future terminal notifications run synchronous host listeners.
                // A failed listener is not a completed physical drain receipt.
                recordPhysicalShutdownFailure(notificationFailure);
                throw notificationFailure;
            } finally {
                synchronized (AsyncNativeIo.this) {
                    phase = neverStarted ? Phase.CANCELLED_BEFORE_START : Phase.RETIRED;
                    admissionSet.remove(result);
                    admittedWork.remove(this);
                }
                maybePublishPhysicalShutdown();
            }
        }
    }

    private synchronized void recordPhysicalShutdownFailure(Throwable failure) {
        if (resourceCloseFailure == null) resourceCloseFailure = failure;
        else if (resourceCloseFailure != failure) resourceCloseFailure.addSuppressed(failure);
    }

    /** Never publish success while unstarted/running work or close is unresolved. */
    private void maybePublishPhysicalShutdown() {
        boolean publish;
        synchronized (this) {
            publish = closed && !closeScanPending && resourceCloseFinished
                    && resourceCloseFailure == null && admittedWork.isEmpty()
                    && !physicalShutdownPublished;
            if (publish) {
                physicalShutdownPublished = true;
                closingResources.clear();
            }
        }
        if (publish) physicalShutdown.completeFromRuntime(null);
    }

    public <T> OresFuture<T> submit(OresContext context,
            RuntimePermissions.Permission permission, String operation, Callable<T> work) {
        // Actor-local authority must be checked before leaving its execution turn.
        context.requireCapability(permission.capability(), operation);
        return submit(operation, work);
    }

    public <T> OresFuture<T> submitClose(OresContext context, Callable<T> work) {
        context.requireCapability(IsolatePolicy.Capability.NETWORK, "socket.close_async");
        return submitControl("socket.close_async", work);
    }

    synchronized <T> OresFuture<T> submitControl(String operation, Callable<T> work) {
        return submit(operation, work, controls, 16);
    }

    synchronized <T> OresFuture<T> submit(String operation, Callable<T> work) {
        return submit(operation, work, pending, capacity);
    }

    private <T> OresFuture<T> submit(String operation, Callable<T> work,
            Set<OresFuture<?>> admitted, int limit) {
        OresFuture<T> result = new OresFuture<>(() -> false, () -> { });
        if (closed || admitted.size() >= limit) {
            CoreLog.record(CoreLog.Level.WARN, CoreLog.ASYNC_IO_REJECTED, 1L);
            result.failFromRuntime(new RejectedExecutionException(
                    closed ? "I/O context closed" : "I/O admission capacity exhausted"));
            return result;
        }
        admitted.add(result);
        Operation<T> operationTask = new Operation<>(
                operation, work, result, admitted, CorePerf.start());
        admittedWork.add(operationTask);
        try {
            workers.execute(operationTask);
        } catch (RuntimeException failure) {
            // execute() returned an explicit rejection before accepting the
            // runnable; it cannot have issued native I/O on this path.
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_REJECTED, 1L);
            operationTask.cancelBeforeStart();
        }
        return result;
    }

    synchronized void register(AutoCloseable resource) throws Exception {
        if (closed) {
            resource.close();
            throw new IllegalStateException("I/O context closed");
        }
        resources.add(resource);
    }

    synchronized void unregister(AutoCloseable resource) { resources.remove(resource); }

    @Override public void close() {
        List<Runnable> discarded = List.of();
        Set<AutoCloseable> closing;
        synchronized (this) {
            if (closed) return;
            closed = true;
            closeScanPending = true;
            // Retain strong ownership through BOTH worker retirement and
            // physical resource close. The close request may unblock a read,
            // but it is not evidence that its callback has exited.
            closingResources.addAll(resources);
            closing = new HashSet<>(resources);
            resources.clear();
        }
        Throwable fatal = null;
        try {
            discarded = workers.shutdownNow();
        } catch (RuntimeException | Error failure) {
            recordPhysicalShutdownFailure(failure);
            if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
                fatal = failure;
            }
        }
        // Initiate close BEFORE notifying pre-start Futures: a synchronous
        // host listener can reenter/park. Even one broken close must not
        // prevent attempts for every other resource.
        for (AutoCloseable resource : closing) {
            try {
                resource.close();
            } catch (Throwable failure) {
                recordPhysicalShutdownFailure(failure);
                if ((failure instanceof VirtualMachineError || failure instanceof ThreadDeath)
                        && fatal == null) fatal = failure;
            }
        }
        for (Runnable task : discarded) {
            if (task instanceof AsyncNativeIo.Operation<?> admitted) {
                try {
                    admitted.cancelBeforeStart();
                } catch (RuntimeException | Error failure) {
                    recordPhysicalShutdownFailure(failure);
                    if ((failure instanceof VirtualMachineError || failure instanceof ThreadDeath)
                            && fatal == null) fatal = failure;
                }
            }
        }
        synchronized (this) {
            resourceCloseFinished = true;
            closeScanPending = false;
        }
                maybePublishPhysicalShutdown();
        if (fatal instanceof Error error) throw error;
    }
}
