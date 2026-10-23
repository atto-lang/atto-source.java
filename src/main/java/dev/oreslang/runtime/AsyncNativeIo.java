package dev.oreslang.runtime;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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

    // Trusted runtime entry point ONLY. Deliberately package-private: the
    // Oreslang guest must not get a raw host CompletableFuture/Response handle.
    // OresContext performs both actor-local and scoped NET permission checks
    // synchronously before any outbound request is admitted or dispatched.
    private ProxyOutboundLoopback proxyLoopback;

    CompletableFuture<ProxyOutboundLoopback.Response> requestProxyLoopback(
            OresContext context, URI target, String method) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(target, "target");
        if (context.nativeIo() != this)
            throw new SecurityException("loopback I/O authority must come from the owning OresContext");
        // Validate the complete canonical authority/path/method *before*
        // consulting a permission scope, and repeat validation in the client
        // immediately before network admission (defense in depth).
        try {
            ProxyOutboundLoopback.validateTarget(target, method);
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        context.requirePermission(RuntimePermissions.Permission.NET,
                "127.0.0.1:" + target.getPort(), "proxy.loopback.request");

        ProxyOutboundLoopback transport;
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(
                    new RejectedExecutionException("context native I/O already closed"));
            if (proxyLoopback == null) {
                // The transport and all its pending/delivered response leases
                // are retired by AsyncNativeIo.close() via the resource set.
                proxyLoopback = new ProxyOutboundLoopback(Math.min(capacity, 128));
                resources.add(proxyLoopback);
            }
            transport = proxyLoopback;
        }
        // request() is nonblocking. Never join its Future on an actor carrier.
        return transport.request(target, method);
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
