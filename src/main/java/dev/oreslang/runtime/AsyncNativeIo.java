package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Context-owned host I/O, never executed on an actor or CONTROL carrier.
 *
 * Every admitted operation holds a physical context loan until its worker has
 * left work.call() AND all terminal Future callbacks have drained. A terminal
 * Future is not the proof of physical retirement. close() interrupts workers,
 * explicitly fails queued work that never started, then joins physical worker
 * exit BEFORE closing registered resources. If joining cannot be proven, the
 * context and resources remain quarantined (strongly reachable), not reclaimed.
 */
public final class AsyncNativeIo implements AutoCloseable {
    private static final long CLOSE_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final int CONTROL_CAPACITY = 16;
    // These are admission limits, not proof of actual OS/native allocation.
    private static final long MAX_ACCOUNTED_REGISTERED_BYTES = 64L * 1024 * 1024;
    // Failed close must not permit the model or GC to reclaim live resource
    // owners after a host releases its last direct reference to this object.
    // The only release path is a subsequent verified, successful close().
    private static final Set<AsyncNativeIo> QUARANTINED_IO =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private enum OperationState {
        ADMITTED, RUNNING, RETIRING, RETIRED, CANCELLED_BEFORE_START
    }

    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor controlWorkers;
    private final Set<Operation<?>> pending = new HashSet<>();
    private final Set<Operation<?>> controls = new HashSet<>();
    private final Set<AutoCloseable> resources =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** Identity, not equals/hashCode: each registered native handle is unique. */
    private final Map<AutoCloseable, ResourceLeaseState> resourceLoans =
            new IdentityHashMap<>();
    /** Open registrations + deferred unregisters still physically loaned. */
    private final int maxRegisteredResources;
    /** Trusted host-supplied attribution; unknown sizes must not count as verified. */
    private long registeredAccountedBytes;
    /** Counts entire I/O operations from admission through callback drain. */
    private final ConcurrentHashMap<ActorRuntime.ActorId, Integer> actorOperations =
            new ConcurrentHashMap<>();
    private volatile Consumer<ActorRuntime.ActorId> actorLoanRetired = ignored -> { };
    private final AtomicLong nextResourceGeneration = new AtomicLong();
    private final AtomicLong nextLoanGeneration = new AtomicLong();
    private final ThreadLocal<ActorRuntime.ActorId> currentOperationActor = new ThreadLocal<>();
    /** A resource unregistered while workers have loans stays reachable. */
    private final Set<AutoCloseable> deferredUnregister =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Object shutdownLock = new Object();
    private final ThreadLocal<Boolean> executingWorker =
            ThreadLocal.withInitial(() -> false);
    private final int capacity;
    private volatile boolean closed;
    private boolean resourcesRetired;
    /** Guarded by shutdownLock; resource.close() may itself call this.close(). */
    private boolean resourceCloseActive;
    /** Published lock-free to actor finalization gates; mutations synchronized. */
    private volatile Throwable incompleteReceipt;

    /** Immutable receipt identity; actorId is null for root/host operations. */
    record IoLoanIdentity(long resourceGeneration, long loanGeneration,
            ActorRuntime.ActorId actorId) { }

    private static final class ResourceLeaseState {
        final long generation;
        final AutoCloseable resource;
        final ActorRuntime.ActorId registeredBy;
        final ActorPermissionScope.ResourceReservation resourceQuota;
        final long accountedBytes;
        int active;
        boolean unregistered;
        ResourceLeaseState(long generation, AutoCloseable resource,
                ActorRuntime.ActorId registeredBy,
                ActorPermissionScope.ResourceReservation resourceQuota, long accountedBytes) {
            this.generation = generation;
            this.resource = resource;
            this.registeredBy = registeredBy;
            this.resourceQuota = resourceQuota;
            this.accountedBytes = accountedBytes;
        }
    }

    /**
     * Physical resource loan. An old generation or repeated close has no
     * authority to retire a new resource registration.
     */
    final class IoLoan implements AutoCloseable {
        private final ResourceLeaseState lease;
        private final IoLoanIdentity identity;
        private final AtomicBoolean released = new AtomicBoolean();

        private IoLoan(ResourceLeaseState lease, IoLoanIdentity identity) {
            this.lease = lease;
            this.identity = identity;
        }

        IoLoanIdentity identity() { return identity; }

