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
    private boolean physicalEntryUnproven;

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
            // An inline scheduler callback on the host's own thread must
            // NOT bypass the exclusive scoped Future handoff. Reentrant
            // guest entry under Value.execute is not a fresh guest lease.
            if (hostOwner == Thread.currentThread()) {
                throw new IllegalStateException(
                        "guest turn cannot reserve during its own host Future scope");
            }
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

    /**
     * Shutdown must not retire code or I/O while the host still owns a
     * scoped Value.execute. Called after sealing new claims, before tearing
     * down any actor/root/async dependencies. Retry remains possible.
     */
    synchronized void requireNoActiveHostClaim() {
        if (hostOwner != null || depth != 0) {
            throw new IllegalStateException(
                    "unfinished host Future handoff: active Value.execute scope");
        }
    }

    /**
     * A poisoned physical entry/exit must reject teardown BEFORE retiring
     * any actor/root/async dependency. A later quiescence check still guards
     * non-poisoned, live guest reservations.
     */
    synchronized void requireProvenEntryAndExit() {
        if (physicalEntryUnproven || physicalExitUnproven) {
            throw new IllegalStateException(
                    "guest physical enter/leave unproven: context retirement denied");
        }
    }

    /**
     * Final physical barrier: guest entry reservations are released only
     * after TruffleContext.leave. A terminal Future is not enough.
     */
    synchronized void requirePhysicalQuiescence() {
        if (hostOwner != null || depth != 0 || guestTurnReservations != 0
                || physicalExitUnproven || physicalEntryUnproven) {
            throw new IllegalStateException(
                    "unfinished Truffle context entry or unproven physical guest enter/leave");
        }
    }

    /** A failed TruffleContext.enter cannot certify that no guest state was entered. */
    synchronized void quarantineUnknownPhysicalEntry() {
        physicalEntryUnproven = true;
        hostClaimsSealed = true;
        closing = true;
        notifyAll();
    }

    /** A failed TruffleContext.leave is NOT a successful physical exit. */
    synchronized void quarantineUnknownPhysicalExit() {
        physicalExitUnproven = true;
        hostClaimsSealed = true;
        closing = true;
        notifyAll();
    }

    synchronized boolean physicalEntryUnproven() {
        return physicalEntryUnproven;
    }

    synchronized boolean physicalExitUnproven() {
        return physicalExitUnproven;
    }

    synchronized void close() {
        closing = true;
        notifyAll();
    }
}
