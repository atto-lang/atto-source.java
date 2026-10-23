package dev.oreslang.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime-only, incarnation-bound receipts for physical resources (not just
 * logical cancellation). A driver issues a receipt BEFORE publishing a loan,
 * and settles it only after the native handle, mutex grant, iterator or FFI
 * operation is physically released.
 *
 * No guest-visible Future, cancellation hook, close(), or observer can settle
 * these tickets. Actor finalization must join pending() AND quarantineCause().
 * The registry deliberately does not own a worker pool or a global table.
 */
final class ActorPhysicalResourceReceipts {
    enum Kind {
        MUTEX_HANDOFF, TCP_HANDLE, HTTP_HANDLE, FFI_LOAN,
        ASYNC_ITERATOR, RESOURCE_CLOSE, OTHER
    }

    static final int MAX_OUTSTANDING = 4_096;

    private final Object lock = new Object();
    private final Set<Receipt> live =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Runnable onSettlement;
    private boolean stopped;
    private boolean finalized;
    private Throwable quarantineCause;

    ActorPhysicalResourceReceipts(Runnable onSettlement) {
        this.onSettlement = Objects.requireNonNull(onSettlement, "onSettlement");
    }

    /**
     * Must be called by the owning actor execution lease, or an authenticated
     * SourceTask cleanup turn after STOP. The caller validates the incarnation;
     * this registry additionally seals ordinary admission on STOP.
     */
    Receipt register(Kind kind, boolean cleanupTurn) {
        Objects.requireNonNull(kind, "kind");
        synchronized (lock) {
            if (finalized || (stopped && !cleanupTurn)) {
                throw new IllegalStateException(
                        "physical resource receipt admission closed");
            }
            if (quarantineCause != null) {
                throw new IllegalStateException(
                        "actor resource lifetime already quarantined",
                        quarantineCause);
            }
            if (live.size() >= MAX_OUTSTANDING) {
                quarantineCause = new IllegalStateException(
                        "actor physical resource receipt capacity exceeded");
                throw new IllegalStateException(
                        "actor physical resource receipt admission overflow",
                        quarantineCause);
            }
            Receipt receipt = new Receipt(kind);
            live.add(receipt);
            return receipt;
        }
    }

    /** Atomically seal ordinary issuance before STOP snapshots other owners. */
    void sealOnStop() {
        synchronized (lock) {
            stopped = true;
        }
    }

    int pending() {
        synchronized (lock) {
            return live.size();
        }
    }

    Throwable quarantineCause() {
        synchronized (lock) {
            return quarantineCause;
        }
    }

    /**
     * Requires an outer actor lifecycle fence in addition to this registry's
     * lock: both must prevent fresh admissions while memory is retired.
     */
    void finishAfterProof() {
        synchronized (lock) {
            if (!stopped || !live.isEmpty() || quarantineCause != null) {
                throw new IllegalStateException(
                        "cannot finalize an actor with unresolved physical receipts");
            }
            finalized = true;
        }
    }

    final class Receipt {
        private final Kind kind;

        private Receipt(Kind kind) {
            this.kind = kind;
        }

        Kind kind() {
            return kind;
        }

        /**
         * Only the trusted physical reclaimer invokes this after releasing
         * the resource. A duplicate or late settlement is harmless.
         */
        boolean completeFromDriver() {
            return settle(null);
        }

        /** An unprovable physical release quarantines, not auto-unlocks. */
        boolean failFromDriver(Throwable failure) {
            return settle(Objects.requireNonNull(failure, "failure"));
        }

        private boolean settle(Throwable failure) {
            synchronized (lock) {
                if (!live.remove(this)) return false;
                if (failure != null && quarantineCause == null) {
                    quarantineCause = new IllegalStateException(
                            "physical resource release failed: " + kind,
                            failure);
                }
            }
            // Never run actor lifecycle callbacks under registry lock. A late
            // native close may finish on a foreign reclaimer thread.
            onSettlement.run();
            return true;
        }
    }
}
