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

    /** Submit host I/O after checking an actor-local capability. Guest conversion
     * must finish before submission; workers execute only trusted host I/O. */
    public <T> OresFuture<T> submitCapability(OresContext context,
            IsolatePolicy.Capability capability, String operation, Callable<T> work) {
        context.requireCapability(capability, operation);
        return submit(operation, work);
    }

    public <T> OresFuture<T> submitClose(OresContext context, Callable<T> work) {
        context.requireCapability(IsolatePolicy.Capability.NETWORK, "socket.close_async");
        return submitControl("socket.close_async", work);
    }

    <T> OresFuture<T> submitControl(String operation, Callable<T> work) {
        return submit(operation, work, controls, 16);
    }

    <T> OresFuture<T> submit(String operation, Callable<T> work) {
        return submit(operation, work, pending, capacity);
    }

    private <T> OresFuture<T> submit(String operation, Callable<T> work,
            Set<OresFuture<?>> admitted, int limit) {
        // Each operation has one context-owned terminal decision. The monitor
        // orders admission, completion, and close, but Future settlement must
        // be outside the monitor: settlement synchronously notifies runtime
        // observers and their host CONTROL plumbing must never run locked.
        OresFuture<T> result = new OresFuture<>(() -> false, () -> { });
        RuntimeException rejected = null;
        synchronized (this) {
            if (closed || admitted.size() >= limit) {
                rejected = new RejectedExecutionException(
                        closed ? "I/O context closed" : "I/O admission capacity exhausted");
            } else {
                admitted.add(result);
                long queued = CorePerf.start();
                try {
                    workers.execute(() -> {
                        CorePerf.end(CorePerf.ASYNC_IO_QUEUE, queued);
                        long workPerf = CorePerf.start();
                        T value = null;
                        Throwable failure = null;
                        try {
                            value = work.call();
                        } catch (Throwable caught) {
                            failure = caught;
                            SourceBoundaryTrace.record(caught, "native:io", operation, "I/O");
                        } finally {
                            CorePerf.end(CorePerf.ASYNC_IO_WORK, workPerf);
                        }
                        // Removing the admitted operation under this monitor
                        // irrevocably selects the worker outcome. If close()
                        // gets the lock first, it selects failure instead.
                        // Only the winner publishes, AFTER unlocking.
                        final boolean winner;
                        synchronized (AsyncNativeIo.this) {
                            winner = admitted.remove(result);
                        }
                        if (winner) {
                            if (failure == null) result.completeFromRuntime(value);
                            else result.failFromRuntime(failure);
                        }
                        if (failure instanceof VirtualMachineError fatal) throw fatal;
                        if (failure instanceof ThreadDeath fatal) throw fatal;
                        if (failure instanceof LinkageError fatal) throw fatal;
                    });
                } catch (RuntimeException failure) {
                    admitted.remove(result);
                    rejected = failure;
                }
            }
        }
        if (rejected != null) result.failFromRuntime(rejected);
        return result;
    }

    void register(AutoCloseable resource) throws Exception {
        // Resource.close() can block or reenter runtime plumbing, so never
        // invoke third-party teardown while holding the admission monitor.
        synchronized (this) {
            if (!closed) {
                resources.add(resource);
                return;
            }
        }
        resource.close();
        throw new IllegalStateException("I/O context closed");
    }

    synchronized void unregister(AutoCloseable resource) { resources.remove(resource); }

    @Override public void close() {
        Set<AutoCloseable> closing;
        Set<OresFuture<?>> failing;
        synchronized (this) {
            if (closed) return;
            closed = true;
            closing = new HashSet<>(resources);
            resources.clear();
            failing = new HashSet<>(pending);
            failing.addAll(controls);
            controls.clear();
            pending.clear();
            // Clearing admission makes teardown's terminal decision
            // authoritative; publish failures only after releasing the monitor
            // so registered runtime observers cannot reenter under the lock.
        }
        for (OresFuture<?> future : failing)
            future.failFromRuntime(new IllegalStateException("I/O context closed"));
        workers.shutdownNow();
        for (AutoCloseable resource : closing) {
            try { resource.close(); } catch (Exception ignored) { /* best-effort shutdown */ }
        }
    }
}
