package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Context-owned host I/O. Never invokes guest code or parks an actor carrier.
 *
 * <p>Actor-admitted work carries a generation-bound physical retirement lease.
 * Nested worker work and native registrations fork that already-admitted lease,
 * including after shutdown seals new root admission. A terminal Future,
 * unregister call, interrupt, or timeout is never a physical-retirement proof.</p>
 */
public final class AsyncNativeIo implements AutoCloseable {
    private static final long CLOSE_WAIT_NANOS =
            TimeUnit.MILLISECONDS.toNanos(500);

    /**
     * A failed close keeps the context strongly reachable. Ref deletion or a
     * caller dropping its last reference must not turn an incomplete physical
     * join into accidental native-resource reclamation.
     */
    private static final Set<AsyncNativeIo> QUARANTINED_IO =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ores-native-io-", 0).factory());
    private final Set<OresFuture<?>> pending = new HashSet<>();
    private final Set<OresFuture<?>> controls = new HashSet<>();

    /**
     * Identity semantics are required: a native handle registration is a
     * physical ownership edge, not a logical equals/hashCode relationship.
     * A non-null lease means this handle is actor-owned.
     */
    private static final class ResourceRegistration {
        final long generation;
        final ActorRuntime.ActorPhysicalLease actorLease;
        final ActorPermissionScope.PhysicalLease permissionLease;

        ResourceRegistration(
                long generation,
                ActorRuntime.ActorPhysicalLease actorLease,
                ActorPermissionScope.PhysicalLease permissionLease) {
            this.generation = generation;
            this.actorLease = actorLease;
            this.permissionLease = permissionLease;
        }
    }

    private final Map<AutoCloseable, ResourceRegistration> resources =
            new IdentityHashMap<>();
    private final AtomicLong nextResourceGeneration = new AtomicLong();

    /** Parent lease restored on an I/O worker so nested work can fork safely. */
    private final ThreadLocal<ActorRuntime.ActorPhysicalLease>
            currentOperationActorLease = new ThreadLocal<>();
    private final ThreadLocal<Boolean> executingWorker =
            ThreadLocal.withInitial(() -> false);

    private final int capacity;
    /** Seals new I/O admission; failed close remains retryable. */
    private boolean closed;
    /** True only after workers joined and every registered resource closed. */
    private boolean resourcesRetired;
    /** Prevent recursive context close from a resource close callback. */
    private boolean resourceCloseActive;

    public AsyncNativeIo() { this(128); }

