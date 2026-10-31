package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
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
    /**
     * Registered native handles are physical actor loans when registration
     * happens during an actor turn. Identity semantics avoid alias/equality
     * surprises from host resource implementations.
     */
    private final Map<AutoCloseable, ActorRuntime.ActorPhysicalLease> resources =
            new IdentityHashMap<>();
    private final int capacity;
    private boolean closed;

    public AsyncNativeIo() { this(128); }
    AsyncNativeIo(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("I/O capacity must be positive");
        this.capacity = capacity;
    }

    public <T> OresFuture<T> submit(OresContext context,
            RuntimePermissions.Permission permission, String operation, Callable<T> work) {
        // Actor-local authority and physical-lifetime admission must both occur
        // before the operation leaves its actor execution turn.
        context.requireCapability(permission.capability(), operation);
        return submit(operation, work);
    }

    public <T> OresFuture<T> submitClose(OresContext context, Callable<T> work) {
        context.requireCapability(IsolatePolicy.Capability.NETWORK, "socket.close_async");
        return submitControl("socket.close_async", work);
    }

    <T> OresFuture<T> submitControl(String operation, Callable<T> work) {
        return submit(
                operation,
                work,
                controls,
                16,
                captureActorPhysicalLease(operation));
    }

    <T> OresFuture<T> submit(String operation, Callable<T> work) {
        return submit(
                operation,
                work,
                pending,
                capacity,
                captureActorPhysicalLease(operation));
    }

    private ActorRuntime.ActorPhysicalLease captureActorPhysicalLease(
            String operation) {
        ActorRuntime runtime = ActorRuntime.currentActorRuntime();
        if (runtime == null) return null;
        return runtime.acquireCurrentActorPhysicalLifetime(
                "native-io:" + operation);
    }

    private <T> OresFuture<T> submit(
            String operation,
            Callable<T> work,
            Set<OresFuture<?>> admitted,
            int limit,
            ActorRuntime.ActorPhysicalLease actorLease) {
        // Cancellation cannot promise rollback of a filesystem mutation. The
        // physical actor lease therefore survives Future settlement and context
        // close until the worker itself exits and terminal callbacks drain.
        OresFuture<T> result = new OresFuture<>(() -> false, () -> { });
        synchronized (this) {
            if (closed || admitted.size() >= limit) {
                CoreLog.record(CoreLog.Level.WARN, CoreLog.ASYNC_IO_REJECTED, 1L);
                result.failFromRuntime(new RejectedExecutionException(
                        closed
                                ? "I/O context closed"
                                : "I/O admission capacity exhausted"));
            } else {
                admitted.add(result);
            }
        }

        if (result.isDone()) {
            if (actorLease != null) actorLease.retire();
            return result;
        }

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
                    synchronized (this) {
                        admitted.remove(result);
                    }
                    SourceBoundaryTrace.record(
                            failure, "native:io", operation, "I/O");
                    try {
                        result.failFromRuntime(failure);
                        if (actorLease != null) actorLease.retire();
                    } catch (RuntimeException | Error publicationFailure) {
                        if (actorLease != null) actorLease.fail(publicationFailure);
                        throw publicationFailure;
                    }
                    if (failure instanceof VirtualMachineError fatal) throw fatal;
                    if (failure instanceof ThreadDeath fatal) throw fatal;
                    if (failure instanceof LinkageError fatal) throw fatal;
                    return;
                } finally {
                    CorePerf.end(CorePerf.ASYNC_IO_WORK, workPerf);
                }

                synchronized (this) {
                    admitted.remove(result);
                }
                try {
                    result.completeFromRuntime(value);
                    if (actorLease != null) actorLease.retire();
                } catch (RuntimeException | Error publicationFailure) {
                    if (actorLease != null) actorLease.fail(publicationFailure);
                    throw publicationFailure;
                }
            });
        } catch (RuntimeException failure) {
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_REJECTED, 1L);
            synchronized (this) {
                admitted.remove(result);
            }
            result.failFromRuntime(failure);
            if (actorLease != null) actorLease.retire();
        }
        return result;
    }

    void register(AutoCloseable resource) throws Exception {
        Objects.requireNonNull(resource, "native I/O resource");
        ActorRuntime.ActorPhysicalLease actorLease =
                captureActorPhysicalLease("native-registration");

        boolean closeImmediately;
        boolean duplicate;
        synchronized (this) {
            closeImmediately = closed;
            duplicate = !closed && resources.containsKey(resource);
            if (!closeImmediately && !duplicate) {
                resources.put(resource, actorLease);
            }
        }

        if (duplicate) {
            if (actorLease != null) actorLease.retire();
            return;
        }
        if (!closeImmediately) return;

        try {
            resource.close();
            if (actorLease != null) actorLease.retire();
        } catch (Exception | Error closeFailure) {
            if (actorLease != null) actorLease.fail(closeFailure);
            throw closeFailure;
        }
        throw new IllegalStateException("I/O context closed");
    }

    /**
     * Detach only after the caller has physically closed the resource. This
     * method retires the actor's registration lease and therefore must never be
     * used as a pre-close bookkeeping operation.
     */
    void unregister(AutoCloseable resource) {
        ActorRuntime.ActorPhysicalLease actorLease;
        synchronized (this) {
            actorLease = resources.remove(resource);
        }
        if (actorLease != null) actorLease.retire();
    }

    @Override public void close() {
        Map<AutoCloseable, ActorRuntime.ActorPhysicalLease> closing;
        Set<OresFuture<?>> failing;
        synchronized (this) {
            if (closed) return;
            closed = true;
            closing = new IdentityHashMap<>(resources);
            resources.clear();
            failing = new HashSet<>(pending);
            failing.addAll(controls);
            controls.clear();
            pending.clear();
        }
        workers.shutdownNow();
        for (Map.Entry<AutoCloseable, ActorRuntime.ActorPhysicalLease> entry
                : closing.entrySet()) {
            try {
                entry.getKey().close();
                if (entry.getValue() != null) entry.getValue().retire();
            } catch (Exception | Error closeFailure) {
                if (entry.getValue() != null) entry.getValue().fail(closeFailure);
                if (closeFailure instanceof VirtualMachineError fatal) throw fatal;
                if (closeFailure instanceof ThreadDeath fatal) throw fatal;
                if (closeFailure instanceof LinkageError fatal) throw fatal;
                // Ordinary close failures quarantine the owning actor receipt.
            }
        }
        for (OresFuture<?> future : failing)
            future.failFromRuntime(new IllegalStateException("I/O context closed"));
    }
}