        @Override public void close() {
            if (!released.compareAndSet(false, true)) {
                throw new IllegalStateException("duplicate physical I/O loan receipt");
            }
            ActorRuntime.ActorId retireActor;
            ActorRuntime.ActorId retiredRegistration = null;
            synchronized (AsyncNativeIo.this) {
                if (resourceLoans.get(lease.resource) != lease
                        || lease.generation != identity.resourceGeneration()
                        || lease.active <= 0) {
                    recordIncomplete(new IllegalStateException(
                            "stale physical I/O loan generation/underflow"));
                    throw new IllegalStateException(
                            "stale physical I/O loan cannot retire a resource");
                }
                lease.active--;
                if (lease.active == 0 && lease.unregistered) {
                    retiredRegistration = retireRegisteredResourceLocked(lease);
                    resourceLoans.remove(lease.resource);
                    deferredUnregister.remove(lease.resource);
                }
                retireActor = decrementActorOperationLocked(identity.actorId());
                AsyncNativeIo.this.notifyAll();
            }
            notifyActorRetirement(retiredRegistration);
            notifyActorRetirement(retireActor);
        }
    }

    /**
     * Acquire a loan before touching a registered native resource. The worker
     * incarnation is captured when I/O is admitted on its actor carrier,
     * then restored on the host I/O worker; it is not inferred from threads.
     */
    synchronized IoLoan borrow(AutoCloseable resource) {
        ResourceLeaseState state = resourceLoans.get(Objects.requireNonNull(resource));
        if (closed || state == null || state.unregistered) {
            throw new RejectedExecutionException(
                    "native resource is closed, unregistered or shutdown-sealed");
        }
        // Enforce revocation at the actual physical-loan boundary, including
        // calls from a worker whose earlier admission captured this actor.
        ActorRuntime.requireCurrentActorAuthorityActive("borrow native I/O resource");
        ActorRuntime.ActorId actorId = currentOwnerActor();
        state.active++;
        incrementActorOperationLocked(actorId);
        return new IoLoan(state, new IoLoanIdentity(
                state.generation, nextLoanGeneration.incrementAndGet(), actorId));
    }

    private ActorRuntime.ActorId retireRegisteredResourceLocked(ResourceLeaseState lease) {
        // AsyncNativeIo monitor held; fail closed before dropping physical reachability.
        if (lease.active != 0) {
            recordIncomplete(new IllegalStateException(
                    "registered native resource retired with active loans"));
            throw new IllegalStateException("physical resource loan still active");
        }
        if (registeredAccountedBytes < lease.accountedBytes) {
            recordIncomplete(new IllegalStateException(
                    "registered native resource byte accounting underflow"));
            throw new IllegalStateException("registered native resource byte underflow");
        }
        try {
            if (lease.resourceQuota != null) lease.resourceQuota.close();
        } catch (RuntimeException badReceipt) {
            recordIncomplete(badReceipt);
            throw badReceipt;
        }
        registeredAccountedBytes -= lease.accountedBytes;
        return decrementActorOperationLocked(lease.registeredBy);
    }

    synchronized int registeredResourceCount() { return resourceLoans.size(); }

    synchronized long registeredAccountedBytes() { return registeredAccountedBytes; }

    int outstandingActorOperations(ActorRuntime.ActorId id) {
        // Called under ActorCell.lifecycleLock. NEVER acquire the AsyncNativeIo
        // monitor here: rejected Futures can deliver host callbacks while the
        // AsyncNativeIo monitor is held by the admitting actor.
        return actorOperations.getOrDefault(Objects.requireNonNull(id), 0);
    }

    boolean actorPhysicallyRetired(ActorRuntime.ActorId id) {
        // A failed native completion receipt can invalidate retirement even
        // after its count reaches zero. Quarantine all affected context heaps
        // until the physical failure has been independently reconciled.
        return incompleteReceipt == null && outstandingActorOperations(id) == 0;
    }

    void setActorLoanRetiredListener(Consumer<ActorRuntime.ActorId> callback) {
        actorLoanRetired = Objects.requireNonNull(callback);
    }

    private void incrementActorOperationLocked(ActorRuntime.ActorId id) {
        if (id != null) actorOperations.merge(id, 1, Integer::sum);
    }

