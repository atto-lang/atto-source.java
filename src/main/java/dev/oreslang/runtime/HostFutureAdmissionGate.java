package dev.oreslang.runtime;

/**
 * Context-local admission fence for the scoped public host-Future handoff.
 *
 * The host already owns the Truffle public call when begin() executes.
 * A newly scheduled CONTROL/actor/async guest turn must therefore defer
 * its TruffleContext.enter until the outer Value.execute has returned and
 * the host bridge has released its scope. Never hold a host monitor while
 * executing guest code or while waiting on the task's Future.
 *
 * This does NOT serialize arbitrary foreign Polyglot Value executions.
 */
final class HostFutureAdmissionGate {
    private Thread hostOwner;
    private int depth;
    private int guestTurnReservations;
    private boolean closing;
    private boolean hostClaimsSealed;
    private boolean physicalExitUnproven;

    synchronized void begin() {
        if (closing || hostClaimsSealed) throw new IllegalStateException(
                "host Future handoff rejected during context disposal");
        Thread caller = Thread.currentThread();
        if (guestTurnReservations != 0) {
            // A worker already reserved its context entry. Never admit a
            // host call over that worker's Truffle enter/leave boundary.
            throw new IllegalStateException(
                    "host Future handoff requires an idle guest-entry boundary");
        }
        if (hostOwner != null && hostOwner != caller) {
            throw new IllegalStateException(
                    "concurrent host Future handoff requires serialized context entry");
        }
        hostOwner = caller;
        depth++;
    }

    synchronized void end() {
        if (hostOwner != Thread.currentThread() || depth <= 0) {
            throw new IllegalStateException(
                    "host Future handoff release must be performed by its owner");
        }
        if (--depth == 0) {
            hostOwner = null;
            notifyAll();
        }
    }

    /**
     * Atomically reserve a guest entry against new host claims. Caller must
     * release the returned lease AFTER TruffleContext.leave, including
     * exceptional exits. This closes the wait-then-enter TOCTOU window.
     */
    synchronized Runnable reserveGuestTurn() {
        boolean interrupted = false;
        try {
            while (hostOwner != null && hostOwner != Thread.currentThread() && !closing) {
                try {
                    wait();
                } catch (InterruptedException failure) {
                    interrupted = true;
                    throw new java.util.concurrent.CancellationException(
                            "guest turn interrupted awaiting host Future handoff");
                }
            }
            if (closing) throw new java.util.concurrent.RejectedExecutionException(
                    "guest turn rejected after context disposal admission");
            guestTurnReservations++;
            Thread owner = Thread.currentThread();
            final boolean[] released = {false};
            return () -> {
                if (Thread.currentThread() != owner) {
                    throw new IllegalStateException(
                            "guest entry reservation released by a foreign carrier");
                }
                synchronized (HostFutureAdmissionGate.this) {
                    if (released[0] || guestTurnReservations <= 0) {
                        throw new IllegalStateException("guest entry reservation already released");
                    }
                    released[0] = true;
                    guestTurnReservations--;
                    HostFutureAdmissionGate.this.notifyAll();
                }
            };
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Close preflight: deny new host claims, but allow already-started guest cleanup. */
    synchronized void sealHostClaims() {
        hostClaimsSealed = true;
        notifyAll();
    }

    /** A failed TruffleContext.leave is NOT a successful physical exit. */
    synchronized void quarantineUnknownPhysicalExit() {
        physicalExitUnproven = true;
        hostClaimsSealed = true;
        closing = true;
        notifyAll();
    }

    synchronized boolean physicalExitUnproven() {
        return physicalExitUnproven;
    }

    synchronized void close() {
        closing = true;
        notifyAll();
    }
}
