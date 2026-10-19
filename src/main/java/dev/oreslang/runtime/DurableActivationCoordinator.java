package dev.oreslang.runtime;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Storage-facing scheduler seam for durable actor activation and freeze.
 *
 * NOT yet wired to guest ActorRuntime. In-process striped locks protect host
 * coordination only; cross-process stale commits are fenced by DurableActorStore.
 * An actor scheduler must supply actual actor-wide quiescence before freeze.
 */
public final class DurableActivationCoordinator {
    @FunctionalInterface
    public interface ActorQuiescence {
        /** Only return true after actor's receive, callbacks, continuation and cleanup are parked. */
        boolean isQuiescent(DurableActorStore.Address address);
    }

    // Bounded stripes, rather than an unbounded lock keyed per long-lived actor.
    private final ReentrantLock[] stripes = new ReentrantLock[256];
    private final DurableActorStore store;
    private final ActorQuiescence quiescence;

    public DurableActivationCoordinator(DurableActorStore store, ActorQuiescence quiescence) {
        this.store = Objects.requireNonNull(store, "store");
        this.quiescence = Objects.requireNonNull(quiescence, "quiescence");
        for (int i=0;i<stripes.length;i++) stripes[i] = new ReentrantLock();
    }

    private ReentrantLock lock(DurableActorStore.Address address) {
        return stripes[(address.hashCode() & Integer.MAX_VALUE) % stripes.length];
    }

    /** Reacquire persisted identity; epoch increments, invalidating older activations. */
    public DurableActorStore.Activation restore(DurableActorStore.Address address) {
        throw new IllegalStateException("owner UUID required: use restoreLeased");
    }

    public PostgresDurableActorStore.LeaseActivation restoreLeased(
            DurableActorStore.Address address, UUID owner) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(address, "address");
        ReentrantLock guard = lock(address);
        guard.lock();
        try {
            if (!quiescence.isQuiescent(address)) {
                throw new IllegalStateException("cannot activate while local actor callback runs");
            }
            if (!(store instanceof PostgresDurableActorStore postgres)) {
                throw new IllegalStateException("owner-aware store is required");
            }
            return postgres.acquireLease(address, owner);
        } finally { guard.unlock(); }
    }

    /**
     * The only durable turn+hibernate entrypoint that accepts a real runtime
     * scheduler proof. The proof is host-only and must refer to the exact
     * incarnation the supervisor has bound to this persistent address.
     * No advisory isQuiescent() callback can substitute for this evidence.
     *
     * Both state commit and owner lease release run in ONE database transaction.
     * The caller is responsible for immediately terminating the fenced runtime
     * actor after success, and failing it closed after any uncertain error.
     */
    public long commitAndHibernateLeased(
            DurableActorStore.Turn turn,
            UUID owner,
            ActorRuntime.ActorId expectedActor,
            ActorRuntime.DurableQuiescence proof) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(expectedActor, "expectedActor");
        Objects.requireNonNull(proof, "proof");
        if (!expectedActor.equals(proof.actorId()) || !proof.valid()) {
            throw new IllegalStateException("missing current runtime quiescence proof");
        }
        ReentrantLock guard = lock(turn.address());
        guard.lock();
        try {
            if (!expectedActor.equals(proof.actorId()) || !proof.valid()) {
                throw new IllegalStateException("runtime quiescence was revoked");
            }
            if (!(store instanceof PostgresDurableActorStore postgres)) {
                throw new IllegalStateException("owner-aware PostgreSQL store is required");
            }
            return postgres.commitAndHibernateOwnedTurn(turn, owner);
        } finally {
            guard.unlock();
        }
    }

    /** A durable mailbox can accept messages while the runtime is frozen. */
    public boolean deliver(DurableActorStore.Address address, DurableActorStore.Inbound mail) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(mail, "mail");
        // Enqueue does not execute guest code and need not wake a Java carrier.
        return store.enqueue(address, mail);
    }

    /**
     * Persist turn intent ONLY AFTER the real actor execution lease has produced
     * the immutable Turn. Never execute side effects directly inside this method.
     */
    public long commit(DurableActorStore.Turn turn) {
        throw new IllegalStateException("owner UUID required: use commitLeased");
    }

    public long commitLeased(DurableActorStore.Turn turn, UUID owner) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(owner);
        ReentrantLock guard = lock(turn.address());
        guard.lock();
        try {
            if (!(store instanceof PostgresDurableActorStore postgres)) {
                throw new IllegalStateException("owner-aware store is required");
            }
            return postgres.commitOwnedTurn(turn, owner);
        } finally { guard.unlock(); }
    }

    /**
     * Freeze only at a runtime-proven safe boundary. A callback must not make a
     * network call or hold an actor-owned borrow while the arena is released.
     */
    public void freeze(DurableActorStore.Address address, long epoch) {
        throw new IllegalStateException("owner UUID required: use freezeLeased");
    }

    public void freezeLeased(DurableActorStore.Address address, long epoch, UUID owner) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(address, "address");
        ReentrantLock guard = lock(address);
        guard.lock();
        try {
            if (!quiescence.isQuiescent(address)) {
                throw new IllegalStateException("actor execution not quiescent");
            }
            if (!(store instanceof PostgresDurableActorStore postgres)) {
                throw new IllegalStateException("owner-aware store is required");
            }
            postgres.hibernateLease(address, epoch, owner);
        } finally { guard.unlock(); }
    }
}