    private ActorRuntime.ActorId decrementActorOperationLocked(ActorRuntime.ActorId id) {
        if (id == null) return null;
        AtomicBoolean finalLoan = new AtomicBoolean();
        AtomicBoolean underflow = new AtomicBoolean();
        actorOperations.compute(id, (ignored, count) -> {
            if (count == null || count <= 0) {
                underflow.set(true);
                return null;
            }
            if (count == 1) {
                finalLoan.set(true);
                return null;
            }
            return count - 1;
        });
        if (underflow.get()) recordIncomplete(new IllegalStateException(
                "actor native I/O operation receipt underflow"));
        return finalLoan.get() ? id : null;
    }

    private void notifyActorRetirement(ActorRuntime.ActorId id) {
        if (id != null) {
            try {
                actorLoanRetired.accept(id);
            } catch (RuntimeException | Error notifyFailure) {
                recordIncomplete(notifyFailure);
            }
        }
    }

    public AsyncNativeIo() { this(128); }

    AsyncNativeIo(int capacity) {
        this(capacity, Math.min(capacity, 32), capacity);
    }

    /** Bounded deterministic worker and queue configuration for test coverage. */
    AsyncNativeIo(int capacity, int workerCount, int queueSize) {
        this(capacity, workerCount, queueSize, Math.max(64, Math.min(capacity, 65_536)));
    }

    /** Explicit host ceiling for registered resources, including deferred loans. */
    AsyncNativeIo(int capacity, int workerCount, int queueSize, int maxRegisteredResources) {
        if (capacity < 1 || workerCount < 1 || queueSize < 1
                || workerCount > capacity || maxRegisteredResources < 1
                || maxRegisteredResources > 65_536) {
            throw new IllegalArgumentException("invalid native I/O worker capacity");
        }
        this.capacity = capacity;
        this.maxRegisteredResources = maxRegisteredResources;
        workers = new ThreadPoolExecutor(workerCount, workerCount, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueSize),
                Thread.ofVirtual().name("ores-native-io-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        controlWorkers = new ThreadPoolExecutor(CONTROL_CAPACITY, CONTROL_CAPACITY, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(CONTROL_CAPACITY),
                Thread.ofVirtual().name("ores-native-io-control-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> OresFuture<T> submit(OresContext context,
            RuntimePermissions.Permission permission, String operation, Callable<T> work) {
        context.requireCapability(permission.capability(), operation);
        return submit(operation, work);
    }

    public <T> OresFuture<T> submitClose(
            OresContext context, String operation, Callable<T> work) {
        context.requireCapability(IsolatePolicy.Capability.NETWORK, operation);
        return submitControl(operation, work);
    }

    synchronized <T> OresFuture<T> submitControl(String operation, Callable<T> work) {
        return admit(operation, work, controls, CONTROL_CAPACITY, controlWorkers);
    }

    synchronized <T> OresFuture<T> submit(String operation, Callable<T> work) {
        return admit(operation, work, pending, capacity, workers);
    }

    private <T> OresFuture<T> admit(String operation, Callable<T> work,
            Set<Operation<?>> admitted, int limit, ThreadPoolExecutor executor) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(work);
        OresFuture<T> result = new OresFuture<>(() -> false, () -> { });
        if (closed || incompleteReceipt != null || admitted.size() >= limit) {
            CoreLog.record(CoreLog.Level.WARN, CoreLog.ASYNC_IO_REJECTED, 1L);
            result.failFromRuntime(new RejectedExecutionException(
                    closed ? "I/O context closed"
                            : incompleteReceipt != null ? "I/O receipt incomplete: context quarantined"
                            : "I/O admission capacity exhausted"));
            return result;
        }

        // Capture actor authorization at admission, not on the worker.
        // Nested native-I/O admissions inherit the same immutable chain.
        ActorRuntime.NativeIoAuthority ioAuthority =
                ActorRuntime.captureNativeIoAuthority();
        ActorRuntime.ActorId admittedActor = ioAuthority == null
                ? ActorRuntime.currentActorId().orElse(null)
                : ioAuthority.actorId();
        ActorPermissionScope.IoReservation ioReservation;
        try {
            ioReservation = ioAuthority == null ? null : ioAuthority.reserveIo(operation);
        } catch (SecurityException | RejectedExecutionException rejected) {
            CoreLog.record(CoreLog.Level.WARN, CoreLog.ASYNC_IO_REJECTED, 1L);
            result.failFromRuntime(rejected);
            return result;
        }
        Operation<T> op = new Operation<>(
                operation, work, result, admitted, admittedActor, ioAuthority, ioReservation);
        admitted.add(op); // Reserve before publishing work to any executor.
        incrementActorOperationLocked(op.admittedActor);
        long queuedAt = CorePerf.start();
        op.queuedAt = queuedAt;
        try {
            executor.execute(op);
        } catch (RuntimeException failure) {
            // Rejected admission never owns a worker or physical resource.
            admitted.remove(op);
            decrementActorOperationLocked(op.admittedActor);
            if (ioReservation != null) ioReservation.close();
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_REJECTED, 1L);
            result.failFromRuntime(failure);
        }
        return result;
    }

