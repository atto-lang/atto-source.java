package dev.oreslang.runtime;

/**
 * Trusted-host lifecycle REFERENCE, not a guest API or an operating-system
 * sandbox. A trusted adapter must authenticate the actor and authorize BOTH
 * the requested resource and actual selected destination before reserve().
 * Synchronization here protects the model, not native socket lifetimes.
 */
final class HostCapabilityIoLifecycleV1 {
    public record Ticket(int slot, long nonce) { }
    private enum Phase { FREE, RESERVED, DISPATCHED, CANCEL_REQUESTED, SETTLED }
    private static final class Slot {
        long actor, incarnation, selected, nonce;
        Phase phase = Phase.FREE;
    }
    private record Revoked(long actor, long incarnation) { }
    private final Slot[] slots;
    private final Revoked[] revoked;
    private int inFlight, revokedCount;
    private long nextNonce;
    private boolean closed;

    public HostCapabilityIoLifecycleV1(int capacity, int maxRevocations) {
        if (capacity < 1 || capacity > 65536 || maxRevocations < 1 || maxRevocations > 65536)
            throw new IllegalArgumentException("bounded lifecycle capacity required");
        slots = new Slot[capacity];
        for (int i = 0; i < capacity; ++i) slots[i] = new Slot();
        revoked = new Revoked[maxRevocations];
    }

    public synchronized Ticket reserve(boolean authenticated,
            boolean requestedAllowed, boolean selectedAllowed,
            long actor, long incarnation, long selectedBinding) {
        if (closed || !authenticated || !requestedAllowed || !selectedAllowed ||
                actor <= 0 || incarnation <= 0 || selectedBinding <= 0 ||
                isRevoked(actor, incarnation)) return null;
        for (int i = 0; i < slots.length; ++i) {
            var slot = slots[i];
            if (slot.phase != Phase.FREE) continue;
            if (nextNonce == Long.MAX_VALUE) { closed = true; return null; }
            slot.actor = actor;
            slot.incarnation = incarnation;
            slot.selected = selectedBinding;
            slot.nonce = ++nextNonce;
            slot.phase = Phase.RESERVED;
            ++inFlight;
            return new Ticket(i, slot.nonce);
        }
        return null;
    }

    public synchronized boolean dispatch(Ticket ticket, long actor,
            long incarnation, long actualBinding) {
        var slot = owned(ticket, actor, incarnation);
        if (closed || slot == null || slot.phase != Phase.RESERVED ||
                slot.selected != actualBinding || isRevoked(actor, incarnation))
            return false;
        slot.phase = Phase.DISPATCHED;
        return true;
    }

    public synchronized boolean cancel(Ticket ticket, long actor, long incarnation) {
        var slot = owned(ticket, actor, incarnation);
        if (slot == null || (slot.phase != Phase.RESERVED && slot.phase != Phase.DISPATCHED))
            return false;
        slot.phase = Phase.CANCEL_REQUESTED;
        return true;
    }

    public synchronized boolean complete(Ticket ticket, long actor, long incarnation) {
        var slot = owned(ticket, actor, incarnation);
        if (slot == null || slot.phase == Phase.SETTLED || slot.phase == Phase.FREE)
            return false;
        slot.phase = Phase.SETTLED;
        return true;
    }

    /** Only the trusted adapter may attest actual transport/descriptor quiescence. */
    public synchronized boolean retire(Ticket ticket, long actor,
            long incarnation, boolean physicalQuiescence) {
        var slot = owned(ticket, actor, incarnation);
        if (slot == null || !physicalQuiescence ||
                (slot.phase != Phase.SETTLED && slot.phase != Phase.CANCEL_REQUESTED))
            return false;
        slot.phase = Phase.FREE;
        slot.actor = slot.incarnation = slot.selected = slot.nonce = 0;
        --inFlight;
        return true;
    }

    /** Stop all new host operations, preserving every credit until real close. */
    public synchronized boolean shutdown() {
        if (closed) return false;
        closeAll();
        return true;
    }

    private void closeAll() {
        closed = true;
        for (Slot slot : slots) {
            if (slot.phase == Phase.RESERVED || slot.phase == Phase.DISPATCHED)
                slot.phase = Phase.CANCEL_REQUESTED;
        }
    }

    public synchronized boolean revokeActor(long actor, long incarnation) {
        if (actor <= 0 || incarnation <= 0 || isRevoked(actor, incarnation)) return false;
        if (revokedCount >= revoked.length) closeAll();
        else revoked[revokedCount++] = new Revoked(actor, incarnation);
        for (Slot slot : slots) {
            if (slot.actor == actor && slot.incarnation == incarnation &&
                    (slot.phase == Phase.RESERVED || slot.phase == Phase.DISPATCHED))
                slot.phase = Phase.CANCEL_REQUESTED;
        }
        return true;
    }

    public synchronized int inFlight() { return inFlight; }

    private Slot owned(Ticket ticket, long actor, long incarnation) {
        if (ticket == null || ticket.nonce() == 0 ||
                ticket.slot() < 0 || ticket.slot() >= slots.length) return null;
        var slot = slots[ticket.slot()];
        return slot.nonce == ticket.nonce() && slot.actor == actor &&
                slot.incarnation == incarnation ? slot : null;
    }
    private boolean isRevoked(long actor, long incarnation) {
        for (int i = 0; i < revokedCount; ++i)
            if (revoked[i].actor() == actor && revoked[i].incarnation() == incarnation)
                return true;
        return false;
    }
}
