package dev.oreslang.runtime;

import java.util.Objects;
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
        Objects.requireNonNull(address, "address");
        ReentrantLock guard = lock(address);
        guard.lock();
        try {
            if (!quiescence.isQuiescent(address)) {
                throw new IllegalStateException("cannot activate while local actor callback runs");
            }
            return store.activate(address);
        } finally { guard.unlock(); }
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
        Objects.requireNonNull(turn, "turn");
        ReentrantLock guard = lock(turn.address());
        guard.lock();
        try {
            return store.commitTurn(turn);
        } finally { guard.unlock(); }
    }

    /**
     * Freeze only at a runtime-proven safe boundary. A callback must not make a
     * network call or hold an actor-owned borrow while the arena is released.
     */
    public void freeze(DurableActorStore.Address address, long epoch) {
        Objects.requireNonNull(address, "address");
        ReentrantLock guard = lock(address);
        guard.lock();
        try {
            if (!quiescence.isQuiescent(address)) {
                throw new IllegalStateException("actor execution not quiescent");
            }
            store.hibernate(address, epoch);
        } finally { guard.unlock(); }
    }
}