    private final class Operation<T> implements Runnable {
        private final String operation;
        private final Callable<T> work;
        private final OresFuture<T> completion;
        private final Set<Operation<?>> owner;
        private final ActorRuntime.ActorId admittedActor;
        private final ActorRuntime.NativeIoAuthority ioAuthority;
        private final ActorPermissionScope.IoReservation ioReservation;
        private final AtomicReference<OperationState> state =
                new AtomicReference<>(OperationState.ADMITTED);
        private long queuedAt;

        private Operation(String operation, Callable<T> work, OresFuture<T> completion,
                Set<Operation<?>> owner, ActorRuntime.ActorId admittedActor,
                ActorRuntime.NativeIoAuthority ioAuthority,
                ActorPermissionScope.IoReservation ioReservation) {
            this.ioReservation = ioReservation;
            this.operation = operation;
            this.work = work;
            this.completion = completion;
            this.owner = owner;
            this.admittedActor = admittedActor;
            this.ioAuthority = ioAuthority;
        }

        /** Failed quota receipts quarantine the I/O context, never silently free credits. */
        private void retireIoBudget() {
            if (ioReservation == null) return;
            try {
                ioReservation.close();
            } catch (RuntimeException invalidReceipt) {
                recordIncomplete(invalidReceipt);
                throw invalidReceipt;
            }
        }

        @Override public void run() {
            if (!state.compareAndSet(OperationState.ADMITTED, OperationState.RUNNING)) return;
            executingWorker.set(true);
            CorePerf.end(CorePerf.ASYNC_IO_QUEUE, queuedAt);
            long workPerf = CorePerf.start();
            T value = null;
            Throwable failure = null;
            boolean authorityEntered = false;
            try {
                if (admittedActor != null) currentOperationActor.set(admittedActor);
                ActorRuntime.enterNativeIoAuthority(ioAuthority);
                authorityEntered = ioAuthority != null;
                // A queued operation may start long after its actor's
                // delegation was revoked. Captured authority is not a lease
                // exempt from revocation: fail before executing work.call().
                // An OS syscall that has already started still requires
                // separate physical retirement/fencing semantics.
                if (ioAuthority != null) ioAuthority.requireActive(operation);
                value = work.call();
            } catch (Throwable caught) {
                failure = caught;
                CoreLog.record(CoreLog.Level.ERROR, CoreLog.ASYNC_IO_FAILED, 1L);
                SourceBoundaryTrace.record(caught, "native:io", operation, "I/O");
            } finally {
                CorePerf.end(CorePerf.ASYNC_IO_WORK, workPerf);
            }

            state.set(OperationState.RETIRING);
            try {
                // close() must not settle a RUNNING Future while its native
                // operation still holds context resources. Settlement here
                // occurs after work.call(), on the physical owning worker.
                if (closed) completion.failFromRuntime(
                        new IllegalStateException("I/O context closed"));
                else if (failure != null) completion.failFromRuntime(failure);
                else completion.completeFromRuntime(value);
            } catch (RuntimeException | Error notificationFailure) {
                recordIncomplete(notificationFailure);
                throw notificationFailure;
            } finally {
                state.set(OperationState.RETIRED);
                ActorRuntime.ActorId retireActor;
                try {
                    synchronized (AsyncNativeIo.this) {
                        owner.remove(this);
                        // Release only after work and terminal callbacks have retired.
                        retireIoBudget();
                        retireActor = decrementActorOperationLocked(admittedActor);
                        retireDeferredUnregisterLocked();
                        AsyncNativeIo.this.notifyAll();
                    }
                } finally {
                    try {
                        if (authorityEntered) ActorRuntime.exitNativeIoAuthority(ioAuthority);
                    } finally {
                        currentOperationActor.remove();
                        executingWorker.remove();
                    }
                }
                notifyActorRetirement(retireActor);
            }
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            if (failure instanceof LinkageError fatal) throw fatal;
        }

