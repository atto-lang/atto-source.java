package dev.oreslang.runtime;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/**
 * Host-only ownership ledger for one logical WebSocket connection.
 *
 * <p>This is NOT a guest-visible capability registry or socket object. Only a
 * trusted runtime bridge may issue a handle and supply its authenticated
 * tenant/current ActorAddress. A guest must never be allowed to provide a
 * caller ActorAddress or the liveness predicate.</p>
 *
 * <p>Lease accounting fences new operations, atomic owner transfer, and
 * logical retirement. An in-flight lease stays outstanding until its physical
 * I/O worker releases it. No lease settlement pretends that a SocketChannel,
 * context loan or scheduler callback has retired.</p>
 */
final class WebSocketActorConnectionLease {
    /** Identity-only, never serializable or reconstructed from UUID strings. */
    static final class Handle {
        private final WebSocketActorConnectionLease issuer;
        private final long generation;

        private Handle(WebSocketActorConnectionLease issuer, long generation) {
            this.issuer = issuer;
            this.generation = generation;
        }
    }

    final class Loan implements AutoCloseable {
        private final long admittedGeneration;
        private boolean released;

        private Loan(long generation) { admittedGeneration = generation; }

        /**
         * The callback may ONLY enqueue a runtime-owned, bounded scheduler
         * message, not execute guest code or block. Enqueue and revocation
         * share the monitor so a competing transfer/retire cannot win between
         * generation validation and enqueue. The scheduler MUST revalidate
         * the handle on actual actor delivery.
         */
        boolean enqueueIfCurrent(Runnable trustedEnqueue) {
            Objects.requireNonNull(trustedEnqueue, "trusted enqueue");
            synchronized (WebSocketActorConnectionLease.this) {
                if (released || retired || admittedGeneration != generation
                        || !ownerAlive.getAsBoolean()) return false;
                trustedEnqueue.run();
                return true;
            }
        }

        boolean mayDeliver(Handle handle, UUID tenant,
                ActorRuntime.ActorAddress currentActor) {
            synchronized (WebSocketActorConnectionLease.this) {
                return !released && !retired && admittedGeneration == generation
                        && authorized(handle, tenant, currentActor)
                        && ownerAlive.getAsBoolean();
            }
        }

        @Override public void close() {
            synchronized (WebSocketActorConnectionLease.this) {
                if (released) return;
                released = true;
                if (outstanding <= 0) {
                    throw new IllegalStateException("WebSocket lease accounting underflow");
                }
                outstanding--;
            }
        }
    }

    private final UUID tenant;
    private final UUID connectionId = UUID.randomUUID();
    private ActorRuntime.ActorAddress owner;
    private BooleanSupplier ownerAlive;
    private long generation = 1;
    private int outstanding;
    private boolean retired;

    private WebSocketActorConnectionLease(UUID tenant,
            ActorRuntime.ActorAddress owner, BooleanSupplier alive) {
        this.tenant = Objects.requireNonNull(tenant, "tenant");
        this.owner = Objects.requireNonNull(owner, "actor owner");
        this.ownerAlive = Objects.requireNonNull(alive, "actor liveness");
        if (!alive.getAsBoolean()) {
            throw new RejectedExecutionException("cannot bind WebSocket to retired actor");
        }
    }

    /** Trusted host/supervisor only; the actor reference is not a guest handle. */
    static WebSocketActorConnectionLease bind(
            UUID tenant, ActorRuntime.ActorRef<?> owner) {
        Objects.requireNonNull(owner, "actor ref");
        if (ActorRuntime.inActorExecution()) {
            throw new SecurityException("WebSocket connection binding belongs to supervisor");
        }
        return new WebSocketActorConnectionLease(tenant,
                owner.address(), owner::isAlive);
    }

