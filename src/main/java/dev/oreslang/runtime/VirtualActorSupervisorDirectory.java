package dev.oreslang.runtime;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Base64;
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
        PASSIVATED, FAILED, CANCELLED, CLOSED
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
                           Optional<String> lastTerminalFailureClass,
                           Optional<Instant> lastTerminalAt,
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
            Objects.requireNonNull(lastTerminalFailureClass, "lastTerminalFailureClass");
            Objects.requireNonNull(lastTerminalAt, "lastTerminalAt");
            if (lastTerminalFailureClass.filter(value -> value.isBlank() || value.length() > 256).isPresent())
                throw new IllegalArgumentException("invalid terminal failure class");
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

    /** Stable storage key data; it is not a persistence capability or write authority. */
    public record PersistenceKey(String tenant, String actor, String state) {
        public PersistenceKey {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(state, "state");
        }
    }

    /** Immutable description available to trusted actor initialization. */
    public record ActivationDescriptor(
            VirtualActorActivationRegistry.Identity identity,
            long logicalGeneration,
            PersistenceKey persistenceKey) {
        public ActivationDescriptor {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(persistenceKey, "persistenceKey");
            if (logicalGeneration <= 0)
                throw new IllegalArgumentException("invalid logical activation generation");
        }
    }

    /** Single-use host reservation; never pass this object through actor DATA. */
    public final class ActivationPlan {
        private final VirtualActorSupervisorDirectory owner = VirtualActorSupervisorDirectory.this;
        private final VirtualActorActivationRegistry.LogicalRef logical;
        private final VirtualActorActivationRegistry.Activation activation;
        private final ActivationDescriptor descriptor;
        private final AtomicBoolean settled = new AtomicBoolean();

        private ActivationPlan(VirtualActorActivationRegistry.LogicalRef logical,
                               VirtualActorActivationRegistry.Activation activation,
                               ActivationDescriptor descriptor) {
            this.logical = logical;
            this.activation = activation;
            this.descriptor = descriptor;
        }

        public ActivationDescriptor descriptor() { return descriptor; }
    }

    private record Binding(ActorRuntime.ActorRef<?> actor,
                           VirtualActorActivationRegistry.Activation activation) { }

    /** Payload-free terminal metadata retained after the worker physically exits. */
    private record TerminalRecord(ActorRuntime.ActorId actorId,
                                  long actorGeneration,
                                  ActorRuntime.ActorKind actorKind,
                                  String failureClass,
                                  boolean cancelled,
                                  Instant terminalAt) { }

    private final ActorRuntime runtime;
    private final ActorRuntime.ActorSupervisor supervisor;
    private final String tenant;
    private final VirtualActorActivationRegistry registry;
    private final VirtualActorDirectoryInspection localInspection;
    private final Object stateLock = new Object();
    private final Map<VirtualActorActivationRegistry.Identity, Binding> bindings = new HashMap<>();
    private final Map<VirtualActorActivationRegistry.Identity, TerminalRecord> terminals = new HashMap<>();
    private final AtomicInteger pendingInspections = new AtomicInteger();
    private volatile boolean closed;
    private int pendingPlans;

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
        // Supervisor identity is intentionally NOT part of the logical actor ID:
        // activation placement/ownership can change without changing persistent identity.
        this.registry = runtime.virtualActorRegistry(
                supervisor, this.tenant, maxIdentities, mailboxCapacity);
        this.localInspection = new VirtualActorDirectoryInspection(registry);
        runtime.claimVirtualActorDirectory(this, supervisor);
    }

    private static String requireText(String value, String name, int max) {
        Objects.requireNonNull(value, name);
        if (value.length() > max || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > max
                || !Normalizer.isNormalized(value, Normalizer.Form.NFC)
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid " + name);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw new IllegalArgumentException("unpaired UTF-16 surrogate in " + name);
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("unpaired UTF-16 surrogate in " + name);
            }
        }
        return value;
    }

    /** Stable logical lookup in the default namespace. This never activates the actor. */
    public VirtualActorActivationRegistry.LogicalRef resolve(String declaration, String key) {
        return resolve("default", declaration, key);
    }

    /** Stable logical lookup with an explicit namespace; lookup itself never activates. */
    public VirtualActorActivationRegistry.LogicalRef resolve(
            String namespace, String declaration, String key) {
        namespace = requireText(namespace, "namespace", 128);
        declaration = requireText(declaration, "declaration", 128);
        String qualifiedDeclaration = namespace.getBytes(StandardCharsets.UTF_8).length
                + ":" + namespace + declaration;
        if (qualifiedDeclaration.length() > 128
                || qualifiedDeclaration.getBytes(StandardCharsets.UTF_8).length > 128)
            throw new IllegalArgumentException("qualified virtual actor type exceeds identity budget");
        runtime.supervisorRegistryForObservation(); // host-only caller/affinity guard
        synchronized (stateLock) {
            if (closed) throw new IllegalStateException("virtual actor supervisor directory closed");
            var logical = registry.resolve(qualifiedDeclaration, key);
            try {
                runtime.claimVirtualActorOwner(logical.identity(), supervisor);
                return logical;
            } catch (RuntimeException | Error failure) {
                registry.discardUnactivated(logical);
                throw failure;
            }
        }
    }

    /**
     * Reserve a fresh generation before constructing the physical worker. The
     * descriptor supplies a stable deterministic storage identifier and epoch for trusted
     * initialization code; it is data, not a capability. The returned plan is
     * single-use and must be published or explicitly aborted.
     */
    public ActivationPlan prepareActivation(
            VirtualActorActivationRegistry.LogicalRef logical, String stateSlot) {
        Objects.requireNonNull(logical, "logical");
        stateSlot = requireText(stateSlot, "stateSlot", 128);
        runtime.supervisorRegistryForObservation();
        if (!tenant.equals(logical.identity().domain())
                || !runtime.ownsVirtualActor(logical.identity(), supervisor))
            throw new SecurityException("virtual actor reference outside supervisor scope");
        PersistenceKey persistenceKey = new PersistenceKey(
                tenant, stablePersistenceActorKey(logical.identity()), stateSlot);
        synchronized (stateLock) {
            if (closed) throw new IllegalStateException("virtual actor supervisor directory closed");
            VirtualActorActivationRegistry.Activation activation = registry.activate(logical);
            if (!activation.initializer())
                throw new IllegalStateException("logical virtual actor already has an activation");
            try {
                ActivationDescriptor descriptor = new ActivationDescriptor(
                        logical.identity(), activation.incarnation(), persistenceKey);
                ActivationPlan plan = new ActivationPlan(logical, activation, descriptor);
                pendingPlans++;
                return plan;
            } catch (RuntimeException | Error allocationFailure) {
                // Descriptor/plan allocation is part of the reservation
                // transaction. If it fails, burn and release the unpublished
                // activation so no unreturnable candidate can block reactivation.
                try { registry.abort(activation); }
                catch (RuntimeException rollbackFailure) {
                    allocationFailure.addSuppressed(rollbackFailure);
                }
                throw allocationFailure;
            }
        }
    }

    /** Abort a candidate after worker initialization/admission fails; the generation is burned. */
    public void abortActivation(ActivationPlan plan) {
        Objects.requireNonNull(plan, "plan");
        runtime.supervisorRegistryForObservation();
        synchronized (stateLock) {
            requirePlan(plan);
            if (!plan.settled.compareAndSet(false, true))
                throw new IllegalStateException("activation plan already settled");
            if (bindings.containsKey(plan.logical.identity()))
                throw new IllegalStateException("cannot abort a published activation");
            registry.abort(plan.activation);
            pendingPlans--;
        }
    }

    /** Publish only an initialized live worker managed by this exact supervisor. */
    public long publishActivation(ActivationPlan plan, ActorRuntime.ActorRef<?> actor) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(actor, "actor");
        runtime.supervisorRegistryForObservation();
        if (!actor.getReady().isDone() || !actor.isAlive()
                || !runtime.actorManagedBySupervisor(actor, supervisor))
            throw new SecurityException("activation worker is not ready and managed by this supervisor");
        synchronized (stateLock) {
            requirePlan(plan);
            if (plan.settled.get())
                throw new IllegalStateException("activation plan already settled");
            if (!actor.isAlive() || !runtime.actorManagedBySupervisor(actor, supervisor)) {
                abortPlanUnderLock(plan);
                throw new IllegalStateException(
                        "activation worker stopped or lost supervisor ownership during admission");
            }
            Binding binding = new Binding(actor, plan.activation);
            if (bindings.putIfAbsent(plan.logical.identity(), binding) != null) {
                abortPlanUnderLock(plan);
                throw new IllegalStateException("logical virtual actor activation already bound");
            }
            try {
                registry.publish(plan.activation);
                if (!plan.settled.compareAndSet(false, true))
                    throw new IllegalStateException("activation plan settlement raced publication");
                pendingPlans--;
            } catch (RuntimeException | Error failure) {
                bindings.remove(plan.logical.identity(), binding);
                if (!plan.settled.get()) {
                    try { abortPlanUnderLock(plan); }
                    catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
                }
                throw failure;
            }
            // done() settles only after finalization and physical TurnExecutor exit.
            // The callback only changes trusted metadata; it never runs guest code.
            actor.done().whenCompleteRuntime((ignored, failure) ->
                    retireAfterPhysicalCompletion(plan.logical.identity(), binding, failure));
            return plan.activation.incarnation();
        }
    }

    /** Convenience host binding for workers constructed without a persistence plan. */
    public long bindActivation(VirtualActorActivationRegistry.LogicalRef logical,
                               ActorRuntime.ActorRef<?> actor) {
        ActivationPlan plan = prepareActivation(logical, "default");
        try {
            return publishActivation(plan, actor);
        } catch (RuntimeException | Error failure) {
            if (!plan.settled.get()) {
                try { abortActivation(plan); }
                catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    private void requirePlan(ActivationPlan plan) {
        if (plan.owner != this || plan.logical == null || plan.activation == null
                || plan.settled.get())
            throw new SecurityException("foreign or settled virtual actor activation plan");
    }

    private void abortPlanUnderLock(ActivationPlan plan) {
        if (!plan.settled.compareAndSet(false, true)) return;
        registry.abort(plan.activation);
        pendingPlans--;
    }

    private static String stablePersistenceActorKey(
            VirtualActorActivationRegistry.Identity identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(identity.domain().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(identity.declaration().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(identity.key().getBytes(StandardCharsets.UTF_8));
            return "va1_" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    private static String sanitizedFailureClass(Throwable failure) {
        String name = failure.getClass().getName();
        return name.length() <= 256 ? name : "other";
    }

    private void retireAfterPhysicalCompletion(
            VirtualActorActivationRegistry.Identity identity, Binding expected, Throwable failure) {
        synchronized (stateLock) {
            if (closed || bindings.get(identity) != expected) return;
            try {
                registry.deactivate(expected.activation());
                bindings.remove(identity, expected);
                Throwable terminal = failure == null ? null : OresFuture.unwrap(failure);
                boolean cancelled = terminal instanceof java.util.concurrent.CancellationException;
                String failureClass = terminal == null || cancelled
                        ? null : sanitizedFailureClass(terminal);
                terminals.put(identity, new TerminalRecord(expected.actor().id(),
                        expected.actor().generation(), expected.actor().kind(),
                        failureClass, cancelled, Instant.now()));
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
        if (!tenant.equals(identity.domain())
                || !runtime.ownsVirtualActor(identity, supervisor))
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
        if (!tenant.equals(logical.identity().domain())
                || !runtime.ownsVirtualActor(logical.identity(), supervisor))
            throw new SecurityException("virtual actor reference outside supervisor scope");
        return request(grant, authenticatedSession, timeoutNanos,
                () -> enrich(localInspection.inspect(logical)));
    }

    /** Immutable bounded page returned by the supervisor's virtual-actor directory view. */
    public record SnapshotPage(java.util.List<Snapshot> entries, long revision,
                               Optional<VirtualActorActivationRegistry.Identity> nextAfter) {
        public SnapshotPage {
            entries = java.util.List.copyOf(entries);
            Objects.requireNonNull(nextAfter, "nextAfter");
            if (revision < 0) throw new IllegalArgumentException("invalid virtual actor page revision");
        }
    }

    /**
     * Enumerate only logical identities registered in this supervisor's local
     * directory. A continuation cursor is bound to the directory revision;
     * any new identity admission/discard between pages rejects the cursor.
     */
    public CompletionStage<SnapshotPage> requestList(
            Grant grant, Object authenticatedSession, int limit,
            long expectedRevision,
            VirtualActorActivationRegistry.Identity after,
            long timeoutNanos) {
        runtime.supervisorRegistryForObservation();
        if (limit < 1 || limit > 128)
            throw new IllegalArgumentException("supervisor virtual actor page size must be 1..128");
        if (after != null && (!tenant.equals(after.domain())
                || !runtime.ownsVirtualActor(after, supervisor)))
            throw new SecurityException("virtual actor page cursor outside supervisor scope");
        return request(grant, authenticatedSession, timeoutNanos, () -> {
            VirtualActorActivationRegistry.Page page = registry.listPage(
                    limit, expectedRevision, after);
            java.util.ArrayList<Snapshot> entries = new java.util.ArrayList<>(page.entries().size());
            for (VirtualActorActivationRegistry.Inspection item : page.entries()) {
                VirtualActorDirectoryInspection.Snapshot local = new VirtualActorDirectoryInspection.Snapshot(
                        1, item.identity(), VirtualActorAddressV1.encode(item.identity()),
                        lifecycle(item), item.latestIncarnation(), item.outstanding(),
                        Instant.now(), true);
                entries.add(enrich(local));
            }
            return new SnapshotPage(entries, page.revision(), page.nextAfter());
        });
    }

    private static VirtualActorDirectoryInspection.Lifecycle lifecycle(
            VirtualActorActivationRegistry.Inspection value) {
        if (value.registryClosed()) return VirtualActorDirectoryInspection.Lifecycle.CLOSED;
        if (value.published()) return VirtualActorDirectoryInspection.Lifecycle.ACTIVE;
        if (value.activationPresent()) return VirtualActorDirectoryInspection.Lifecycle.ACTIVATING;
        if (value.latestIncarnation() == 0)
            return VirtualActorDirectoryInspection.Lifecycle.NEVER_ACTIVATED;
        return VirtualActorDirectoryInspection.Lifecycle.PASSIVATED;
    }

    private Snapshot enrich(VirtualActorDirectoryInspection.Snapshot snapshot) {
        synchronized (stateLock) {
            Binding binding = bindings.get(snapshot.identity());
            TerminalRecord terminal = terminals.get(snapshot.identity());
            Optional<ActorRuntime.ActorId> actorId = Optional.empty();
            Optional<ActorRuntime.ActorKind> actorKind = Optional.empty();
            Optional<String> terminalFailure = Optional.empty();
            Optional<Instant> terminalAt = Optional.empty();
            long actorGeneration = 0L;
            Lifecycle lifecycle = switch (snapshot.lifecycle()) {
                case NEVER_ACTIVATED -> Lifecycle.NEVER_ACTIVATED;
                case ACTIVATING -> Lifecycle.ACTIVATING;
                case ACTIVE -> Lifecycle.ACTIVE;
                case PASSIVATED -> Lifecycle.PASSIVATED;
                case CLOSED -> Lifecycle.CLOSED;
            };
            if (terminal != null) {
                actorId = Optional.of(terminal.actorId());
                actorKind = Optional.of(terminal.actorKind());
                actorGeneration = terminal.actorGeneration();
                terminalFailure = Optional.ofNullable(terminal.failureClass());
                terminalAt = Optional.of(terminal.terminalAt());
                if (terminal.failureClass() != null) lifecycle = Lifecycle.FAILED;
                else if (terminal.cancelled()) lifecycle = Lifecycle.CANCELLED;
                else lifecycle = Lifecycle.PASSIVATED;
            }
            if (binding != null) {
                actorId = Optional.of(binding.actor().id());
                actorKind = Optional.of(binding.actor().kind());
                actorGeneration = binding.actor().generation();
                if (!binding.actor().isAlive())
                    lifecycle = Lifecycle.TERMINATION_PENDING_RECONCILIATION;
                else
                    lifecycle = Lifecycle.ACTIVE;
            }
            return new Snapshot(1, snapshot.identity(), snapshot.address(), supervisor.id(),
                    lifecycle, snapshot.activationGeneration(), actorId, actorGeneration,
                    actorKind, terminalFailure, terminalAt, snapshot.outstandingAdmissions(),
                    snapshot.observedAt(), true);
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
        // 0=queued, 1=running, 2=cancelled-before-start, 3=finished.
        // Timeout can revoke a queued capture but global queue capacity remains
        // charged until the queued CONTROL callback is physically drained (or
        // the dispatcher reports that it will never run).
        AtomicInteger phase = new AtomicInteger(0);
        AtomicBoolean grantReleased = new AtomicBoolean();
        AtomicBoolean slotReleased = new AtomicBoolean();
        Runnable releaseGrant = () -> {
            if (grantReleased.compareAndSet(false, true))
                grant.reading.set(false);
        };
        Runnable releaseSlot = () -> {
            if (slotReleased.compareAndSet(false, true)) {
                releaseGrant.run();
                pendingInspections.decrementAndGet();
            }
        };
        // If the caller times out before the CONTROL turn starts, the phase
        // transition prevents capture and permits reuse of the grant. The
        // global CONTROL admission remains charged until its queued callback
        // runs and observes cancellation.
        result.whenComplete((ignored, failure) -> {
            if (phase.compareAndSet(0, 2)) releaseGrant.run();
        });
        final OresFuture<Void> dispatch;
        try {
            dispatch = runtime.dispatchSupervisorObservationControl(() -> {
                if (!phase.compareAndSet(0, 1)) {
                    if (phase.compareAndSet(2, 3)) releaseSlot.run();
                    return;
                }
                try {
                    if (Thread.currentThread() == caller)
                        throw new IllegalStateException("INLINE_CONTROL_DISPATCH_FORBIDDEN");
                    requireGrant(grant, session);
                    if (!result.isDone()) result.complete(capture.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    phase.set(3);
                    releaseSlot.run();
                }
            });
        } catch (RuntimeException | Error failure) {
            result.completeExceptionally(failure);
            if (phase.compareAndSet(0, 3) || phase.compareAndSet(2, 3))
                releaseSlot.run();
            return result.minimalCompletionStage();
        }
        dispatch.whenCompleteRuntime((ignored, failure) -> {
            if (failure != null) {
                result.completeExceptionally(new IllegalStateException("CONTROL_DISPATCH_FAILED"));
                // The dispatcher Future may release a queued task only when it
                // guarantees the task will not execute later. The phase fence
                // makes an erroneous late execution a no-op in either case.
                if (phase.compareAndSet(0, 3) || phase.compareAndSet(2, 3))
                    releaseSlot.run();
            }
        });
        return result.minimalCompletionStage();
    }

    public int pendingInspectionCount() {
        runtime.supervisorRegistryForObservation();
        return pendingInspections.get();
    }

    @Override public void close() {
        // releaseVirtualActorDirectory performs host/supervisor-context and
        // runtime-affinity checks but remains usable after ActorRuntime shutdown.
        synchronized (stateLock) {
            if (closed) return;
            if (pendingInspections.get() != 0)
                throw new IllegalStateException("cannot close with pending CONTROL inspections");
            if (pendingPlans != 0)
                throw new IllegalStateException("cannot close with reserved activation plans");
            if (!bindings.isEmpty())
                throw new IllegalStateException("cannot close while supervised activations remain bound");
            // The registry belongs to ActorRuntime, not this facade. Its logical
            // IDs, revision and burned generations survive facade replacement.
            runtime.releaseVirtualActorDirectory(this, supervisor);
            closed = true;
        }
    }
}
