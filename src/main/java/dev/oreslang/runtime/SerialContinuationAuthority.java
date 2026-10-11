package dev.oreslang.runtime;

/**
 * Host-only continuation provenance, captured before a completion may leave
 * the actor carrier. Not a guest-created capability and not a scheduler ticket.
 *
 * A callback from a prior serial receive cannot borrow a later receive's
 * admission. An actor-scoped callback with no receive owner may only run while
 * the serial actor is idle; it must never enter during another handler.
 */
public final class SerialContinuationAuthority {
    private final SerialActorAdmission admission;
    private final SerialActorAdmission.Lease originatingReceive;

    public SerialContinuationAuthority(
            SerialActorAdmission admission,
            SerialActorAdmission.Lease originatingReceive) {
        if (admission == null && originatingReceive != null) {
            throw new IllegalArgumentException(
                    "serial continuation lease requires its admission gate");
        }
        this.admission = admission;
        this.originatingReceive = originatingReceive;
    }

    /** Cheap rejection before using a bounded continuation mailbox slot. */
    public boolean mayQueue() {
        return originatingReceive == null
                || originatingReceive.state() != SerialActorAdmission.State.FINISHED;
    }

    /** Check on the real actor carrier immediately before executing callback. */
    public void requireRunnable() {
        if (originatingReceive != null) {
            originatingReceive.requireActive();
        } else if (admission != null && admission.busy()) {
            throw new IllegalStateException(
                    "unbound continuation cannot enter a running serial handler");
        }
    }
}
