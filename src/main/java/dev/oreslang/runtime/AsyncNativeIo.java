package dev.oreslang.runtime;

import java.util.HashSet;
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
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ores-native-io-", 0).factory());
    private final Set<OresFuture<?>> pending = new HashSet<>();
    private final Set<OresFuture<?>> controls = new HashSet<>();
    private final Set<AutoCloseable> resources = new HashSet<>();
    private final int capacity;
    private boolean closed;

    public AsyncNativeIo() { this(128); }
    AsyncNativeIo(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("I/O capacity must be positive");
        this.capacity = capacity;
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
        // Cancellation cannot promise rollback of a filesystem mutation. The
        // operation remains owned by the context until completion or context close.
        OresFuture<T> result = new OresFuture<>(() -> false, () -> { });
        if (closed || admitted.size() >= limit) {
            CoreLog.record(CoreLog.Level.WARN, CoreLog.ASYNC_IO_REJECTED, 1L);
            result.failFromRuntime(new RejectedExecutionException(
                    closed ? "I/O context closed" : "I/O admission capacity exhausted"));
            return result;
        }
        admitted.add(result);
        long queued = CorePerf.start();
        try {
            workers.execute(() -> {
                CorePerf.end(CorePerf.ASYNC_IO_QUEUE, queued);
                long workPerf = CorePerf.start();
                T value = null;
                Throwable workFailure = null;
                try {
                    value = work.call();
                } catch (Throwable failure) {
                    workFailure = failure;
                } finally {
                    // The host operation has physically returned, including
                    // its I/O accounting finalizer. No terminal Future is
                    // published while that cleanup boundary remains active.
                    CorePerf.end(CorePerf.ASYNC_IO_WORK, workPerf);
                }
                final boolean closedAtRetirement;
                synchronized (this) {
                    admitted.remove(result);
                    closedAtRetirement = closed;
                }
                if (workFailure != null) {
                    CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, 1L);
                    SourceBoundaryTrace.record(workFailure, "native:io", operation, "I/O");
                    result.failFromRuntime(workFailure);
                    if (workFailure instanceof VirtualMachineError fatal) throw fatal;
                    if (workFailure instanceof ThreadDeath fatal) throw fatal;
                    if (workFailure instanceof LinkageError fatal) throw fatal;
                } else if (closedAtRetirement) {
                    result.failFromRuntime(new IllegalStateException(
                            "I/O context closed after physical operation exit"));
                } else {
                    result.completeFromRuntime(value);
                }
            });
        } catch (RuntimeException failure) {
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_REJECTED, 1L);
            admitted.remove(result);
            result.failFromRuntime(failure);
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
        Set<AutoCloseable> closing;
        synchronized (this) {
            if (closed) return;
            closed = true;
            closing = new HashSet<>(resources);
            resources.clear();
            // Keep running operation ownership in pending/controls until the
            // physical worker exits. Interrupt is only a request; it cannot
            // certify that a native call, pinned buffer or host callback
            // actually relinquished its loan.
        }
        workers.shutdownNow();
        for (AutoCloseable resource : closing) {
            try { resource.close(); } catch (Exception ignored) { /* best-effort shutdown */ }
        }
        // Do not fabricate a physical retirement receipt here. Each worker
        // publishes terminal failure/success only after its Callable returns.
        // An uncooperative worker deliberately leaves its Future unsettled
        // and its physical loan retained until a trusted quiescence witness
        // arrives.
    }
}
