package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Context-owned host I/O. Never invokes guest code or parks an actor carrier.
 * Legacy filesystem/socket calls may block an offload worker. Truly asynchronous
 * transports use submitNonblocking() and do not occupy an I/O worker for the
 * operation's lifetime. Both paths have bounded admission and no caller-runs path.
 */
public final class AsyncNativeIo implements AutoCloseable {
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ores-native-io-", 0).factory());
    private final Set<OresFuture<?>> pending = new HashSet<>();
    private final Set<OresFuture<?>> controls = new HashSet<>();
    private final Map<OresFuture<?>, CompletableFuture<?>> nonblocking = new HashMap<>();
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

    /** Admit a transport that completes through callbacks, not a blocking host
     * worker. The supplier must only initiate asynchronous work: no send(),
     * blocking socket read, join(), or get() is permitted on the calling carrier.
     * Cancelling the owning context also cancels the submitted transport stage.
     * Per-operation cancellation is intentionally not exposed until the I/O
     * cancellation/rollback contract is defined.
     */
    public <T> OresFuture<T> submitNonblocking(OresContext context,
            RuntimePermissions.Permission permission, String operation,
            Supplier<CompletableFuture<T>> start) {
        context.requireCapability(permission.capability(), operation);
        return submitNonblocking(operation, start);
    }

    /** Package-private for deterministic admission and teardown tests.
     * Only trusted runtime paths may use this unchecked internal entry point.
     */
    <T> OresFuture<T> submitNonblocking(String operation,
            Supplier<CompletableFuture<T>> start) {
        OresFuture<T> result = new OresFuture<>(() -> false, () -> { });
        synchronized (this) {
            if (closed || pending.size() >= capacity) {
                result.failFromRuntime(new RejectedExecutionException(
                        closed ? "I/O context closed" : "I/O admission capacity exhausted"));
                return result;
            }
            pending.add(result);
        }

        // HttpClient.sendAsync does not block awaiting its response, but its
        // request startup, proxy lookup or name resolution may do synchronous
        // work. Never execute that setup on an actor scheduler carrier.
        // This worker is held only until the async transport has been started.
        try {
            workers.execute(() -> startNonblockingTransport(operation, start, result));
        } catch (RuntimeException failure) {
            synchronized (this) {
                pending.remove(result);
                result.failFromRuntime(failure);
            }
        }
        return result;
    }

    private <T> void startNonblockingTransport(String operation,
            Supplier<CompletableFuture<T>> start, OresFuture<T> result) {
        CompletableFuture<T> transport;
        try {
            transport = Objects.requireNonNull(start.get(),
                    "nonblocking I/O transport returned null future");
        } catch (Throwable failure) {
            SourceBoundaryTrace.record(failure, "native:io", operation, "I/O");
            synchronized (this) {
                pending.remove(result);
                result.failFromRuntime(failure);
            }
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            if (failure instanceof LinkageError fatal) throw fatal;
            return;
        }

        boolean contextClosed;
        synchronized (this) {
            contextClosed = closed;
            if (!contextClosed) nonblocking.put(result, transport);
        }
        if (contextClosed) {
            transport.cancel(true);
            // close() has already failed every admitted Future under the
            // admission lock; this fallback is idempotent.
            result.failFromRuntime(new IllegalStateException("I/O context closed"));
            return;
        }
        transport.whenComplete((value, failure) -> {
            Throwable cause = failure instanceof CompletionException completion
                    && completion.getCause() != null ? completion.getCause() : failure;
            if (cause != null) {
                SourceBoundaryTrace.record(cause, "native:io", operation, "I/O");
            }
            synchronized (AsyncNativeIo.this) {
                pending.remove(result);
                nonblocking.remove(result);
                if (closed) {
                    result.failFromRuntime(new IllegalStateException("I/O context closed"));
                } else if (cause != null) {
                    result.failFromRuntime(cause);
                } else {
                    result.completeFromRuntime(value);
                }
            }
        });
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
            result.failFromRuntime(new RejectedExecutionException(
                    closed ? "I/O context closed" : "I/O admission capacity exhausted"));
            return result;
        }
        admitted.add(result);
        try {
            workers.execute(() -> {
                T value;
                try {
                    value = work.call();
                } catch (Throwable failure) {
                    SourceBoundaryTrace.record(failure, "native:io", operation, "I/O");
                    synchronized (this) {
                        admitted.remove(result);
                        result.failFromRuntime(failure);
                    }
                    if (failure instanceof VirtualMachineError fatal) throw fatal;
                    if (failure instanceof ThreadDeath fatal) throw fatal;
                    if (failure instanceof LinkageError fatal) throw fatal;
                    return;
                }
                synchronized (this) {
                    admitted.remove(result);
                    result.completeFromRuntime(value);
                }
            });
        } catch (RuntimeException failure) {
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
        Set<CompletableFuture<?>> cancelling;
        synchronized (this) {
            if (closed) return;
            closed = true;
            closing = new HashSet<>(resources);
            resources.clear();
            failing = new HashSet<>(pending);
            failing.addAll(controls);
            controls.clear();
            pending.clear();
            cancelling = new HashSet<>(nonblocking.values());
            nonblocking.clear();
            // Close must settle every admitted Future before any worker or
            // transport callback can publish a late result.
            for (OresFuture<?> future : failing)
                future.failFromRuntime(new IllegalStateException("I/O context closed"));
        }
        for (CompletableFuture<?> stage : cancelling) stage.cancel(true);
        workers.shutdownNow();
        for (AutoCloseable resource : closing) {
            try { resource.close(); } catch (Exception ignored) { /* best-effort shutdown */ }
        }
    }
}
