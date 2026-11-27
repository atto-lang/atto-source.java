package dev.oreslang.runtime;

/** Host-only quota substrate. No guest access, allocation, scheduler, or AOT proof. */
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
        java.util.Objects.requireNonNull(charge);
        if (charge.heap > limit.heap - heap || charge.stacks > limit.stacks - stacks ||
                charge.mailboxBytes > limit.mailboxBytes - mailboxBytes || charge.messages > limit.messages - messages)
            throw new IllegalStateException("E_SPAWN_QUOTA");
        if (generation == Long.MAX_VALUE) throw new IllegalStateException("E_SPAWN_GENERATION_EXHAUSTED");
        // Create the host bookkeeping before committing counters. No guest allocation here.
        Permit permit = new Permit(this, generation + 1, charge);
        active.add(permit);
        generation++;
        heap += charge.heap; stacks += charge.stacks;
        mailboxBytes += charge.mailboxBytes; messages += charge.messages;
        return permit;
    }
    synchronized void beginInit(Permit p) {
        requireActive(p);
        if (p.phase != Phase.INITIALIZING || p.initStarted)
            throw new IllegalStateException("E_SPAWN_INIT_AUTHORITY");
        p.initStarted = true;
    }
    synchronized boolean completeInit(Permit p) {
        requireActive(p);
        if (p.phase == Phase.RETIRING) return false;
        if (p.phase != Phase.INITIALIZING || !p.initStarted)
            throw new IllegalStateException("E_SPAWN_INIT_AUTHORITY");
        p.phase = Phase.READY;
        return true;
    }
    synchronized boolean mayReceive(Permit p) {
        return p != null && p.owner == this && active.contains(p) && p.phase == Phase.READY;
    }
    synchronized void retire(Permit p) {
        requireActive(p);
        p.phase = Phase.RETIRING;
    }
    /** Trusted dispatcher only, AFTER real physical quiescence and cleanup.
     * This host prototype cannot establish those prerequisites itself. */
    synchronized void releaseAfterQuiescence(Permit p) {
        requireActive(p);
        if (p.phase != Phase.RETIRING) throw new IllegalStateException("E_SPAWN_NOT_RETIRED");
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
        private Phase phase = Phase.INITIALIZING;
        private boolean initStarted;
        private Permit(ActorSpawnReservation owner, long generation, Charge charge) {
            this.owner = owner; this.generation = generation; this.charge = charge;
        }
    }
}
