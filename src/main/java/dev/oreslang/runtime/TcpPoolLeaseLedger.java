package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Trusted host-only authority for exclusive raw TCP physical slots.
 *
 * <p>Not a guest pointer, socket connector, payload mailbox, or multiplexed
 * protocol. Admission is a nonparking tryLock and bounded fixed-slot lookup.
 * Reactor completion must be observed before an in-flight slot can be reused.
 * Java is a soft-real-time backend; this deliberately allocates one opaque
 * token per lease and makes no hard-real-time allocation claim.</p>
 */
final class TcpPoolLeaseLedger {
    record ActorIdentity(long id, long incarnation) {}

    static final class Lease {
        private final TcpPoolLeaseLedger owner;
        private final int slot;
        private final long generation;
        private final ActorIdentity actor;
        private final long epoch;
        private final String partition;
        private boolean active;
        // With no operation-scoped completion receipt, a lease can start
        // exactly ONE physical exchange. Otherwise a late duplicate completion
        // could finish a subsequent operation under the same lease token.
        private boolean everBegun;
        private boolean revoked;

        private Lease(TcpPoolLeaseLedger owner, int slot, long generation,
                      ActorIdentity actor, long epoch, String partition) {
            this.owner = owner;
            this.slot = slot;
            this.generation = generation;
            this.actor = actor;
            this.epoch = epoch;
            this.partition = partition;
        }

        long generation() { return generation; }
    }

    private static final class Slot {
        private long generation = 1;
        private Lease current;
        private boolean quarantine;
    }

    private final ReentrantLock gate = new ReentrantLock();
    private final Slot[] slots;
    private final String partition;
    private long epoch;
    private boolean stopped;

    TcpPoolLeaseLedger(int capacity, String partition, long epoch) {
        if (capacity < 1 || capacity > 1024) {
            throw new IllegalArgumentException("invalid TCP pool slot count");
        }
        Objects.requireNonNull(partition, "partition");
        if (partition.isBlank() || !partition.equals(partition.strip())) {
            throw new IllegalArgumentException("canonical TCP pool partition required");
        }
        if (epoch <= 0) throw new IllegalArgumentException("invalid policy epoch");
        this.partition = partition;
        this.epoch = epoch;
        slots = new Slot[capacity];
        for (int index = 0; index < capacity; index++) slots[index] = new Slot();
    }

    /** Null means rejected (busy, wrong authority, stopped, or no capacity). */
    Lease tryAcquire(int index, ActorIdentity actor, String requestedPartition,
                     long requestedEpoch) {
        if (actor == null || actor.id() <= 0 || actor.incarnation() <= 0) return null;
        if (!gate.tryLock()) return null;
        try {
            if (stopped || requestedEpoch != epoch ||
                    !partition.equals(requestedPartition) ||
                    index < 0 || index >= slots.length) return null;
            Slot state = slots[index];
            if (state.generation == Long.MAX_VALUE || state.current != null ||
                    state.quarantine) return null;
            Lease token = new Lease(this, index, state.generation, actor,
                                    epoch, partition);
            state.current = token;
            return token;
        } finally {
            gate.unlock();
        }
    }

    boolean tryBegin(Lease token, ActorIdentity actor, long requestedEpoch) {
        if (!gate.tryLock()) return false;
        try {
            if (!valid(token, actor, requestedEpoch) || token.everBegun) return false;
            token.everBegun = true;
            token.active = true;
            return true;
        } finally {
            gate.unlock();
        }
    }

    /** Trusted reactor path: actor identity may be gone at cancellation time. */
    boolean tryComplete(Lease token) {
        if (!gate.tryLock()) return false;
        try {
            if (!owns(token) || !token.active) return false;
            token.active = false;
            if (token.revoked) retire(token);
            return true;
        } finally {
            gate.unlock();
        }
    }

    boolean tryRelease(Lease token, ActorIdentity actor, long requestedEpoch) {
        if (!gate.tryLock()) return false;
        try {
            if (!valid(token, actor, requestedEpoch)) return false;
            token.revoked = true;
            if (!token.active) retire(token);
            return true;
        } finally {
            gate.unlock();
        }
    }

    boolean tryRevokeEpoch(long next) {
        if (!gate.tryLock()) return false;
        try {
            if (stopped || next <= epoch) return false;
            epoch = next;
            for (Slot slot : slots) {
                // Revoking a policy fences even idle, pre-connected sockets.
                // They may have been authenticated under the old epoch.
                slot.quarantine = true;
                if (slot.current == null) continue;
                Lease token = slot.current;
                token.revoked = true;
                if (!token.active) retire(token);
            }
            return true;
        } finally {
            gate.unlock();
        }
    }

    boolean tryStop() {
        if (!gate.tryLock()) return false;
        try {
            stopped = true;
            for (Slot slot : slots) {
                if (slot.current == null) continue;
                Lease token = slot.current;
                token.revoked = true;
                if (!token.active) retire(token);
            }
            return true;
        } finally {
            gate.unlock();
        }
    }

    /**
     * Trusted transport owner calls ONLY after the old physical connection
     * has actually been closed and all I/O/buffer completions are quiescent.
     * An actor cannot present a lease token as proof of physical closure.
     */
    boolean tryConfirmPhysicalClose(int index, long expectedGeneration) {
        if (!gate.tryLock()) return false;
        try {
            if (stopped || index < 0 || index >= slots.length) return false;
            Slot state = slots[index];
            if (!state.quarantine || state.current != null ||
                    state.generation != expectedGeneration) return false;
            state.quarantine = false;
            return true;
        } finally {
            gate.unlock();
        }
    }

    private boolean owns(Lease token) {
        if (token == null || token.owner != this || token.slot < 0 ||
                token.slot >= slots.length) return false;
        Slot slot = slots[token.slot];
        return slot.current == token && slot.generation == token.generation;
    }

    private boolean valid(Lease token, ActorIdentity actor, long requestedEpoch) {
        return owns(token) && !token.revoked && !stopped &&
                token.actor.equals(actor) && token.epoch == requestedEpoch &&
                requestedEpoch == epoch && token.partition.equals(partition);
    }

    private void retire(Lease token) {
        Slot slot = slots[token.slot];
        slot.current = null;
        slot.quarantine = true; // Never reuse until the trusted close fence.
        token.revoked = true;
        if (slot.generation < Long.MAX_VALUE) slot.generation++;
    }
}