        private void cancelledBeforeStart(Throwable failure) {
            if (!state.compareAndSet(OperationState.ADMITTED,
                    OperationState.CANCELLED_BEFORE_START)) return;
            // A Future terminal subscriber may block indefinitely. Deliver
            // never-started cancellation from a detached host notification
            // carrier, never on the close() caller or CONTROL thread. At most
            // capacity + CONTROL_CAPACITY such notifications can be admitted.
            try {
                Thread.ofVirtual().name("ores-native-io-discard-").start(() -> {
                    boolean entered = false;
                    try {
                        // Cancellation callbacks are still part of the admitted
                        // operation: never let them execute with root authority.
                        ActorRuntime.enterNativeIoAuthority(ioAuthority);
                        entered = ioAuthority != null;
                        completion.failFromRuntime(failure);
                    } catch (RuntimeException | Error notificationFailure) {
                        recordIncomplete(notificationFailure);
                    } finally {
                        ActorRuntime.ActorId retireActor;
                        try {
                            synchronized (AsyncNativeIo.this) {
                                owner.remove(this);
                                // Release only after work and terminal callbacks have retired.
                                retireIoBudget();
                                retireActor = decrementActorOperationLocked(admittedActor);
                                retireDeferredUnregisterLocked();
                                AsyncNativeIo.this.notifyAll();
                            }
                        } finally {
                            if (entered) ActorRuntime.exitNativeIoAuthority(ioAuthority);
                        }
                        // Runtime lifecycle notifications are host control work,
                        // never executed while carrying an actor's I/O-only scope.
                        notifyActorRetirement(retireActor);
                    }
                });
            } catch (RuntimeException | Error startFailure) {
                // No notification occurred. Preserve this operation in owner
                // and quarantine the context rather than forging retirement.
                recordIncomplete(startFailure);
            }
        }
    }

    private synchronized void recordIncomplete(Throwable failure) {
        if (incompleteReceipt == null) incompleteReceipt = failure;
        else if (incompleteReceipt != failure) incompleteReceipt.addSuppressed(failure);
    }

    private void retireDeferredUnregisterLocked() {
        // Existing socket close/unregister may race another active read.
        // Keep the resource strongly reachable until every context loan ends.
        if (pending.isEmpty() && controls.isEmpty()) {
            deferredUnregister.removeIf(resource -> {
                ResourceLeaseState state = resourceLoans.get(resource);
                return state == null || state.active == 0;
            });
        }
    }

    private ActorRuntime.ActorId currentOwnerActor() {
        ActorRuntime.ActorId id = currentOperationActor.get();
        return id != null ? id : ActorRuntime.currentActorId().orElse(null);
    }

    void register(AutoCloseable resource) throws Exception {
        register(resource, 0L);
    }

