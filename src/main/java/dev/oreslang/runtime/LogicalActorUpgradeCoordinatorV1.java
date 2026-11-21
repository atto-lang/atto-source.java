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
    private boolean closed;
    // Guarded by the coordinator monitor, never by a transaction-local lock.
    private Transaction active;

    LogicalActorUpgradeCoordinatorV1(
            ActorUpgradeEpochGate code, LogicalActorProxyMailboxV1 mailbox) {
        this.code = Objects.requireNonNull(code, "code");
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox");
        if (code.epoch() != mailbox.snapshot().routeEpoch())
            throw new IllegalArgumentException("actor code and mailbox epochs differ");
    }

    synchronized Transaction prepare(ActorUpgradeEpochGate.Image image) {
        if (closed)
            throw new IllegalStateException("actor upgrade coordinator is closed");
        if (poisoned || preparing)
            throw new IllegalStateException("actor upgrade is already active or quarantined");

        // Lock order for every cross-ledger transition is:
        // coordinator -> code gate -> mailbox. ActorUpgradeEpochGate.close()
        // uses the same code monitor, so retirement cannot slip between a
        // mailbox freeze/thaw and the corresponding code-state transition.
        synchronized (code) {
            final LogicalActorProxyMailboxV1.Snapshot snapshot;
            try {
                snapshot = mailbox.snapshot();
            } catch (IllegalStateException stateFailure) {
                poisoned = true;
                throw stateFailure;
            }
            if (code.epoch() != snapshot.routeEpoch()) {
                poisoned = true;
                throw new IllegalStateException("code and mailbox route epochs diverged");
            }

            final LogicalActorProxyMailboxV1.FreezeTicket frozen;
            try {
                // Freeze dispatch before staging code. Admission remains
                // bounded/open. Allocation failure in freeze is pre-mutation.
                frozen = mailbox.freezeDispatchForUpgrade();
            } catch (IllegalStateException stateFailure) {
                poisoned = true;
                throw stateFailure;
            }

            ActorUpgradeEpochGate.Candidate candidate = null;
            try {
                candidate = code.prepare(image);
                // Allocate the transaction before publishing it as active.
                Transaction transaction = new Transaction(frozen, candidate);
                active = transaction;
                preparing = true;
                return transaction;
            } catch (RuntimeException | Error failure) {
                // Allocation can fail AFTER code.prepare succeeded. Do not
                // leave the code candidate frozen when rolling back the
                // mailbox ticket.
                if (candidate != null) {
                    try {
                        code.abort(candidate);
                    } catch (RuntimeException | Error abortFailure) {
                        poisoned = true;
                        if (abortFailure != failure) failure.addSuppressed(abortFailure);
                        // Never resume old dispatch without a proven code abort.
                        throw failure;
                    }
                } else if (failure instanceof IllegalStateException) {
                    // A lifecycle/state refusal from the code gate means the
                    // old code authority may already be retired or owned by a
                    // foreign transaction. Preserve the dispatch freeze rather
                    // than thawing an unverified old route.
                    poisoned = true;
                    throw failure;
                }

                try {
                    frozen.rollback();
                } catch (RuntimeException | Error rollbackFailure) {
                    poisoned = true;
                    if (rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    final class Transaction {
        private final LogicalActorProxyMailboxV1.FreezeTicket ticket;
        private final ActorUpgradeEpochGate.Candidate candidate;
        // All state here is guarded by LogicalActorUpgradeCoordinatorV1.this.
        private boolean done;

        private Transaction(LogicalActorProxyMailboxV1.FreezeTicket ticket,
                            ActorUpgradeEpochGate.Candidate candidate) {
            this.ticket = ticket;
            this.candidate = candidate;
        }

        boolean drained() {
            synchronized (LogicalActorUpgradeCoordinatorV1.this) {
                requireActive();
                synchronized (code) {
                    try {
                        return ticket.drained() && code.livePins() == 0;
                    } catch (RuntimeException | Error failure) {
                        quarantine();
                        throw failure;
                    }
                }
            }
        }

        boolean commit() {
            synchronized (LogicalActorUpgradeCoordinatorV1.this) {
                requireActive();
                synchronized (code) {
                    try {
                        if (!ticket.drained() || code.livePins() != 0) return false;
                        if (!code.publish(candidate)) return false;
                    } catch (RuntimeException | Error failure) {
                        // A foreign close/retirement or an ambiguous
                        // publication must not allow this incarnation to
                        // dispatch again.
                        quarantine();
                        throw new IllegalStateException(
                                "upgrade code publication unverified: dispatch quarantined",
                                failure);
                    }

                    // Code is now authoritative. Keep the gate monitor held
                    // through mailbox publication so an external close cannot
                    // retire the new code between epoch publish and dispatch
                    // thaw.
                    poisoned = true;
                    try {
                        ticket.commit();
                        done = true;
                        active = null;
                        preparing = false;
                        poisoned = false;
                        return true;
                    } catch (RuntimeException | Error failure) {
                        quarantine();
                        throw new IllegalStateException(
                                "code generation published but mailbox did not: "
                                        + "dispatch remains quarantined", failure);
                    }
                }
            }
        }

        void rollback() {
            synchronized (LogicalActorUpgradeCoordinatorV1.this) {
                requireActive();
                synchronized (code) {
                    // Abort first; only a proven abort permits old-route
                    // dispatch. Keep the gate monitor until the mailbox thaw
                    // completes so retirement cannot interleave between them.
                    try {
                        code.abort(candidate);
                    } catch (RuntimeException | Error failure) {
                        quarantine();
                        throw new IllegalStateException(
                                "code candidate abort unverified: dispatch quarantined",
                                failure);
                    }
                    try {
                        ticket.rollback();
                        done = true;
                        active = null;
                        preparing = false;
                    } catch (RuntimeException | Error failure) {
                        quarantine();
                        throw new IllegalStateException(
                                "mailbox rollback unverified: dispatch quarantined",
                                failure);
                    }
                }
            }
        }

        private void quarantine() {
            poisoned = true;
            done = true;
            preparing = false;
            active = null;
        }

        private void requireActive() {
            if (done || active != this || !preparing || poisoned)
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
        // A clean close revokes this coordinator's ability to begin any new
        // freeze/publish transaction. It does not close the actor or mailbox.
        closed = true;
    }
}
