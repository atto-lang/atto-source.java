package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime-internal epoch registry for ActorGroup continuation wakeups.
 *
 * <p>Register/claim/release, group seal, and member revocation are linearized
 * on one monitor. Claiming a wakeup is not permission to run guest code: the
 * owning scheduler still performs mailbox admission and the actor-wide
 * execution-lease check. A claimed wake stays tracked until release, so a
 * group cannot reclaim an old frame merely because it was enqueued.</p>
 *
 * <p>The registry does not create a new actor scheduler, run callbacks, expose
 * guest permissions, or pin native memory. It provides the generation/ABA
 * fencing primitive required for a future group-owned continuation adapter.</p>
 */
public final class ActorGroupWakeRegistry {
    public static final class Ticket {
        private final ActorGroupWakeRegistry registry;
        private final Object owner;
        private final long groupEpoch;
        private final long ownerEpoch;
        private final long sequence;
        private State state = State.PENDING;

        private Ticket(ActorGroupWakeRegistry registry, Object owner,
                       long groupEpoch, long ownerEpoch, long sequence) {
            this.registry = registry;
            this.owner = owner;
            this.groupEpoch = groupEpoch;
            this.ownerEpoch = ownerEpoch;
            this.sequence = sequence;
        }

        public long sequence() {
            return sequence;
        }
    }

    private enum State { PENDING, CLAIMED, RELEASED }

    private final Map<Object, Long> ownerEpochs = new HashMap<>();
    private final Map<Object, Set<Ticket>> tickets = new HashMap<>();
    private long groupEpoch = 1;
    private long nextSequence;
    private boolean sealed;

    /**
     * Called only after actor-group membership/host authority is verified.
     * Caller-provided Object is an internal actor identity, not a guest token.
     */
    public synchronized Ticket register(Object owner) {
        Objects.requireNonNull(owner, "owner");
        if (sealed) throw new IllegalStateException("ActorGroup wake registry is sealed");
        long sequence = Math.incrementExact(nextSequence);
        nextSequence = sequence;
        Ticket ticket = new Ticket(this, owner, groupEpoch,
                ownerEpochs.getOrDefault(owner, 1L), sequence);
        tickets.computeIfAbsent(owner, ignored -> new HashSet<>()).add(ticket);
        return ticket;
    }

    /**
     * Only the first completion of a registered epoch can win. The claimed
     * ticket remains a retirement root while its scheduler dispatch is queued.
     */
    public synchronized boolean claim(Ticket ticket) {
        if (!isCurrentInternal(ticket) || ticket.state != State.PENDING) return false;
        ticket.state = State.CLAIMED;
        return true;
    }

    /** Recheck when admitting/resuming a queued callback after a group change. */
    public synchronized boolean current(Ticket ticket) {
        return isCurrentInternal(ticket) && ticket.state != State.RELEASED;
    }

    /** Task termination or settled dispatch releases the retained wake root. */
    public synchronized boolean release(Ticket ticket) {
        if (ticket == null || ticket.registry != this
                || ticket.state == State.RELEASED) return false;
        ticket.state = State.RELEASED;
        Set<Ticket> pending = tickets.get(ticket.owner);
        if (pending != null) {
            pending.remove(ticket);
            if (pending.isEmpty()) tickets.remove(ticket.owner);
        }
        return true;
    }

    /** A leaving/cancelled actor invalidates even already-claimed callbacks. */
    public synchronized void revokeOwner(Object owner) {
        Objects.requireNonNull(owner, "owner");
        long previous = ownerEpochs.getOrDefault(owner, 1L);
        ownerEpochs.put(owner, Math.incrementExact(previous));
        Set<Ticket> owned = tickets.remove(owner);
        if (owned != null) {
            for (Ticket ticket : owned) ticket.state = State.RELEASED;
        }
    }

    /** Fail closed: invalidate all old tickets and prevent future admission. */
    public synchronized void seal() {
        if (sealed) return;
        // Never wrap an epoch: ABA could alias an old group generation.
        groupEpoch = Math.incrementExact(groupEpoch);
        sealed = true;
        for (Set<Ticket> byOwner : tickets.values()) {
            for (Ticket ticket : byOwner) ticket.state = State.RELEASED;
        }
        tickets.clear();
        ownerEpochs.clear();
    }

    public synchronized boolean sealed() {
        return sealed;
    }

    /** Number of pending plus claimed continuation roots, not merely callbacks. */
    public synchronized int outstanding() {
        int count = 0;
        for (Set<Ticket> byOwner : tickets.values()) {
            count = Math.addExact(count, byOwner.size());
        }
        return count;
    }

    private boolean isCurrentInternal(Ticket ticket) {
        return ticket != null
                && ticket.registry == this
                && !sealed
                && ticket.groupEpoch == groupEpoch
                && ticket.ownerEpoch == ownerEpochs.getOrDefault(ticket.owner, 1L);
    }
}
