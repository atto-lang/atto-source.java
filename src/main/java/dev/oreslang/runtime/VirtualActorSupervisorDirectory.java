package dev.oreslang.runtime;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host-only virtual actor directory scoped to one real ActorRuntime supervisor.
 *
 * Logical identities are bound only to live workers managed by this exact
 * supervisor's ActorGroup. Inspection is dispatched through the runtime's
 * injected CONTROL dispatcher. This is still a local host adapter: it does
 * not create a guest-visible reference, persist state, route messages, or
 * implement distributed activation/lease consensus.
 */
public final class VirtualActorSupervisorDirectory implements AutoCloseable {
    public static final int MAX_PENDING_INSPECTIONS = 8;
    public static final long MAX_INSPECTION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    public enum Lifecycle {
        NEVER_ACTIVATED, ACTIVATING, ACTIVE, TERMINATION_PENDING_RECONCILIATION,
        PASSIVATED, CLOSED
    }

    public record Snapshot(int schemaVersion,
                           VirtualActorActivationRegistry.Identity identity,
                           String address,
                           ActorRuntime.SupervisorId supervisorId,
                           Lifecycle lifecycle,
                           long activationGeneration,
                           Optional<ActorRuntime.ActorId> actorId,
                           long actorReferenceGeneration,
                           Optional<ActorRuntime.ActorKind> actorKind,
                           int outstandingAdmissions,
                           Instant observedAt,
                           boolean partial) {
        public Snapshot {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(supervisorId, "supervisorId");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(actorKind, "actorKind");
            Objects.requireNonNull(observedAt, "observedAt");
            if (schemaVersion != 1 || activationGeneration < 0
                    || actorReferenceGeneration < 0 || outstandingAdmissions < 0)
                throw new IllegalArgumentException("invalid virtual actor supervisor snapshot");
        }
    }

    /** Host-minted, session-bound read grant. It is not serializable or guest-transferable. */
    public static final class Grant {
        private final VirtualActorSupervisorDirectory owner;
        private final Object session;
        private final String tenant;
        private final long issuedAt;
        private final long ttlNanos;
        private final AtomicBoolean revoked = new AtomicBoolean();
        private final AtomicBoolean reading = new AtomicBoolean();

        private Grant(VirtualActorSupervisorDirectory owner, Object session,
                      String tenant, long issuedAt, long ttlNanos) {
            this.owner = owner;
            this.session = session;
            this.tenant = tenant;
            this.issuedAt = issuedAt;
            this.ttlNanos = ttlNanos;
        }
    }

    private record Binding(ActorRuntime.ActorRef<?> actor,
                           VirtualActorActivationRegistry.Activation activation) { }

    private final ActorRuntime runtime;
    private final ActorRuntime.ActorSupervisor supervisor;
    private final String tenant;
    private final VirtualActorActivationRegistry registry;
    private final VirtualActorDirectoryInspection localInspection;
    private final Object stateLock = new Object();
    private final Map<VirtualActorActivationRegistry.Identity, Binding> bindings = new HashMap<>();
    private final AtomicInteger pendingInspections = new AtomicInteger();
    private volatile boolean closed;

