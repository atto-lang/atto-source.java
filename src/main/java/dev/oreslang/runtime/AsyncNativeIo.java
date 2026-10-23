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
    private final Set<StopThenRelease> deferredResources = new HashSet<>();

    /**
     * Trusted host resource whose shutdown request cannot certify physical release.
     * requestStop() may interrupt/close a transport, but MUST NOT reclaim pinned
     * buffers, FFI state, or callback-owned memory. close() is the final resource
     * release, invoked only after every context I/O operation and inline
     * completion notification has retired. External callbacks still require
     * their own generation-scoped physical receipts.
     */
    interface StopThenRelease extends AutoCloseable {
        void requestStop() throws Exception;
    }
    private final OresFuture<Void> physicalShutdown = new OresFuture<>(() -> false, () -> { });
    private boolean closeScanPending;
    private int rejectedResourceCloses; // physical close attempts outside context monitor
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
        private final ActorPhysicalRetirementRegistry.Receipt actorReceipt;
        private Phase phase = Phase.ADMITTED; // guarded by AsyncNativeIo.this

        Operation(String operation, Callable<T> work, OresFuture<T> result,
                Set<OresFuture<?>> admissionSet, long queued,
                ActorPhysicalRetirementRegistry.Receipt actorReceipt) {
            this.operation = operation;
            this.work = work;
            this.result = result;
            this.admissionSet = admissionSet;
            this.queued = queued;
            this.actorReceipt = actorReceipt;
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
            T value = null;
            Throwable failure = null;
            try {
                // Diagnostic counters are host-owned finalizers too. A
                // throw during queue/work accounting must not strand an
                // admitted operation without a terminal retirement path.
                CorePerf.end(CorePerf.ASYNC_IO_QUEUE, queued);
                long workPerf = CorePerf.start();
                try {
                    value = work.call();
                } finally {
                    CorePerf.end(CorePerf.ASYNC_IO_WORK, workPerf);
                }
            } catch (Throwable thrown) {
                failure = thrown;
            }
            synchronized (AsyncNativeIo.this) { phase = Phase.RETIRING; }
            settle(failure, value, false);
        }

        private void settle(Throwable failure, T value, boolean neverStarted) {
            Throwable notificationFailure = null;
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
            } catch (RuntimeException | Error failedNotification) {
                // Future terminal notifications run synchronous host listeners.
                // A failed listener is not a completed physical drain receipt.
                notificationFailure = failedNotification;
                recordPhysicalShutdownFailure(failedNotification);
                throw failedNotification;
            } finally {
                synchronized (AsyncNativeIo.this) {
                    phase = neverStarted ? Phase.CANCELLED_BEFORE_START : Phase.RETIRED;
                    admissionSet.remove(result);
                }
                // The actor ticket was minted on its OWN execution lease
                // before dispatch. Its physical proof is issued only after
                // Callable, perf finalizers and terminal subscribers return.
                // Keep the context operation admitted during this notifier.
                try {
                    if (actorReceipt != null) {
                        if (notificationFailure == null) {
                            actorReceipt.completeAfterPhysicalRelease();
                        } else {
                            actorReceipt.failPhysicalRelease(notificationFailure);
                        }
                    }
                } catch (RuntimeException | Error receiptFailure) {
                    recordPhysicalShutdownFailure(receiptFailure);
                    throw receiptFailure;
                } finally {
                    synchronized (AsyncNativeIo.this) {
                        admittedWork.remove(this);
                    }
                    maybePublishPhysicalShutdown();
                }
            }
        }
    }

    private synchronized void recordPhysicalShutdownFailure(Throwable failure) {
        if (resourceCloseFailure == null) resourceCloseFailure = failure;
        else if (resourceCloseFailure != failure) resourceCloseFailure.addSuppressed(failure);
    }

    /** Never publish success while unstarted/running work or close is unresolved. */
    private void maybePublishPhysicalShutdown() {
        Set<StopThenRelease> ready;
        boolean publish;
        synchronized (this) {
            boolean operationsDrained = closed && !closeScanPending
                    && resourceCloseFinished && admittedWork.isEmpty();
            // Claim the final close batch under the same lock as admission.
            // Counter is reserved BEFORE callbacks execute outside the lock.
            if (operationsDrained && !deferredResources.isEmpty()) {
                ready = new HashSet<>(deferredResources);
                deferredResources.clear();
                rejectedResourceCloses += ready.size();
            } else {
                ready = Set.of();
            }
            publish = operationsDrained && ready.isEmpty()
                    && rejectedResourceCloses == 0
                    && resourceCloseFailure == null
                    && !physicalShutdownPublished;
            if (publish) {
                physicalShutdownPublished = true;
                closingResources.clear();
            }
        }
        // No final resource disposer may race a RUNNING Callable or the
        // synchronous completion listeners that may still hold a host loan.
        Throwable fatal = null;
        for (StopThenRelease resource : ready) {
            try {
                resource.close();
            } catch (Throwable failure) {
                recordPhysicalShutdownFailure(failure);
                if ((failure instanceof VirtualMachineError || failure instanceof ThreadDeath)
                        && fatal == null) fatal = failure;
            } finally {
                synchronized (this) { rejectedResourceCloses--; }
            }
        }
        if (!ready.isEmpty()) maybePublishPhysicalShutdown();
        if (publish) physicalShutdown.completeFromRuntime(null);
        if (fatal instanceof Error error) throw error;
    }

    public <T> OresFuture<T> submit(OresContext context,
            RuntimePermissions.Permission permission, String operation, Callable<T> work) {
        // Actor-local authority must be checked on the owning execution turn.
        context.requireCapability(permission.capability(), operation);
        return submitActorAware(context, operation, work, pending, capacity);
    }

    public <T> OresFuture<T> submitClose(OresContext context, Callable<T> work) {
        context.requireCapability(IsolatePolicy.Capability.NETWORK, "socket.close_async");
        return submitActorAware(context, "socket.close_async", work, controls, 16);
    }

    /**
     * Mint the actor's generation-bound release ticket before queue admission.
     * Root context work has no actor incarnation and retains the context-level
     * physicalShutdown receipt instead. An actor from another runtime may not
     * delegate its loan into this context.
     */
    private <T> OresFuture<T> submitActorAware(OresContext context, String operation,
            Callable<T> work, Set<OresFuture<?>> admitted, int limit) {
        if (context.nativeIo() != this) {
            throw new SecurityException("native I/O context affinity mismatch");
        }
        ActorPhysicalRetirementRegistry.Receipt actorReceipt = null;
        if (ActorRuntime.inActorExecution()) {
            ActorRuntime runtime = context.actors();
            if (ActorRuntime.currentActorRuntime() != runtime) {
                throw new SecurityException("native I/O actor runtime affinity mismatch");
            }
            actorReceipt = runtime.registerCurrentActorPhysicalReceipt(
                    ActorPhysicalRetirementRegistry.Kind.NATIVE_IO_OPERATION);
        }
        return submit(operation, work, admitted, limit, actorReceipt);
    }

    /**
     * Trusted in-runtime admission for native drivers already holding
     * their own permission-checked actor turn. This deliberately requires
     * a live single-writer actor lease; a foreign/root host thread cannot
     * manufacture a generation-owned I/O operation.
     */
    <T> OresFuture<T> submitCurrentActor(
            ActorRuntime runtime, String operation, Callable<T> work) {
        java.util.Objects.requireNonNull(runtime, "runtime");
        if (!ActorRuntime.inActorExecution()
                || ActorRuntime.currentActorRuntime() != runtime) {
            throw new SecurityException("native I/O requires exact actor execution lease");
        }
        var receipt = runtime.registerCurrentActorPhysicalReceipt(
                ActorPhysicalRetirementRegistry.Kind.NATIVE_IO_OPERATION);
        return submit(operation, work, pending, capacity, receipt);
    }

    <T> OresFuture<T> submitControl(String operation, Callable<T> work) {
        return submit(operation, work, controls, 16);
    }

    <T> OresFuture<T> submit(String operation, Callable<T> work) {
        return submit(operation, work, pending, capacity);
    }

    private <T> OresFuture<T> submit(String operation, Callable<T> work,
            Set<OresFuture<?>> admitted, int limit) {
        return submit(operation, work, admitted, limit, null);
    }

    private <T> OresFuture<T> submit(String operation, Callable<T> work,
            Set<OresFuture<?>> admitted, int limit,
            ActorPhysicalRetirementRegistry.Receipt actorReceipt) {
        OresFuture<T> result = new OresFuture<>(() -> false, () -> { });
        Operation<T> operationTask = null;
        String rejection = null;
        synchronized (this) {
            if (closed || admitted.size() >= limit) {
                rejection = closed ? "I/O context closed" : "I/O admission capacity exhausted";
            } else {
                admitted.add(result);
                operationTask = new Operation<>(
                        operation, work, result, admitted, CorePerf.start(), actorReceipt);
                admittedWork.add(operationTask);
            }
        }
        if (rejection != null) {
            // Neither the Future callback nor the actor's receipt notifier
            // may execute with the AsyncNativeIo admission lock held.
            try {
                CoreLog.record(CoreLog.Level.WARN, CoreLog.ASYNC_IO_REJECTED, 1L);
                result.failFromRuntime(new RejectedExecutionException(rejection));
            } finally {
                if (actorReceipt != null) actorReceipt.completeAfterPhysicalRelease();
            }
            return result;
        }
        try {
            workers.execute(operationTask);
        } catch (RuntimeException failure) {
            // A rejected execute() is not proof that a malicious executor did
            // not concurrently run the task. The phase CAS below decides:
            // a RUNNING operation remains owned until its actual exit.
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_REJECTED, 1L);
            operationTask.cancelBeforeStart();
        }
        return result;
    }

    void register(AutoCloseable resource) throws Exception {
        java.util.Objects.requireNonNull(resource, "resource");
        synchronized (this) {
            if (!closed) {
                resources.add(resource);
                return;
            }
            if (physicalShutdownPublished) {
                // The context has already published a permanent host-only
                // quiescence receipt. No new untracked close callback may
                // begin under that old authority; the caller remains the
                // owner of its never-admitted resource and must close it.
                throw new IllegalStateException(
                        "I/O context retired; caller must close unadmitted resource");
            }
            // The resource never obtained an admission lease. A caller that
            // raced STOP must close its rejected native handle without
            // holding the context monitor (close may block or reenter).
            rejectedResourceCloses++;
        }
        try {
            resource.close();
        } catch (Exception | Error failure) {
            // A failed rejected-resource close cannot become a clean native
            // retirement receipt. Retain the failed handle and quarantine.
            synchronized (this) { closingResources.add(resource); }
            recordPhysicalShutdownFailure(failure);
            throw failure;
        } finally {
            synchronized (this) { rejectedResourceCloses--; }
            maybePublishPhysicalShutdown();
        }
        throw new IllegalStateException("I/O context closed");
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
                if (resource instanceof StopThenRelease staged) {
                    // Defer physical reclamation until the last host worker
                    // AND its terminal observer have left. STOP is merely a
                    // cancellation request and can run while they still own loans.
                    synchronized (this) { deferredResources.add(staged); }
                    staged.requestStop();
                } else {
                    resource.close();
                }
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
