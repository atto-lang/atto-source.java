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
        if (originatingReceive != null
                && (admission == null || !admission.issued(originatingReceive))) {
            throw new IllegalArgumentException(
                    "serial continuation lease must be issued by its actor admission gate");
        }
        this.admission = admission;
        this.originatingReceive = originatingReceive;
    }

    /** Cheap rejection before using a bounded continuation mailbox slot. */
    public boolean mayQueue() {
        if (originatingReceive != null) {
            return originatingReceive.state() != SerialActorAdmission.State.FINISHED;
        }
        // Unowned callbacks require one atomic open-and-idle observation.
        // The actor carrier checks it again before running user code.
        return admission == null || admission.canQueueUnownedContinuation();
    }

    /** Check on the real actor carrier immediately before executing callback. */
    public void requireRunnable() {
        if (originatingReceive != null) {
            originatingReceive.requireActive();
        } else if (admission != null) {
            admission.requireIdleActorScopedCallback();
        }
    }
}