    AsyncNativeIo(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "I/O capacity must be positive");
        }
        this.capacity = capacity;
    }

    public <T> OresFuture<T> submit(
            OresContext context,
            RuntimePermissions.Permission permission,
            String operation,
            Callable<T> work) {
        // Actor-local authority and physical-lifetime admission must both occur
        // before the operation leaves its actor execution turn.
        context.requireCapability(permission.capability(), operation);
        return submit(operation, work);
    }

    public <T> OresFuture<T> submitClose(
            OresContext context,
            Callable<T> work) {
        context.requireCapability(
                IsolatePolicy.Capability.NETWORK,
                "socket.close_async");
        return submitControl("socket.close_async", work);
    }

    <T> OresFuture<T> submitControl(
            String operation,
            Callable<T> work) {
        return submit(
                operation,
                work,
                controls,
                16,
                captureActorPhysicalLease(operation));
    }

    <T> OresFuture<T> submit(
            String operation,
            Callable<T> work) {
        return submit(
                operation,
                work,
                pending,
                capacity,
                captureActorPhysicalLease(operation));
    }

    /**
     * A nested worker admission is a descendant of already-admitted physical
     * work, not a new root admission. This is what lets a connect worker hand
     * ownership to a returned socket after actor shutdown has already sealed.
     */
    private ActorRuntime.ActorPhysicalLease captureActorPhysicalLease(
            String operation) {
        ActorRuntime.ActorPhysicalLease parent =
                currentOperationActorLease.get();
        if (parent != null) {
            return parent.fork("native-io:" + operation);
        }

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
        Objects.requireNonNull(operation, "native I/O operation");
        Objects.requireNonNull(work, "native I/O work");

        final ActorRuntime.NativeIoAuthority ioAuthority;
        final ActorPermissionScope.PhysicalLease permissionLease;
        try {
            ioAuthority = ActorRuntime.captureNativeIoAuthority();
            permissionLease = ActorRuntime.capturePermissionPhysicalLease(
                    "native-io:" + operation);
        } catch (RuntimeException | Error permissionAdmissionFailure) {
            if (actorLease != null) actorLease.retire();
            throw permissionAdmissionFailure;
        }

        OresFuture<T> result = new OresFuture<>(() -> false, () -> { });
        synchronized (this) {
            if (closed || admitted.size() >= limit) {
                CoreLog.record(
                        CoreLog.Level.WARN,
                        CoreLog.ASYNC_IO_REJECTED,
                        1L);
                result.failFromRuntime(new RejectedExecutionException(
                        closed
                                ? "I/O context closed"
                                : "I/O admission capacity exhausted"));
            } else {
                admitted.add(result);
            }
        }

        if (result.isDone()) {
            if (permissionLease != null) permissionLease.close();
            if (actorLease != null) actorLease.retire();
            return result;
        }

        long queued = CorePerf.start();
        try {
            workers.execute(() -> {
                executingWorker.set(true);
                ActorRuntime.ActorPhysicalLease previous =
                        currentOperationActorLease.get();
                if (actorLease == null) {
                    currentOperationActorLease.remove();
                } else {
                    currentOperationActorLease.set(actorLease);
                }

                boolean permissionAuthorityEntered = false;
                try {
                    CorePerf.end(CorePerf.ASYNC_IO_QUEUE, queued);
                    ActorRuntime.enterNativeIoAuthority(ioAuthority);
                    permissionAuthorityEntered = ioAuthority != null;
                    long workPerf = CorePerf.start();
                    T value;
                    try {
                        // A queued worker must revalidate delegated authority at
                        // the physical start boundary. Revocation may win after
                        // queue admission but before work.call().
                        if (ioAuthority != null) {
                            ioAuthority.requireActive(operation);
                        }
                        value = work.call();
                    } catch (Throwable failure) {
                        CoreLog.record(
                                CoreLog.Level.ERROR,
                                CoreLog.ASYNC_IO_FAILED,
                                1L);
                        synchronized (this) {
                            admitted.remove(result);
                            notifyAll();
                        }
                        SourceBoundaryTrace.record(
                                failure,
                                "native:io",
                                operation,
                                "I/O");
                        try {
                            result.failFromRuntime(failure);
                            if (permissionLease != null) permissionLease.close();
                            if (actorLease != null) actorLease.retire();
                        } catch (RuntimeException | Error publicationFailure) {
                            if (ioAuthority != null) {
                                ioAuthority.quarantinePhysicalDrain();
                            }
                            if (actorLease != null) {
                                actorLease.fail(publicationFailure);
                            }
                            throw publicationFailure;
                        }
                        if (failure instanceof VirtualMachineError fatal) {
                            throw fatal;
                        }
                        if (failure instanceof ThreadDeath fatal) {
                            throw fatal;
                        }
                        if (failure instanceof LinkageError fatal) {
                            throw fatal;
                        }
                        return;
                    } finally {
                        CorePerf.end(CorePerf.ASYNC_IO_WORK, workPerf);
                    }

                    synchronized (this) {
                        admitted.remove(result);
                        notifyAll();
                    }
                    try {
                        // OresFuture terminal subscribers drain synchronously.
                        // Retire both permission and actor physical leases only
                        // after they return.
                        result.completeFromRuntime(value);
                        if (permissionLease != null) permissionLease.close();
                        if (actorLease != null) actorLease.retire();
                    } catch (RuntimeException | Error publicationFailure) {
                        if (ioAuthority != null) {
                            ioAuthority.quarantinePhysicalDrain();
                        }
                        if (actorLease != null) {
                            actorLease.fail(publicationFailure);
                        }
                        throw publicationFailure;
                    }
                } finally {
                    try {
                        if (permissionAuthorityEntered) {
                            ActorRuntime.exitNativeIoAuthority(ioAuthority);
                        }
                    } finally {
                        if (previous == null) {
                            currentOperationActorLease.remove();
                        } else {
                            currentOperationActorLease.set(previous);
                        }
                        executingWorker.remove();
                    }
                }
            });
        } catch (RuntimeException failure) {
            CoreLog.record(
                    CoreLog.Level.ERROR,
                    CoreLog.ASYNC_IO_REJECTED,
                    1L);
            synchronized (this) {
                admitted.remove(result);
                notifyAll();
            }
            try {
                result.failFromRuntime(failure);
                if (permissionLease != null) permissionLease.close();
                if (actorLease != null) actorLease.retire();
            } catch (RuntimeException | Error publicationFailure) {
                if (ioAuthority != null) {
                    ioAuthority.quarantinePhysicalDrain();
                }
                if (actorLease != null) {
                    actorLease.fail(publicationFailure);
                }
                throw publicationFailure;
            }
        }
        return result;
    }

    /**
     * Register one physical native handle. If registration occurs from an
     * actor-owned I/O worker, the new handle forks the worker's existing lease
     * rather than attempting forbidden root admission after shutdown sealing.
     */
    void register(AutoCloseable resource) throws Exception {
        Objects.requireNonNull(resource, "native I/O resource");

        synchronized (this) {
            if (resources.containsKey(resource)) {
                throw new IllegalStateException(
                        "duplicate native resource registration");
            }
            if (closed && resourcesRetired) {
                throw new IllegalStateException(
                        "I/O context fully retired; caller retains resource ownership");
            }
        }

        ActorRuntime.ActorPhysicalLease actorLease =
                captureActorPhysicalLease("native-registration");
        ActorPermissionScope.PhysicalLease permissionLease;
        try {
            permissionLease = ActorRuntime.capturePermissionPhysicalLease(
                    "native-registration");
        } catch (RuntimeException | Error permissionFailure) {
            if (actorLease != null) actorLease.retire();
            throw permissionFailure;
        }

        boolean rejectAfterTracking = false;
        boolean rejectWithoutTracking = false;
        boolean generationExhausted = false;
        synchronized (this) {
            if (resources.containsKey(resource)) {
                rejectWithoutTracking = true;
            } else if (closed && resourcesRetired) {
                rejectWithoutTracking = true;
            } else {
                // A worker that was admitted before shutdown may create a
                // handle after closed=true. Retain it for the post-join sweep;
                // its forked actor lease prevents arena retirement.
                long generation = nextResourceGeneration.incrementAndGet();
                if (generation <= 0) {
                    rejectWithoutTracking = true;
                    generationExhausted = true;
                } else {
                    resources.put(
                            resource,
                            new ResourceRegistration(
                                    generation,
                                    actorLease,
                                    permissionLease));
                    rejectAfterTracking = closed;
                }
            }
        }

        if (rejectWithoutTracking) {
            if (permissionLease != null) permissionLease.close();
            if (actorLease != null) actorLease.retire();
            throw new IllegalStateException(
                    generationExhausted
                            ? "native resource generation space exhausted"
                            : "native resource registration raced duplicate/retired state");
        }
        if (rejectAfterTracking) {
            // A pre-shutdown worker created ownership after scope admission was
            // sealed. Keep the resource/leases for context cleanup but withhold
            // delegated drain evidence until independently reconciled.
            ActorRuntime.quarantineCurrentPermissionDrain();
            throw new IllegalStateException(
                    "I/O context closed; late resource retained for physical cleanup");
        }
    }

    /**
     * A bare detach is not a physical close witness. Actor-owned sockets may
     * unregister only after the JDK reports them closed. Other actor-owned
     * AutoCloseables have no generic verifiable close state and therefore stay
     * context-owned until close() performs the physical close itself.
     */
    void unregister(AutoCloseable resource) {
        ResourceRegistration registration;
        synchronized (this) {
            registration = resources.get(resource);
            if (registration == null) return;
            if (registration.actorLease != null
                    && (!(resource instanceof java.net.Socket socket)
                            || !socket.isClosed())) {
                throw new SecurityException(
                        "actor-owned native handle must be physically closed before unregister");
            }
            // Remove only this exact registration generation.
            if (resources.get(resource) != registration) {
                throw new IllegalStateException(
                        "stale native resource registration cannot retire current generation");
            }
            resources.remove(resource);
        }
        if (registration.permissionLease != null) {
            registration.permissionLease.close();
        }
        if (registration.actorLease != null) {
            registration.actorLease.retire();
        }
    }

    /**
     * Phase one: seal I/O admission and interrupt workers. Registered sockets
     * are closed only as a wake-up mechanism; their actor registration leases
     * remain live until the post-join sweep proves worker exit.
     */
    void requestShutdown() {
        final List<java.net.Socket> sockets = new ArrayList<>();
        final boolean first;
        synchronized (this) {
            first = !closed;
            closed = true;
            if (first) {
                for (AutoCloseable resource : resources.keySet()) {
                    if (resource instanceof java.net.Socket socket) {
                        sockets.add(socket);
                    }
                }
            }
        }
        if (!first) return;

        workers.shutdownNow();
        for (java.net.Socket socket : sockets) {
            try {
                socket.close();
            } catch (java.io.IOException ignored) {
                // The post-join resource sweep retries. The registration lease
                // remains live, so this wake-up failure cannot forge retirement.
            }
        }
    }

    @Override
    public void close() {
        if (Boolean.TRUE.equals(executingWorker.get())) {
            throw new IllegalStateException(
                    "native I/O worker cannot join its own physical retirement");
        }

        QUARANTINED_IO.add(this);
        requestShutdown();

        boolean interrupted = false;
        boolean joined = false;
        try {
            joined = workers.awaitTermination(
                    CLOSE_WAIT_NANOS,
                    TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            interrupted = true;
        }
        if (interrupted) Thread.currentThread().interrupt();

        synchronized (this) {
            joined = joined && pending.isEmpty() && controls.isEmpty();
        }
        if (!joined || interrupted) {
            // Do not close registered resources while a worker may still be
            // inside work.call(). The entire context remains strongly retained.
            throw new IllegalStateException(
                    "native I/O physical cleanup incomplete; resources quarantined");
        }

        synchronized (this) {
            if (resourcesRetired) {
                QUARANTINED_IO.remove(this);
                return;
            }
            if (resourceCloseActive) {
                throw new IllegalStateException(
                        "native I/O resource close cannot recursively close its context");
            }
            resourceCloseActive = true;
        }

        Throwable closeFailure = null;
        List<Map.Entry<AutoCloseable, ResourceRegistration>> closing =
                new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<AutoCloseable, ResourceRegistration> entry
                    : resources.entrySet()) {
                closing.add(new java.util.AbstractMap.SimpleImmutableEntry<>(
                        entry.getKey(), entry.getValue()));
            }
        }

        try {
            for (Map.Entry<AutoCloseable, ResourceRegistration> entry
                    : closing) {
                try {
                    entry.getKey().close();
                    ResourceRegistration retired = null;
                    synchronized (this) {
                        if (resources.get(entry.getKey()) == entry.getValue()) {
                            retired = resources.remove(entry.getKey());
                        }
                    }
                    if (retired != null) {
                        if (retired.permissionLease != null) {
                            retired.permissionLease.close();
                        }
                        if (retired.actorLease != null) {
                            retired.actorLease.retire();
                        }
                    }
                } catch (Exception | Error failure) {
                    // Physical close failure is retryable ownership, not a
                    // successful retirement and not receipt corruption. Keep
                    // the resource and its lease intact for a later close().
                    if (closeFailure == null) closeFailure = failure;
                    else if (closeFailure != failure) {
                        closeFailure.addSuppressed(failure);
                    }
                    if (failure instanceof VirtualMachineError fatal) {
                        throw fatal;
                    }
                    if (failure instanceof ThreadDeath fatal) {
                        throw fatal;
                    }
                    if (failure instanceof LinkageError fatal) {
                        throw fatal;
                    }
                }
            }
        } finally {
            synchronized (this) {
                resourceCloseActive = false;
                if (closeFailure == null && resources.isEmpty()) {
                    resourcesRetired = true;
                }
            }
        }

        synchronized (this) {
            if (!resources.isEmpty() && closeFailure == null) {
                closeFailure = new IllegalStateException(
                        "native I/O resources remain after post-join close sweep");
            }
        }

        if (closeFailure != null) {
            throw new IllegalStateException(
                    "native I/O resource close failed; context quarantined",
                    closeFailure);
        }
        QUARANTINED_IO.remove(this);
    }
}
