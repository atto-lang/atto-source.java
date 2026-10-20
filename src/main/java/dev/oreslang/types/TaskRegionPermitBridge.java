package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compiler-owned Task region admission and physical-exit retirement seam.
 *
 * <p>This class is intentionally package-private. It is neither a guest
 * capability nor proof of an actor allocation domain: the compiler must
 * establish trusted allocation provenance, type identity and capture effects
 * before constructing the claims. The runtime is allowed to receive only the
 * one-time bound physical-exit Runnable. Do not expose the ledger or
 * an exit callback to Oreslang guest code.
 *
 * <p>In particular this compiler preflight does NOT authorize lexical &T or
 * &mut T across await; admitCoherentTyped still rejects unproven borrows.
 * No guest nb lowering is enabled by adding this bridge.
 */
final class TaskRegionPermitBridge {
    private final TaskAccessVerifier ledger = new TaskAccessVerifier();

    /**
     * Atomically reserve a proven footprint BEFORE executable Task scheduling.
     * A failed proof reserves nothing; the caller must discard a permit if
     * runtime scheduling is rejected, via the same trusted physical-exit hook.
     */
    Permit admit(List<TaskAccessVerifier.CoherentTypedClaim> footprint,
                 SendSyncTraits sourceProof,
                 ThreadSafetyTraits.NominalProof resolvedProof) {
        TaskAccessVerifier.Ticket ticket =
                ledger.admitCoherentTyped(footprint, sourceProof, resolvedProof);
        // Permit preparation is after the static claim reservation but still
        // BEFORE scheduler submission. If allocation fails, revoke the ticket
        // without fabricating a live child or leaving the parent blocked.
        try {
            return new Permit(ledger, ticket);
        } catch (RuntimeException | Error preparationFailure) {
            try {
                ledger.quiescent(ticket); // no executable frame was admitted
            } catch (RuntimeException | Error cleanupFailure) {
                if (preparationFailure != cleanupFailure)
                    preparationFailure.addSuppressed(cleanupFailure);
            }
            throw preparationFailure;
        }
    }

    boolean parentMayAccess(TaskAccessVerifier.Claim claim) {
        return ledger.parentMayAccess(claim);
    }

    int activeChildren() { return ledger.activeChildren(); }

    void finishScope() { ledger.finishScope(); }

    /**
     * One compiler-owned child region ticket.
     *
     * RESERVED is pre-scheduling, not physical runtime admission.
     * abortBeforeScheduling() is its only permitted cleanup. Once bound,
     * only the one-time physical-exit callback supplied to the trusted
     * OresTaskScope may retire the region. This is NOT an authenticated MIR
     * provenance witness or guest-visible capability.
     */
    static final class Permit {
        private static final int RESERVED = 0;
        private static final int BOUND = 1;
        private static final int RETIRING = 2;
        private static final int RETIRED = 3;
        private static final int ABANDONED = 4;
        private static final int FAILED = 5;

        private final TaskAccessVerifier ledger;
        private final TaskAccessVerifier.Ticket ticket;
        private final AtomicInteger retirement = new AtomicInteger(RESERVED);
        private volatile Throwable failure;

        private Permit(TaskAccessVerifier ledger, TaskAccessVerifier.Ticket ticket) {
            this.ledger = Objects.requireNonNull(ledger, "ledger");
            this.ticket = Objects.requireNonNull(ticket, "ticket");
        }

        /**
         * Call immediately before trusted host admission of this frame.
         * Scheduler rejection invokes the returned callback without starting
         * the frame; otherwise only physical final exit invokes it.
         * The callback must never be exposed to guest code.
         * Allocate callback before switching state so allocation failure
         * leaves a reservation eligible for pre-scheduling abandonment.
         */
        Runnable bindPhysicalExit() {
            Runnable callback = this::retireOnPhysicalExit;
            if (!retirement.compareAndSet(RESERVED, BOUND))
                throw new IllegalStateException("Task region permit already bound or retired");
            return callback;
        }

        /** Only valid before a runnable child / exit callback is bound. */
        void abortBeforeScheduling() {
            retireFrom(RESERVED, "Task region permit is already bound to a Task");
        }

        void requestCancel() {
            int state = retirement.get();
            if (state != RESERVED && state != BOUND)
                throw new IllegalStateException("Task region permit is no longer live");
            ledger.requestCancel(ticket);
        }

        boolean cancellationRequested() {
            int state = retirement.get();
            if (state != RESERVED && state != BOUND)
                throw new IllegalStateException("Task region permit is no longer live");
            return ledger.cancellationRequested(ticket);
        }

        boolean retired() {
            int state = retirement.get();
            return state == RETIRED || state == ABANDONED;
        }

        private void retireOnPhysicalExit() {
            retireFrom(BOUND, "Task region physical-exit callback is not bound");
        }

        private void retireFrom(int expected, String denial) {
            if (!retirement.compareAndSet(expected, RETIRING)) {
                int prior = retirement.get();
                // A completed physical exit is not equivalent to an
                // abandoned unbound reservation. Only the *same* successful
                // completion route is idempotent.
                if ((prior == RETIRED && expected == BOUND)
                        || (prior == ABANDONED && expected == RESERVED)) return;
                if (prior == FAILED)
                    throw new IllegalStateException(
                            "Task region retirement previously failed", failure);
                if (prior == RETIRING)
                    throw new IllegalStateException(
                            "Task region retirement has not physically completed");
                throw new IllegalStateException(denial);
            }
            try {
                ledger.quiescent(ticket);
                retirement.set(expected == RESERVED ? ABANDONED : RETIRED);
            } catch (RuntimeException | Error error) {
                failure = error;
                retirement.set(FAILED);
                throw error;
            }
        }
    }
}
