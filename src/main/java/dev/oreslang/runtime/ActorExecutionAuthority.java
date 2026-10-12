package dev.oreslang.runtime;

import java.util.Optional;

/**
 * Host/supervisor-owned, per-actor execution authority. This is a local
 * safety gate, not a distributed lease or an external-effect fencing service.
 *
 * A claim is held for the entire physical guest-code entry. Suspending an
 * Oreslang computation is not permission to overlap carriers: leave() must
 * run after the previous guest turn has unwound. No timeout revokes an owner.
 *
 * All transitions are linearized on this monitor. A stale claim cannot
 * release or impersonate a successor turn, even after actor reincarnation.
 */
public final class ActorExecutionAuthority {
    public enum Phase { READY, RUNNING, SUSPENDED, STOPPING, TERMINATED }
    public record Claim(long generation, long sequence, long carrierId) {}

    private long generation = 1;
    private long sequence;
    private long owner;
    // The Claim is an opaque, identity-sensitive local capability. Numerical
    // sequence/carrier metadata alone must never permit a forged release.
    private Claim activeClaim;
    private Phase phase = Phase.READY;

    public synchronized long generation() { return generation; }
    public synchronized Phase phase() { return phase; }
    public synchronized boolean ownerActive() { return owner != 0; }

    /** Returns empty on contention, stale generation or terminal authority. */
    public synchronized Optional<Claim> enter(long expectedGeneration, long carrierId) {
        if (carrierId <= 0 || expectedGeneration != generation || owner != 0 ||
                (phase != Phase.READY && phase != Phase.SUSPENDED)) return Optional.empty();
        if (sequence == Long.MAX_VALUE) return Optional.empty(); // no ABA wrap
        owner = carrierId;
        phase = Phase.RUNNING;
        activeClaim = new Claim(generation, ++sequence, owner);
        return Optional.of(activeClaim);
    }

    /**
     * The caller MUST have completely left the guest-code boundary before
     * calling leave(). A suspension releases the physical carrier, not the
     * logical actor's identity or protected ownership.
     */
    public synchronized boolean leave(Claim claim, boolean suspended) {
        if (!owns(claim) || (phase != Phase.RUNNING && phase != Phase.STOPPING))
            return false;
        owner = 0;
        activeClaim = null;
        if (phase != Phase.STOPPING) phase = suspended ? Phase.SUSPENDED : Phase.READY;
        return true;
    }

    /** Stop seals entry immediately; an executing turn retains its owner. */
    public synchronized boolean requestStop(long expectedGeneration) {
        if (expectedGeneration != generation || phase == Phase.TERMINATED ||
                phase == Phase.STOPPING) return false;
        phase = Phase.STOPPING;
        return true;
    }

    /**
     * Only a trusted supervisor can acknowledge retirement, after carrier
     * exit and guest-context teardown. In-progress owners cannot be revoked.
     */
    public synchronized boolean acknowledgeRetired(long expectedGeneration) {
        if (expectedGeneration != generation || phase != Phase.STOPPING ||
                owner != 0 || activeClaim != null) return false;
        phase = Phase.TERMINATED;
        return true;
    }

    /**
     * Explicitly reincarnates a retired actor; exhaustion fails closed.
     * A previous incarnation's Claim cannot pass generation equality.
     */
    public synchronized Optional<Long> restart() {
        if (phase != Phase.TERMINATED || owner != 0 || activeClaim != null ||
                generation == Long.MAX_VALUE || sequence == Long.MAX_VALUE)
            return Optional.empty();
        generation++;
        phase = Phase.READY;
        return Optional.of(generation);
    }

    public synchronized boolean owns(Claim claim) {
        return claim != null && claim == activeClaim &&
                claim.carrierId() > 0 && owner == claim.carrierId() &&
                claim.generation() == generation && claim.sequence() == sequence;
    }
}
