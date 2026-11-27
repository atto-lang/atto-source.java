package dev.oreslang.runtime;

/** Host quota substrate only. Physical quiescence and guest lowering remain external. */
final class ActorSpawnReservation {
    record Charge(long heap, long stacks, long mailboxBytes, long messages) {
        Charge {
            if (heap <= 0 || stacks <= 0 || mailboxBytes <= 0 || messages <= 0)
                throw new IllegalArgumentException("E_SPAWN_QUOTA");
        }
    }
    record Usage(long heap, long stacks, long mailboxBytes, long messages) { }
    enum Phase { INITIALIZING, READY, RETIRING, RELEASED }
    private final Charge limit;
    private long heap, stacks, mailboxBytes, messages, generation;
    private final java.util.Set<Permit> active = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    ActorSpawnReservation(Charge limit) { this.limit = java.util.Objects.requireNonNull(limit); }

    synchronized Permit reserve(Charge charge) {
        return reserve(charge, Long.MAX_VALUE);
    }
    synchronized Permit reserve(Charge charge, long maxLiveReceives) {
        java.util.Objects.requireNonNull(charge);
        if (maxLiveReceives <= 0) throw new IllegalArgumentException("E_SPAWN_POLICY");
        if (charge.heap > limit.heap - heap || charge.stacks > limit.stacks - stacks ||
                charge.mailboxBytes > limit.mailboxBytes - mailboxBytes || charge.messages > limit.messages - messages)
            throw new IllegalStateException("E_SPAWN_QUOTA");
        if (generation == Long.MAX_VALUE) throw new IllegalStateException("E_SPAWN_GENERATION_EXHAUSTED");
        Permit p = new Permit(this, generation + 1, charge, maxLiveReceives);
        active.add(p);
        generation++;
        heap += charge.heap; stacks += charge.stacks;
        mailboxBytes += charge.mailboxBytes; messages += charge.messages;
        return p;
    }
    synchronized InitLease beginInit(Permit p) {
        requireActive(p);
        if (p.phase != Phase.INITIALIZING || p.initStarted)
            throw new IllegalStateException("E_SPAWN_INIT_AUTHORITY");
        InitLease lease = new InitLease(p);
        p.initStarted = true;
        p.initLease = lease;
        return lease;
    }
    /** Settle exactly once, including cancellation after retirement. */
    synchronized boolean completeInit(InitLease lease, boolean success) {
        if (lease == null) throw new IllegalStateException("E_SPAWN_INIT_AUTHORITY");
        Permit p = lease.permit;
        requireActive(p);
        if (p.initLease != lease) throw new IllegalStateException("E_SPAWN_INIT_AUTHORITY");
        p.initLease = null;
        if (p.phase == Phase.RETIRING || !success) {
            p.phase = Phase.RETIRING;
            return false;
        }
        p.phase = Phase.READY;
        return true;
    }
    /** Observation only; cannot authorize execution. */
    synchronized boolean mayReceive(Permit p) {
        return p != null && p.owner == this && active.contains(p) && p.phase == Phase.READY;
    }
    /** Logical receive lease; retains its slot across suspension. Physical scheduler limits are separate. */
    synchronized ReceiveLease tryEnterReceive(Permit p) {
        requireActive(p);
        if (p.phase != Phase.READY) return null;
        if (p.receives >= p.maxLiveReceives) return null;
        ReceiveLease lease = new ReceiveLease(p);
        p.receives++;
        return lease;
    }
    private synchronized void leaveReceive(ReceiveLease lease) {
        if (lease.closed) return;
        Permit p = lease.permit;
        requireActive(p);
        if (p.receives == 0) throw new IllegalStateException("E_SPAWN_LEASE_UNDERFLOW");
        p.receives--;
        lease.closed = true;
    }
    synchronized void retire(Permit p) {
        requireActive(p);
        p.phase = Phase.RETIRING;
    }
    /** Trusted dispatcher must also prove tasks, FFI, allocator and physical cleanup. */
    synchronized void releaseAfterQuiescence(Permit p) {
        requireActive(p);
        if (p.phase != Phase.RETIRING) throw new IllegalStateException("E_SPAWN_NOT_RETIRED");
        if (p.initLease != null || p.receives != 0) throw new IllegalStateException("E_SPAWN_IN_FLIGHT");
        active.remove(p);
        Charge c = p.charge;
        heap -= c.heap; stacks -= c.stacks;
        mailboxBytes -= c.mailboxBytes; messages -= c.messages;
        p.phase = Phase.RELEASED;
    }
    synchronized Usage usage() { return new Usage(heap, stacks, mailboxBytes, messages); }
    private void requireActive(Permit p) {
        if (p == null || p.owner != this || !active.contains(p))
            throw new IllegalStateException("E_SPAWN_STALE_PERMIT");
    }
    static final class Permit {
        private final ActorSpawnReservation owner;
        final long generation;
        private final Charge charge;
        private final long maxLiveReceives;
        private Phase phase = Phase.INITIALIZING;
        private boolean initStarted;
        private InitLease initLease;
        private long receives;
        private Permit(ActorSpawnReservation owner, long generation, Charge charge, long maxLiveReceives) {
            this.owner = owner; this.generation = generation; this.charge = charge;
            this.maxLiveReceives = maxLiveReceives;
        }
    }
    static final class InitLease {
        private final Permit permit;
        private InitLease(Permit permit) { this.permit = permit; }
    }
    static final class ReceiveLease implements AutoCloseable {
        private final Permit permit;
        private boolean closed;
        private ReceiveLease(Permit permit) { this.permit = permit; }
        @Override public void close() { permit.owner.leaveReceive(this); }
    }
}