    synchronized void register(AutoCloseable resource, long accountedBytes) throws Exception {
        Objects.requireNonNull(resource);
        if (accountedBytes < 0 || accountedBytes > MAX_ACCOUNTED_REGISTERED_BYTES)
            throw new IllegalArgumentException("invalid native resource byte attribution");
        if (resourceLoans.containsKey(resource)) {
            throw new IllegalStateException(
                    "duplicate registration or stale native resource generation");
        }
        if (closed) {
            if (resourcesRetired) {
                // The full worker and resource join already completed.
                // There is no remaining physical owner capable of sweeping
                // newly added handles. Reject the transfer outright: the
                // caller still owns this unregistered resource and must close
                // it. Never add it to an already-retired context.
                throw new IllegalStateException(
                        "I/O context fully retired; caller retains resource ownership");
            }
            // A RUNNING connect may create a resource after close() sealed
            // admission. Closing it on this worker/monitor would release a
            // native handle before all context loans have exited. Retain it
            // for the post-join resource sweep and reject publication.
            // An OS handle may already have been created by an admitted
            // operation before STOP. Retain it for quarantine even if this
            // crosses a quota; it is too late to reject physical creation.
            if (resourceLoans.size() >= maxRegisteredResources
                    || accountedBytes > MAX_ACCOUNTED_REGISTERED_BYTES - registeredAccountedBytes) {
                recordIncomplete(new IllegalStateException(
                        "late native resource exceeded registration budget; quarantined"));
            }
            ActorRuntime.ActorId owner = currentOwnerActor();
            resources.add(resource);
            resourceLoans.put(resource, new ResourceLeaseState(
                    nextResourceGeneration.incrementAndGet(), resource,
                    owner, null, accountedBytes));
            registeredAccountedBytes += accountedBytes;
            incrementActorOperationLocked(owner);
            throw new IllegalStateException("I/O context closed");
        }
        // Check AFTER the closed-path quarantine branch: a resource created
        // by an already-running operation during shutdown must remain tracked
        // until physical retirement, even if its delegation was revoked.
        // In the normal open path, reject revoked actor/worker authority
        // before publishing a new native resource to the context.
        ActorRuntime.requireCurrentActorAuthorityActive("register native I/O resource");
        if (resourceLoans.size() >= maxRegisteredResources
                || accountedBytes > MAX_ACCOUNTED_REGISTERED_BYTES - registeredAccountedBytes) {
            throw new RejectedExecutionException(
                    "native registered resource handle/byte admission capacity exhausted");
        }
        ActorRuntime.NativeIoAuthority authority = ActorRuntime.captureNativeIoAuthority();
        Runnable publish = () -> {
            // Only trusted ownership bookkeeping (no guest/FFI callbacks)
            // may run inside the actor lifecycle monitor. The identity maps
            // can still allocate; this is not a hard-real-time allocation-free path.
            // STOP cannot see zero loans
            // between admission and publication of this registered handle.
            ActorPermissionScope.ResourceReservation quota = authority == null
                    ? null : authority.reserveResource(
                            accountedBytes, "register native resource");
            ActorRuntime.ActorId owner = currentOwnerActor();
            resources.add(resource);
            resourceLoans.put(resource, new ResourceLeaseState(
                    nextResourceGeneration.incrementAndGet(), resource,
                    owner, quota, accountedBytes));
            registeredAccountedBytes += accountedBytes;
            incrementActorOperationLocked(owner);
        };
        if (authority == null) publish.run();
        else authority.withLiveIncarnation("register native resource", publish);
    }

    /**
     * Trusted host-side ownership transfer. A resource with active loans stays
     * charged until the final loan retires. The caller explicitly assumes
     * ownership of the unregistered handle and must close it separately.
     */
    void unregister(AutoCloseable resource) {
        ActorRuntime.ActorId retired = null;
        synchronized (this) {
            ResourceLeaseState state = resourceLoans.get(resource);
            if (state == null || state.unregistered) return;
            state.unregistered = true;
            resources.remove(resource);
            if (state.active != 0) {
                deferredUnregister.add(resource);
            } else {
                retired = retireRegisteredResourceLocked(state);
                resourceLoans.remove(resource);
            }
        }
        notifyActorRetirement(retired);
    }

    private void reportDiscarded(List<Runnable> discarded) {
        Throwable callbackFailure = null;
        for (Runnable runnable : discarded) {
            try {
                if (!(runnable instanceof AsyncNativeIo.Operation<?> op)) {
                    throw new IllegalStateException("untracked native I/O queue entry discarded");
                }
                op.cancelledBeforeStart(new RejectedExecutionException(
                        "I/O worker cancelled before start by context close"));
            } catch (RuntimeException | Error failure) {
                if (callbackFailure == null) callbackFailure = failure;
                else if (callbackFailure != failure) callbackFailure.addSuppressed(failure);
            }
        }
        if (callbackFailure != null) recordIncomplete(callbackFailure);
    }

    /**
     * Phase one: seal admissions and revoke transport handles to unblock
     * outstanding reads. A Socket.close() here is ONLY a wakeup request; no
     * resource lease, callback receipt or actor arena may be reclaimed here.
     *
     * The phase can be requested separately from close()/retire() and is
     * idempotent. Generic AutoCloseables are NOT forcibly closed while active
     * because arbitrary close callbacks may free memory prematurely.
     */
    void requestShutdown() {
        synchronized (shutdownLock) {
            final boolean first;
            final List<java.net.Socket> sockets = new ArrayList<>();
            synchronized (this) {
                first = !closed;
                closed = true;
                if (first) {
                    for (AutoCloseable resource : resources) {
                        if (resource instanceof java.net.Socket socket) sockets.add(socket);
                    }
                    for (AutoCloseable resource : deferredUnregister) {
                        if (resource instanceof java.net.Socket socket) sockets.add(socket);
                    }
                }
            }
            if (!first) return;
            reportDiscarded(workers.shutdownNow());
            reportDiscarded(controlWorkers.shutdownNow());
            for (java.net.Socket socket : sockets) {
                try {
                    socket.close(); // unblock only; physical loans still own state
                } catch (java.io.IOException wakeFailure) {
                    recordIncomplete(wakeFailure);
                }
            }
        }
    }

