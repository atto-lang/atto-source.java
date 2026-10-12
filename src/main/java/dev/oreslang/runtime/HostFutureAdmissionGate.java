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
    private boolean closing;

    synchronized void begin() {
        if (closing) throw new IllegalStateException(
                "host Future handoff rejected during context disposal");
        Thread caller = Thread.currentThread();
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

    synchronized void awaitGuestAdmission() {
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
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    synchronized void close() {
        closing = true;
        notifyAll();
    }
}
