package dev.oreslang.runtime;

/** Fixed-profile host charge calculation; not pool authorization or allocation. */
final class ActorSpawnChargePlan {
    enum Affinity { NONE, STICKY, PINNED }
    record Policy(boolean serial, int carrierCap, int defaultCarriers,
                  long liveStackSlots, long heapLimit, long stackLimit,
                  long mailboxMessagesLimit, long mailboxByteCharge) {
        Policy {
            if (carrierCap <= 0 || defaultCarriers <= 0 || defaultCarriers > carrierCap
                    || liveStackSlots <= 0 || heapLimit <= 0 || stackLimit <= 0
                    || mailboxMessagesLimit <= 0 || mailboxByteCharge <= 0)
                throw new IllegalArgumentException("E_SPAWN_POLICY");
            if (serial && (carrierCap != 1 || defaultCarriers != 1))
                throw new IllegalArgumentException("E_SPAWN_POLICY");
        }
    }
    record Request(Integer carriers, Affinity affinity, Long heapBytes,
                   Long stackBytes, Long mailboxMessages) { }
    record Defaults(Affinity affinity, long heapBytes, long stackBytes, long mailboxMessages) { }
    record Plan(int carrierCap, Affinity affinity, long stackBytes,
                ActorSpawnReservation.Charge charge) { }

    static Plan resolve(Policy policy, Defaults defaults, Request request) {
        java.util.Objects.requireNonNull(policy);
        java.util.Objects.requireNonNull(defaults);
        java.util.Objects.requireNonNull(request);
        // Validate defaults independently: overrides may not hide malformed metadata.
        checked(policy, policy.defaultCarriers(), defaults.affinity(), defaults.heapBytes(),
                defaults.stackBytes(), defaults.mailboxMessages());
        return checked(policy, request.carriers() == null ? policy.defaultCarriers() : request.carriers(),
                request.affinity() == null ? defaults.affinity() : request.affinity(),
                request.heapBytes() == null ? defaults.heapBytes() : request.heapBytes(),
                request.stackBytes() == null ? defaults.stackBytes() : request.stackBytes(),
                request.mailboxMessages() == null ? defaults.mailboxMessages() : request.mailboxMessages());
    }
    private static Plan checked(Policy p, int carriers, Affinity affinity, long heap, long stack, long messages) {
        if (carriers <= 0 || carriers > p.carrierCap() || affinity == null
                || (affinity == Affinity.PINNED && carriers != 1))
            throw new IllegalArgumentException("E_SPAWN_POLICY");
        if (heap <= 0 || heap > p.heapLimit() || stack <= 0 || stack > p.stackLimit()
                || messages <= 0 || messages > p.mailboxMessagesLimit())
            throw new IllegalArgumentException("E_SPAWN_QUOTA");
        // Live stacks include suspended invocations; never multiply by physical carrier count.
        long stacks;
        try { stacks = Math.multiplyExact(stack, p.liveStackSlots()); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("E_SPAWN_QUOTA", e); }
        // Mailbox bytes are a trusted independent charge, not derived from message count.
        return new Plan(carriers, affinity, stack,
                new ActorSpawnReservation.Charge(heap, stacks, p.mailboxByteCharge(), messages));
    }
    private ActorSpawnChargePlan() { }
}