    @Override public void close() {
        if (Boolean.TRUE.equals(executingWorker.get())) {
            throw new IllegalStateException(
                    "native I/O worker cannot join its own physical retirement");
        }
        synchronized (shutdownLock) {
            // Java monitors are reentrant: an AutoCloseable callback can
            // recursively enter close() on this SAME thread while it is still
            // in the post-join resource sweep. Without this guard the same
            // resource is closed again (possibly until stack overflow).
            // Fail closed before changing resource ownership; outer close()
            // keeps an uncaught failure quarantined and retryable.
            if (resourceCloseActive) {
                throw new IllegalStateException(
                        "native I/O resource close cannot recursively close its context");
            }
            QUARANTINED_IO.add(this);
            requestShutdown();

            long deadline = System.nanoTime() + CLOSE_WAIT_NANOS;
            boolean interrupted = false;
            boolean joined = false;
            try {
                long wait = Math.max(0L, deadline - System.nanoTime());
                boolean dataDone = workers.awaitTermination(wait, TimeUnit.NANOSECONDS);
                wait = Math.max(0L, deadline - System.nanoTime());
                boolean controlDone = controlWorkers.awaitTermination(wait, TimeUnit.NANOSECONDS);
                if (dataDone && controlDone) {
                    synchronized (this) {
                        // shutdownNow() may have produced bounded independent
                        // never-started notification tasks. Those also own
                        // physical callback receipts until their last terminal
                        // subscriber has returned. Wait only to the deadline.
                        while ((!pending.isEmpty() || !controls.isEmpty()
                                || resourceLoans.values().stream().anyMatch(x -> x.active != 0))
                                && incompleteReceipt == null) {
                            wait = deadline - System.nanoTime();
                            if (wait <= 0) break;
                            TimeUnit.NANOSECONDS.timedWait(this, wait);
                        }
                        joined = pending.isEmpty() && controls.isEmpty()
                                && resourceLoans.values().stream().noneMatch(x -> x.active != 0)
                                && incompleteReceipt == null;
                    }
                }
            } catch (InterruptedException failure) {
                interrupted = true;
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (!joined) {
                // NEVER clear resource ownership, settle running Futures, or
                // publish successful retirement when a physical worker is live.
                throw new IllegalStateException(
                        "native I/O context physical cleanup incomplete; resources quarantined",
                        incompleteReceipt);
            }

            List<AutoCloseable> closing;
            synchronized (this) {
                if (resourcesRetired) {
                    QUARANTINED_IO.remove(this);
                    return;
                }
                closing = new ArrayList<>(resources);
            }
            Throwable closeFailure = null;
            resourceCloseActive = true;
            try {
                for (AutoCloseable resource : closing) {
                    try {
                        resource.close();
                        ActorRuntime.ActorId retired = null;
                        synchronized (this) {
                            ResourceLeaseState lease = resourceLoans.get(resource);
                            if (lease != null) {
                                retired = retireRegisteredResourceLocked(lease);
                                resourceLoans.remove(resource);
                                deferredUnregister.remove(resource);
                            }
                            resources.remove(resource);
                        }
                        notifyActorRetirement(retired);
                    } catch (Exception failure) {
                        if (closeFailure == null) closeFailure = failure;
                        else if (closeFailure != failure) closeFailure.addSuppressed(failure);
                    }
                }
            } finally {
                // Even a faulty or throwing resource callback cannot leave
                // the reentrancy fence permanently armed. Failed resources
                // remain owned and may be closed on a later explicit retry.
                resourceCloseActive = false;
            }
            synchronized (this) {
                if (!resources.isEmpty()) {
                    // A resource-close callback (or a concurrent late
                    // connector) can register another handle AFTER the
                    // closing snapshot. register() retains this late handle
                    // and rejects publication, but its caller can catch the
                    // rejection. Never remove the context from quarantine or
                    // report clean close while that new resource remains.
                    IllegalStateException retained = new IllegalStateException(
                            "native I/O resources remain after post-join close sweep");
                    if (closeFailure == null) closeFailure = retained;
                    else closeFailure.addSuppressed(retained);
                } else if (closeFailure == null) {
                    resourcesRetired = true;
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
}
