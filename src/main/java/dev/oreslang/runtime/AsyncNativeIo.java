package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Context-owned host I/O. Never invokes guest code or parks an actor carrier.
 * Native asynchronous producers use completion callbacks; legacy filesystem/socket
 * adapters may still block a host worker. Both share bounded admission, with no
 * caller-runs fallback for worker operations.
 */
public final class AsyncNativeIo implements AutoCloseable {
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ores-native-io-", 0).factory());
    private final Set<OresFuture<?>> pending = new HashSet<>();
    private final Set<OresFuture<?>> controls = new HashSet<>();
    private final Set<AutoCloseable> resources = new HashSet<>();
    private final int capacity;
    private boolean closed;
    private HttpClient httpClient;

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

    /** Native asynchronous operations share admission with worker-backed I/O.
     * The registrar must return promptly; callbacks only settle runtime Futures.
     * Its external resources must be registered with this context for shutdown.
     */
    public <T> OresFuture<T> submitAsync(OresContext context,
            RuntimePermissions.Permission permission, String operation,
            Supplier<? extends CompletionStage<T>> registrar) {
        context.requireCapability(permission.capability(), operation);
        return submitAsync(operation, registrar);
    }

    <T> OresFuture<T> submitAsync(String operation,
            Supplier<? extends CompletionStage<T>> registrar) {
        OresFuture<T> result = new OresFuture<>(() -> false, () -> { });
        synchronized (this) {
            if (closed || pending.size() >= capacity) {
                CoreLog.record(CoreLog.Level.WARN, CoreLog.ASYNC_IO_REJECTED, 1L);
                result.failFromRuntime(new RejectedExecutionException(
                        closed ? "I/O context closed" : "I/O admission capacity exhausted"));
                return result;
            }
            pending.add(result);
        }
        long started = CorePerf.start();
        try {
            // No guest execution or socket/file readiness waits here.
            java.util.Objects.requireNonNull(registrar.get(), "async I/O completion")
                    .whenComplete((value, failure) -> {
                        CorePerf.end(CorePerf.ASYNC_IO_WORK, started);
                        synchronized (this) { pending.remove(result); }
                        if (failure == null) result.completeFromRuntime(value);
                        else failAsync(result, operation, OresFuture.unwrap(failure));
                    });
        } catch (RuntimeException | Error failure) {
            synchronized (this) { pending.remove(result); }
            failAsync(result, operation, failure);
            if (failure instanceof Error fatal) throw fatal;
        }
        return result;
    }

    private <T> void failAsync(OresFuture<T> result, String operation, Throwable failure) {
        CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, 1L);
        SourceBoundaryTrace.record(failure, "native:io", operation, "asynchronous I/O");
        result.failFromRuntime(failure);
    }

    synchronized HttpClient httpClient() {
        if (closed) throw new IllegalStateException("I/O context closed");
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(15)).build();
            // shutdownNow initiates cancellation without awaiting peer I/O.
            resources.add(httpClient::shutdownNow);
        }
        return httpClient;
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
        }
        workers.shutdownNow();
        for (AutoCloseable resource : closing) {
            try { resource.close(); } catch (Exception ignored) { /* best-effort shutdown */ }
        }
        for (OresFuture<?> future : failing)
            future.failFromRuntime(new IllegalStateException("I/O context closed"));
    }
}
