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

        boolean drained() {
            // All coordinator state transitions must use ONE monitor. The
            // Transaction monitor is not the coordinator monitor: mixing the
            // two permits data races between prepare, commit, rollback and
            // quarantine observations by independent supervisor threads.
            synchronized (LogicalActorUpgradeCoordinatorV1.this) {
                requireActive();
                try {
                    return ticket.drained() && code.livePins() == 0;
                } catch (RuntimeException | Error uncertain) {
                    poisoned = true;
                    throw uncertain;
                }
            }
        }

        boolean commit() {
            synchronized (LogicalActorUpgradeCoordinatorV1.this) {
                requireActive();
                if (!drained()) return false;

                // There is no safe rollback after publication begins: even a
                // thrown publish() call is not proof that the old image is
                // authoritative. Quarantine first; only clear it after BOTH
                // code and proxy epochs are certified to have advanced.
                poisoned = true;
                try {
                    if (!code.publish(candidate)) {
                        throw new IllegalStateException(
                                "code gate refused publication after verified drainage");
                    }
                    ticket.commit();
                    if (code.epoch() != mailbox.snapshot().routeEpoch()) {
                        throw new IllegalStateException(
                                "actor code and proxy route epochs diverged after publication");
                    }
                    done = true;
                    preparing = false;
                    poisoned = false;
                    return true;
                } catch (RuntimeException | Error failure) {
                    done = true;
                    preparing = false;
                    throw new IllegalStateException(
                            "actor upgrade publication not fully certified: "
                                    + "dispatch remains quarantined", failure);
                }
            }
        }

        void rollback() {
            synchronized (LogicalActorUpgradeCoordinatorV1.this) {
                requireActive();
                try {
                    // Only a proven candidate abort permits old-route DATA
                    // dispatch to reopen. A failed abort leaves the mailbox
                    // frozen even if it appears that publication did not run.
                    code.abort(candidate);
                    ticket.rollback();
                    done = true;
                    preparing = false;
                } catch (RuntimeException | Error failure) {
                    poisoned = true;
                    done = true;
                    preparing = false;
                    throw new IllegalStateException(
                            "actor upgrade rollback not fully certified: "
                                    + "dispatch remains quarantined", failure);
                }
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
