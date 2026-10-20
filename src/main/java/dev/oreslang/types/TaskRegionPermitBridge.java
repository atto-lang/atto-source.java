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
 * resulting permit's physical-exit Runnable. Do not expose the ledger or
 * {@link Permit#run()} to Oreslang guest code.
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
        return new Permit(ledger, ticket);
    }

    boolean parentMayAccess(TaskAccessVerifier.Claim claim) {
        return ledger.parentMayAccess(claim);
    }

    int activeChildren() { return ledger.activeChildren(); }

    void finishScope() { ledger.finishScope(); }

    /**
     * Ticket holder passed as the trusted onPhysicalExit callback when the
     * runtime admits a Task frame. The scheduler invokes it after physical
     * quiescence; admission rejection invokes it before any task starts.
     *
     * A second invocation may succeed only if the original retirement has
     * FINISHED. Cancellation is just intent and never releases the ticket.
     */
    static final class Permit implements Runnable {
        private static final int ACTIVE = 0;
        private static final int RETIRING = 1;
        private static final int RETIRED = 2;
        private static final int FAILED = 3;

        private final TaskAccessVerifier ledger;
        private final TaskAccessVerifier.Ticket ticket;
        private final AtomicInteger retirement = new AtomicInteger(ACTIVE);
        private volatile Throwable failure;

        private Permit(TaskAccessVerifier ledger, TaskAccessVerifier.Ticket ticket) {
            this.ledger = Objects.requireNonNull(ledger, "ledger");
            this.ticket = Objects.requireNonNull(ticket, "ticket");
        }

        void requestCancel() {
            if (retirement.get() != ACTIVE)
                throw new IllegalStateException("Task region permit is no longer live");
            ledger.requestCancel(ticket);
        }

        boolean cancellationRequested() {
            if (retirement.get() != ACTIVE)
                throw new IllegalStateException("Task region permit is no longer live");
            return ledger.cancellationRequested(ticket);
        }

        boolean retired() { return retirement.get() == RETIRED; }

        @Override public void run() {
            if (!retirement.compareAndSet(ACTIVE, RETIRING)) {
                int prior = retirement.get();
                if (prior == RETIRED) return;
                if (prior == FAILED)
                    throw new IllegalStateException(
                            "Task region retirement previously failed", failure);
                throw new IllegalStateException(
                        "Task region retirement has not physically completed");
            }
            try {
                ledger.quiescent(ticket);
                retirement.set(RETIRED);
            } catch (RuntimeException | Error error) {
                failure = error;
                retirement.set(FAILED);
                throw error;
            }
        }
    }
}