    public VirtualActorSupervisorDirectory(ActorRuntime runtime,
                                           ActorRuntime.ActorSupervisor supervisor,
                                           String tenant,
                                           int maxIdentities,
                                           int mailboxCapacity) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.tenant = requireText(tenant, "tenant", 80);
        Map<ActorRuntime.SupervisorId, ActorRuntime.ActorSupervisor> registered =
                runtime.supervisorRegistryForObservation();
        if (registered.get(supervisor.id()) != supervisor)
            throw new SecurityException("supervisor does not belong to this ActorRuntime");
        String domain = this.tenant + "@" + supervisor.id().value();
        if (domain.length() > 128)
            throw new IllegalArgumentException("tenant/supervisor domain exceeds identity budget");
        this.registry = new VirtualActorActivationRegistry(domain, maxIdentities, mailboxCapacity);
        this.localInspection = new VirtualActorDirectoryInspection(registry);
    }

    private static String requireText(String value, String name, int max) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > max)
            throw new IllegalArgumentException("invalid " + name);
        return value;
    }

    /** Stable logical lookup. This deliberately does not activate the actor. */
    public VirtualActorActivationRegistry.LogicalRef resolve(String declaration, String key) {
        runtime.supervisorRegistryForObservation(); // host-only caller/affinity guard
        synchronized (stateLock) {
            if (closed) throw new IllegalStateException("virtual actor supervisor directory closed");
            return registry.resolve(declaration, key);
        }
    }

    /**
     * Bind a real actor worker as this logical virtual actor's activation.
     * The worker must be a managed child of this exact supervisor. It is never
     * enough that it merely belongs to a cooperative ActorGroup.
     */
    public long bindActivation(VirtualActorActivationRegistry.LogicalRef logical,
                               ActorRuntime.ActorRef<?> actor) {
        Objects.requireNonNull(logical, "logical");
        Objects.requireNonNull(actor, "actor");
        runtime.supervisorRegistryForObservation();
        if (!runtime.actorManagedBySupervisor(actor, supervisor))
            throw new SecurityException("activation worker is not managed by this supervisor");
        if (!actor.getReady().isDone() || !actor.isAlive())
            throw new IllegalStateException("cannot bind a terminated actor activation");
        synchronized (stateLock) {
            if (closed) throw new IllegalStateException("virtual actor supervisor directory closed");
            VirtualActorActivationRegistry.Activation activation = registry.activate(logical);
            if (!activation.initializer())
                throw new IllegalStateException("logical virtual actor already has an activation");
            Binding binding = new Binding(actor, activation);
            try {
                if (bindings.putIfAbsent(logical.identity(), binding) != null)
                    throw new IllegalStateException("logical virtual actor activation already bound");
                registry.publish(activation);
            } catch (RuntimeException | Error failure) {
                bindings.remove(logical.identity(), binding);
                try { registry.abort(activation); }
                catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
            long generation = activation.incarnation();
            // done() settles only after finalization and physical TurnExecutor exit.
            // The callback only changes trusted metadata; it never runs guest code.
            actor.done().whenCompleteRuntime((ignored, failure) ->
                    retireAfterPhysicalCompletion(logical.identity(), binding));
            return generation;
        }
    }

    private void retireAfterPhysicalCompletion(
            VirtualActorActivationRegistry.Identity identity, Binding expected) {
        synchronized (stateLock) {
            if (closed || bindings.get(identity) != expected) return;
            try {
                registry.deactivate(expected.activation());
                bindings.remove(identity, expected);
            } catch (RuntimeException | Error failure) {
                // Retain the binding as a quarantined record for explicit
                // reconciliation; never report passivation if accounting failed.
            }
        }
    }

    /** Create a short-lived read-only grant for an authenticated host session. */
    public Grant issueInspectionGrant(Object authenticatedSession, long ttlNanos) {
        runtime.supervisorRegistryForObservation();
        Objects.requireNonNull(authenticatedSession, "authenticatedSession");
        if (closed) throw new IllegalStateException("virtual actor supervisor directory closed");
        if (ttlNanos <= 0 || ttlNanos > MAX_INSPECTION_TIMEOUT_NANOS)
            throw new IllegalArgumentException("inspection grant TTL out of range");
        return new Grant(this, authenticatedSession, tenant, System.nanoTime(), ttlNanos);
    }

    public void revoke(Grant grant) {
        runtime.supervisorRegistryForObservation();
        if (grant == null || grant.owner != this)
            throw new SecurityException("foreign virtual actor inspection grant");
        grant.revoked.set(true);
    }

    private void requireGrant(Grant grant, Object session) {
        if (closed || grant == null || grant.owner != this || grant.session != session
                || !tenant.equals(grant.tenant) || grant.revoked.get())
            throw new SecurityException("virtual actor inspection denied");
        long elapsed = System.nanoTime() - grant.issuedAt;
        if (elapsed < 0 || elapsed >= grant.ttlNanos)
            throw new SecurityException("virtual actor inspection grant expired");
    }

    public CompletionStage<Optional<Snapshot>> requestInspect(
            Grant grant, Object authenticatedSession,
            VirtualActorActivationRegistry.Identity identity,
            long timeoutNanos) {
        Objects.requireNonNull(identity, "identity");
        if (!identity.domain().equals(tenant + "@" + supervisor.id().value()))
            throw new SecurityException("virtual actor identity outside supervisor scope");
        return request(grant, authenticatedSession, timeoutNanos,
                () -> localInspection.inspect(identity).map(this::enrich));
    }

    public CompletionStage<Optional<Snapshot>> requestInspectAddress(
            Grant grant, Object authenticatedSession, String address,
            long timeoutNanos) {
        VirtualActorActivationRegistry.Identity identity = VirtualActorAddressV1.decode(address);
        return requestInspect(grant, authenticatedSession, identity, timeoutNanos);
    }

    public CompletionStage<Snapshot> requestInspect(
            Grant grant, Object authenticatedSession,
            VirtualActorActivationRegistry.LogicalRef logical,
            long timeoutNanos) {
        Objects.requireNonNull(logical, "logical");
        if (!logical.identity().domain().equals(tenant + "@" + supervisor.id().value()))
            throw new SecurityException("virtual actor reference outside supervisor scope");
        return request(grant, authenticatedSession, timeoutNanos,
                () -> Optional.of(enrich(localInspection.inspect(logical))));
    }

    private Snapshot enrich(VirtualActorDirectoryInspection.Snapshot snapshot) {
        synchronized (stateLock) {
            Binding binding = bindings.get(snapshot.identity());
            Optional<ActorRuntime.ActorId> actorId = Optional.empty();
            Optional<ActorRuntime.ActorKind> actorKind = Optional.empty();
            long actorGeneration = 0L;
            Lifecycle lifecycle = switch (snapshot.lifecycle()) {
                case NEVER_ACTIVATED -> Lifecycle.NEVER_ACTIVATED;
                case ACTIVATING -> Lifecycle.ACTIVATING;
                case ACTIVE -> Lifecycle.ACTIVE;
                case PASSIVATED -> Lifecycle.PASSIVATED;
                case CLOSED -> Lifecycle.CLOSED;
            };
            if (binding != null) {
                actorId = Optional.of(binding.actor().id());
                actorKind = Optional.of(binding.actor().kind());
                actorGeneration = binding.actor().generation();
                if (!binding.actor().isAlive())
                    lifecycle = Lifecycle.TERMINATION_PENDING_RECONCILIATION;
            }
            return new Snapshot(1, snapshot.identity(), snapshot.address(), supervisor.id(),
                    lifecycle, snapshot.activationGeneration(), actorId, actorGeneration,
                    actorKind, snapshot.outstandingAdmissions(), snapshot.observedAt(), true);
        }
    }

    private <T> CompletionStage<T> request(
            Grant grant, Object session, long timeoutNanos,
            java.util.function.Supplier<T> capture) {
        runtime.supervisorRegistryForObservation();
        requireGrant(grant, session);
        if (timeoutNanos <= 0 || timeoutNanos > MAX_INSPECTION_TIMEOUT_NANOS)
            throw new IllegalArgumentException("inspection timeout out of range");
        if (!grant.reading.compareAndSet(false, true))
            throw new IllegalStateException("inspection grant already has an outstanding request");
        if (pendingInspections.incrementAndGet() > MAX_PENDING_INSPECTIONS) {
            pendingInspections.decrementAndGet();
            grant.reading.set(false);
            throw new IllegalStateException("virtual actor inspection capacity exhausted");
        }
        Thread caller = Thread.currentThread();
        CompletableFuture<T> result = new CompletableFuture<>();
        result.orTimeout(timeoutNanos, TimeUnit.NANOSECONDS);
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                grant.reading.set(false);
                pendingInspections.decrementAndGet();
            }
        };
        final OresFuture<Void> dispatch;
        try {
            dispatch = runtime.dispatchSupervisorObservationControl(() -> {
                if (!started.compareAndSet(false, true)) return;
                try {
                    if (Thread.currentThread() == caller)
                        throw new IllegalStateException("INLINE_CONTROL_DISPATCH_FORBIDDEN");
                    requireGrant(grant, session);
                    if (!result.isDone()) result.complete(capture.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    release.run();
                }
            });
        } catch (RuntimeException | Error failure) {
            result.completeExceptionally(failure);
            release.run();
            return result.minimalCompletionStage();
        }
        dispatch.whenCompleteRuntime((ignored, failure) -> {
            if (failure != null) {
                result.completeExceptionally(new IllegalStateException("CONTROL_DISPATCH_FAILED"));
                if (!started.get()) release.run();
            }
        });
        return result.minimalCompletionStage();
    }

    public int pendingInspectionCount() {
        runtime.supervisorRegistryForObservation();
        return pendingInspections.get();
    }

    @Override public void close() {
        runtime.supervisorRegistryForObservation();
        synchronized (stateLock) {
            if (closed) return;
            if (pendingInspections.get() != 0)
                throw new IllegalStateException("cannot close with pending CONTROL inspections");
            if (!bindings.isEmpty())
                throw new IllegalStateException("cannot close while supervised activations remain bound");
            registry.close();
            closed = true;
        }
    }
}
