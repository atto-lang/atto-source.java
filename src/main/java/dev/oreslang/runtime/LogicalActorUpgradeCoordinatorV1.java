package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Host-only, single-owner integration seam between a bounded proxy mailbox
 * watermark and an actor code-generation epoch. This is NOT a live ActorRef
 * router: source ActorRuntime dispatch must be routed through the same
 * trusted CONTROL gate before this may be used for production publication.
 *
 * The mailbox is deliberately committed LAST. If gate publication succeeds
 * but mailbox publication fails, the proxy remains frozen; it is forbidden
 * to roll back dispatch to code that has ceased to be authoritative.
 */
final class LogicalActorUpgradeCoordinatorV1 implements AutoCloseable {
    private final ActorUpgradeEpochGate code;
    private final LogicalActorProxyMailboxV1 mailbox;
    private boolean preparing;
    private boolean poisoned;

    LogicalActorUpgradeCoordinatorV1(
            ActorUpgradeEpochGate code, LogicalActorProxyMailboxV1 mailbox) {
        this.code = Objects.requireNonNull(code, "code");
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox");
        if (code.epoch() != mailbox.snapshot().routeEpoch())
            throw new IllegalArgumentException("actor code and mailbox epochs differ");
    }

    synchronized Transaction prepare(ActorUpgradeEpochGate.Image image) {
        if (poisoned || preparing)
            throw new IllegalStateException("actor upgrade is already active or quarantined");
        if (code.epoch() != mailbox.snapshot().routeEpoch())
            throw new IllegalStateException("code and mailbox route epochs diverged");
        // Reserve mailbox freeze first, so new DATA dispatch cannot pass this
        // point. Existing dispatched messages retain their old route ownership.
        LogicalActorProxyMailboxV1.FreezeTicket frozen =
                mailbox.freezeDispatchForUpgrade();
        try {
            ActorUpgradeEpochGate.Candidate candidate = code.prepare(image);
            preparing = true;
            return new Transaction(frozen, candidate);
        } catch (RuntimeException | Error failure) {
            try {
                frozen.rollback();
            } catch (RuntimeException | Error rollback) {
                poisoned = true;
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    final class Transaction {
        private final LogicalActorProxyMailboxV1.FreezeTicket ticket;
        private final ActorUpgradeEpochGate.Candidate candidate;
        private boolean done;

        private Transaction(LogicalActorProxyMailboxV1.FreezeTicket ticket,
                            ActorUpgradeEpochGate.Candidate candidate) {
            this.ticket = ticket;
            this.candidate = candidate;
        }

        synchronized boolean drained() {
            requireActive();
            return ticket.drained() && code.livePins() == 0;
        }

        synchronized boolean commit() {
            requireActive();
            if (!drained()) return false;
            // The physical source ActorRef route still requires its own
            // atomic swap and health proof before this host seam is connected.
            // No user callbacks or guest code run under this monitor.
            if (!code.publish(candidate)) return false;
            // This is the point of no return. Any failure thereafter must
            // keep dispatch sealed and quarantine rather than old-route retry.
            poisoned = true;
            try {
                ticket.commit();
                done = true;
                preparing = false;
                poisoned = false;
                return true;
            } catch (RuntimeException | Error failure) {
                done = true;
                preparing = false;
                throw new IllegalStateException(
                        "code generation published but mailbox did not: "
                                + "dispatch remains quarantined", failure);
            }
        }

        synchronized void rollback() {
            requireActive();
            // Abort the candidate before reopening old DATA dispatch.
            code.abort(candidate);
            try {
                ticket.rollback();
                done = true;
                preparing = false;
            } catch (RuntimeException | Error failure) {
                poisoned = true;
                done = true;
                preparing = false;
                throw failure;
            }
        }

        private void requireActive() {
            if (done || !preparing || poisoned)
                throw new IllegalStateException("stale or quarantined actor upgrade");
        }
    }

    synchronized boolean quarantined() { return poisoned; }

    @Override public synchronized void close() {
        // Do not implicitly unfreeze on close: a failed or ambiguous commit
        // cannot be classified as safe rollback by Java object lifetime.
        if (preparing || poisoned)
            throw new IllegalStateException("upgrade must be explicitly resolved "
                    + "or quarantined by trusted supervisor");
    }
}
