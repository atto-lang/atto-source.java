package dev.oreslang.runtime;

/**
 * A linearizable host-only admission fence for a reusable HTTP actor worker.
 *
 * A dispatch reserves a handoff before the worker reference can escape the
 * Handler pool lock. Shutdown seals the worker before observing its physical
 * carrier. No later dispatch may reserve the sealed incarnation; one that
 * already reserved must relinquish its lease before quiescence is reported.
 *
 * This fence is not an actor mailbox-drain or physical-carrier receipt:
 * callers must combine quiescent() with ActorRuntime.httpCarrierQuiescent().
 */
final class HttpPooledWorkerAdmissionFence {
    private boolean sealed;
    private int handoffs;

    synchronized boolean tryReserve() {
        if (sealed) return false;
        if (handoffs == Integer.MAX_VALUE) {
            throw new IllegalStateException("HTTP worker handoff count exhausted");
        }
        handoffs++;
        return true;
    }

    synchronized void release() {
        if (handoffs == 0) {
            throw new IllegalStateException("HTTP worker handoff released twice");
        }
        handoffs--;
    }

    synchronized void seal() {
        sealed = true;
    }

    synchronized boolean quiescent() {
        return sealed && handoffs == 0;
    }

    synchronized boolean sealed() {
        return sealed;
    }

    synchronized int outstanding() {
        return handoffs;
    }
}