    /** Host test seam, not an authorization endpoint exposed to guests. */
    static WebSocketActorConnectionLease bindHost(
            UUID tenant, ActorRuntime.ActorAddress address, BooleanSupplier alive) {
        if (ActorRuntime.inActorExecution()) {
            throw new SecurityException("host WebSocket binding inside actor execution");
        }
        return new WebSocketActorConnectionLease(tenant, address, alive);
    }

    synchronized Handle issue() {
        if (retired || !ownerAlive.getAsBoolean()) {
            throw new RejectedExecutionException("WebSocket owner retired");
        }
        return new Handle(this, generation);
    }

    synchronized Loan acquire(Handle handle, UUID authenticatedTenant,
            ActorRuntime.ActorAddress authenticatedCaller) {
        requireCurrent(handle, authenticatedTenant, authenticatedCaller);
        if (outstanding == Integer.MAX_VALUE) {
            throw new RejectedExecutionException("WebSocket ownership leases exhausted");
        }
        outstanding++;
        return new Loan(generation);
    }

    /**
     * Transfer is permitted only with zero outstanding physical operations.
     * The next generation is published under one lock after the old handle
     * becomes stale; the new owner must belong to an explicitly verified
     * runtime/tenant authority in the trusted caller.
     */
    synchronized Handle transfer(Handle previous, UUID authenticatedTenant,
            ActorRuntime.ActorAddress authenticatedCaller,
            ActorRuntime.ActorRef<?> nextActor) {
        Objects.requireNonNull(nextActor, "next actor");
        return transferHost(previous, authenticatedTenant, authenticatedCaller,
                nextActor.address(), nextActor::isAlive);
    }

    /** Trusted host test seam; never accept an untrusted next-owner address. */
    synchronized Handle transferHost(Handle previous, UUID authenticatedTenant,
            ActorRuntime.ActorAddress authenticatedCaller,
            ActorRuntime.ActorAddress nextOwner, BooleanSupplier nextAlive) {
        requireCurrent(previous, authenticatedTenant, authenticatedCaller);
        Objects.requireNonNull(nextOwner, "next owner");
        Objects.requireNonNull(nextAlive, "next owner liveness");
        if (nextOwner.equals(owner)) {
            throw new IllegalArgumentException("WebSocket transfer requires distinct owner");
        }
        if (!nextAlive.getAsBoolean()) {
            throw new RejectedExecutionException("new actor already retired");
        }
        if (outstanding != 0) {
            throw new RejectedExecutionException(
                    "WebSocket transfer awaits physical operation retirement");
        }
        if (generation == Long.MAX_VALUE) {
            throw new RejectedExecutionException("WebSocket generation exhausted");
        }
        generation++;
        owner = nextOwner;
        ownerAlive = nextAlive;
        return new Handle(this, generation);
    }

    /**
     * Logical revocation wins before any later operation may be admitted.
     * The owning native-I/O service must separately close/wake the socket and
     * wait for all loans/worker receipts; this method NEVER claims that proof.
     */
    synchronized boolean revoke() {
        if (retired) return false;
        retired = true;
        return true;
    }

    synchronized boolean isLogicallyRetired() { return retired; }
    synchronized boolean hasOutstandingLoans() { return outstanding != 0; }
    synchronized int outstandingLoans() { return outstanding; }
    UUID connectionId() { return connectionId; }

    private boolean authorized(Handle handle, UUID authenticatedTenant,
            ActorRuntime.ActorAddress authenticatedCaller) {
        return handle != null && handle.issuer == this
                && handle.generation == generation
                && tenant.equals(authenticatedTenant)
                && owner.equals(authenticatedCaller);
    }

    private void requireCurrent(Handle handle, UUID authenticatedTenant,
            ActorRuntime.ActorAddress authenticatedCaller) {
        if (retired || !authorized(handle, authenticatedTenant, authenticatedCaller)) {
            throw new SecurityException("stale, foreign-tenant or foreign-actor WebSocket handle");
        }
        if (!ownerAlive.getAsBoolean()) {
            throw new RejectedExecutionException("WebSocket actor owner no longer alive");
        }
    }
}
