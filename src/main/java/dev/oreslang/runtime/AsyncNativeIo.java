package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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
    private final Object retirementLock = new Object();
    private boolean retired;
    private Thread retiringThread;

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
                T value;
                try {
                    value = work.call();
                } catch (Throwable failure) {
                    CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, 1L);
                    synchronized (this) { admitted.remove(result); }
                    SourceBoundaryTrace.record(failure, "native:io", operation, "I/O");
                    result.failFromRuntime(failure);
                    if (failure instanceof VirtualMachineError fatal) throw fatal;
                    if (failure instanceof ThreadDeath fatal) throw fatal;
                    if (failure instanceof LinkageError fatal) throw fatal;
                    return;
                } finally {
                    CorePerf.end(CorePerf.ASYNC_IO_WORK, workPerf);
                }
                synchronized (this) { admitted.remove(result); }
                result.completeFromRuntime(value);
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
        synchronized (retirementLock) {
            if (retired) return;
            if (retiringThread == Thread.currentThread()) {
                throw new IllegalStateException(
                        "native I/O retirement cannot be re-entered by a completion callback");
            }
            retiringThread = Thread.currentThread();
            try {
                boolean firstClose;
                synchronized (this) {
                    firstClose = !closed;
                    closed = true;
                }
                if (firstClose) workers.shutdownNow();
                try {
                    if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "native I/O workers still own pending operations after close timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "interrupted before native I/O physical worker exit", interrupted);
                }

                Set<AutoCloseable> closing;
                Set<OresFuture<?>> failing;
                synchronized (this) {
                    closing = new HashSet<>(resources);
                    failing = new HashSet<>(pending);
                    failing.addAll(controls);
                    controls.clear();
                    pending.clear();
                }
                IllegalStateException releaseFailure = null;
                Error fatalFailure = null;
                for (AutoCloseable resource : closing) {
                    try {
                        resource.close();
                        synchronized (this) { resources.remove(resource); }
                    } catch (Throwable failure) {
                        // Failure, including a host Error, must not strand
                        // unrelated closeable resources. The failed resource
                        // remains registered for the next physical-close retry.
                        if (releaseFailure == null) {
                            releaseFailure = new IllegalStateException(
                                    "native I/O resources did not release after worker exit");
                        }
                        releaseFailure.addSuppressed(failure);
                        if (failure instanceof VirtualMachineError
                                || failure instanceof ThreadDeath
                                || failure instanceof LinkageError) {
                            if (fatalFailure == null) fatalFailure = (Error) failure;
                            else if (fatalFailure != failure) fatalFailure.addSuppressed(failure);
                        }
                    }
                }
                for (OresFuture<?> future : failing) {
                    try {
                        future.failFromRuntime(new IllegalStateException("I/O context closed"));
                    } catch (Throwable failure) {
                        // Subscriber publication is outside the admission
                        // monitor. Attempt every pending settlement even if a
                        // user/host callback fails while observing another.
                        if (releaseFailure == null) {
                            releaseFailure = new IllegalStateException(
                                    "native I/O completion publication failed during physical shutdown");
                        }
                        releaseFailure.addSuppressed(failure);
                        if (failure instanceof VirtualMachineError
                                || failure instanceof ThreadDeath
                                || failure instanceof LinkageError) {
                            if (fatalFailure == null) fatalFailure = (Error) failure;
                            else if (fatalFailure != failure) fatalFailure.addSuppressed(failure);
                        }
                    }
                }
                if (fatalFailure != null) {
                    if (releaseFailure != null) fatalFailure.addSuppressed(releaseFailure);
                    throw fatalFailure;
                }
                if (releaseFailure != null) throw releaseFailure;
                retired = true;
            } finally {
                retiringThread = null;
            }
        }
    }
}
