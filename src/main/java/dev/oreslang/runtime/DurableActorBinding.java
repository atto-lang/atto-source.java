package dev.oreslang.runtime;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host/supervisor-side binding of exactly one local runtime incarnation to
 * one fenced, durable PostgreSQL activation. Never pass this object, its
 * owner UUID, or its activation to Oreslang guest code.
 *
 * This is a one-turn handoff: input must have been committed to the durable
 * inbox before delivery was acknowledged; the trusted caller supplies a Turn
 * computed for the bound code generation. It is NOT a snapshot serializer
 * and does not implement activation restore or external effect dispatch.
 */
public final class DurableActorBinding {
    private final ActorRuntime runtime;
    private final ActorRuntime.ActorRef<?> actor;
    private final DurableActorStore.Address address;
    private final long epoch;
    private final long stateVersion;
    private final String codeGeneration;
    private final UUID owner;
    private final DurableActivationCoordinator coordinator;
    private final java.util.Set<String> admissibleDeliveries;
    private final AtomicBoolean handoffStarted = new AtomicBoolean();

    private DurableActorBinding(
            ActorRuntime runtime,
            ActorRuntime.ActorRef<?> actor,
            DurableActorStore.Address address,
            PostgresDurableActorStore.LeaseActivation lease,
            String executingGeneration,
            DurableActivationCoordinator coordinator) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.address = Objects.requireNonNull(address, "address");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        Objects.requireNonNull(lease, "lease");
        // LeaseActivation can be issued only by PostgreSQL lease acquisition.
        // Never reinterpret a valid owner+epoch for a different address.
        if (!address.equals(lease.address())) {
            throw new IllegalArgumentException(
                    "durable activation belongs to a different tenant-scoped address");
        }
        Objects.requireNonNull(executingGeneration, "executingGeneration");
        DurableActorStore.Activation activation =
                Objects.requireNonNull(lease.activation(), "activation");
        this.owner = Objects.requireNonNull(lease.owner(), "owner");
        this.epoch = activation.epoch();
        this.stateVersion = activation.snapshot().stateVersion();
        this.codeGeneration = activation.snapshot().codeGeneration();
        if (!executingGeneration.equals(codeGeneration)) {
            throw new IllegalArgumentException("actor code generation differs from durable state");
        }
        this.admissibleDeliveries = activation.inbox().stream()
                .map(DurableActorStore.Inbound::deliveryId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        // This is a host-owned capability, not an authorization claim derived
        // from a raw actor address. Cross-runtime binding fails at the actual
        // ActorRuntime.requestDurableQuiescence() call before any database I/O.
    }

    public static DurableActorBinding bind(
            ActorRuntime runtime,
            ActorRuntime.ActorRef<?> actor,
            DurableActorStore.Address address,
            PostgresDurableActorStore.LeaseActivation lease,
            String executingGeneration,
            DurableActivationCoordinator coordinator) {
        return new DurableActorBinding(runtime, actor, address, lease,
                executingGeneration, coordinator);
    }

    /** Fail fast before the host acquires a runtime proof or accesses SQL. */
    private void verifyTurn(DurableActorStore.Turn turn) {
        Objects.requireNonNull(turn, "turn");
        if (!address.equals(turn.address()) || turn.epoch() != epoch
                || turn.expectedStateVersion() != stateVersion
                || !turn.nextSnapshot().codeGeneration().equals(codeGeneration)
                || !admissibleDeliveries.contains(turn.consumedDeliveryId())) {
            throw new IllegalArgumentException(
                    "durable turn does not match bound address, epoch, generation, version or inbox");
        }
    }

    /**
     * Quiesce without blocking an actor/CONTROL worker. Once proven, run the
     * entire PostgreSQL commit+lease-release transaction on dbExecutor, then
     * terminate the old actor before completing the returned observer.
     *
     * A failure before SQL is attempted resumes the actor and permits retry.
     * An uncertain SQL failure cancels the actor instead: a possibly expired
     * or superseded database lease must NEVER resume guest code.
     */
    public CompletableFuture<Long> commitAndHibernateAsync(
            DurableActorStore.Turn turn, Executor dbExecutor) {
        verifyTurn(turn);
        Objects.requireNonNull(dbExecutor, "dbExecutor");
        if (!handoffStarted.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("durable handoff already pending or completed"));
        }
        CompletableFuture<Long> completion = new CompletableFuture<>();
        final CompletableFuture<ActorRuntime.DurableQuiescence> freeze;
        try {
            freeze = runtime.requestDurableQuiescence(actor);
        } catch (RuntimeException e) {
            handoffStarted.set(false);
            completion.completeExceptionally(e);
            return completion.copy();
        }
        freeze.whenComplete((proof, error) -> {
            if (error != null) {
                handoffStarted.set(false);
                completion.completeExceptionally(error);
                return;
            }
            try {
                dbExecutor.execute(() -> {
                    boolean sqlAttempted = false;
                    try {
                        if (ActorRuntime.inActorExecution()
                                || ActorRuntime.inActorGroupMailmanExecution()
                                || ActorRuntime.isActorCarrierThread()) {
                            throw new SecurityException(
                                    "durable database I/O cannot execute on actor or CONTROL carrier");
                        }
                        if (!proof.valid() || !actor.id().equals(proof.actorId())) {
                            throw new IllegalStateException("runtime execution proof was revoked");
                        }
                        sqlAttempted = true;
                        long version = coordinator.commitAndHibernateLeased(
                                turn, owner, actor.id(), proof);
                        proof.stopAfterHibernate();
                        completion.complete(version);
                    } catch (Throwable e) {
                        if (sqlAttempted) {
                            // SQL may have committed and the database lease may
                            // have been released. Terminate, do not resume.
                            try { actor.cancel(); }
                            catch (Throwable failure) { e.addSuppressed(failure); }
                        } else {
                            handoffStarted.set(false);
                        }
                        try { proof.close(); }
                        catch (Throwable failure) { e.addSuppressed(failure); }
                        completion.completeExceptionally(e);
                    }
                });
            } catch (RuntimeException rejected) {
                // No transaction started; an executor rejection may safely
                // release this freeze proof and retry on a healthy worker.
                handoffStarted.set(false);
                try { proof.close(); }
                catch (RuntimeException cleanup) { rejected.addSuppressed(cleanup); }
                completion.completeExceptionally(rejected);
            }
        });
        // A caller can complete/cancel its observer but cannot forge success,
        // cancel the DB operation or release the runtime's authoritative proof.
        return completion.copy();
    }
}
