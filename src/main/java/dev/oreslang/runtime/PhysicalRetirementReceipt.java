package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fail-closed authority for reclaiming resources that outlive logical Future
 * completion. A receipt is issued only after admission is sealed, every
 * admitted physical lease retires, and no retirement failure was recorded.
 *
 * <p>Receipts are generation- and issuer-bound. A receipt from another actor,
 * runtime generation, or earlier lifecycle cannot authorize reclamation.</p>
 */
final class PhysicalRetirementReceipt {
    enum State {
        OPEN,
        SEALED,
        DRAINED,
        FAILED
    }

    static final class Receipt {
        private final PhysicalRetirementReceipt issuer;
        private final long generation;

        private Receipt(PhysicalRetirementReceipt issuer, long generation) {
            this.issuer = issuer;
            this.generation = generation;
        }

        long generation() {
            return generation;
        }
    }

    static final class Lease implements AutoCloseable {
        private final PhysicalRetirementReceipt owner;
        private final long generation;
        private final String kind;
        private final AtomicBoolean retired = new AtomicBoolean();

        private Lease(
                PhysicalRetirementReceipt owner,
                long generation,
                String kind) {
            this.owner = owner;
            this.generation = generation;
            this.kind = kind;
        }

        long generation() {
            return generation;
        }

        String kind() {
            return kind;
        }

        /**
         * Ordinary successful physical retirement. Repeated calls are safe.
         */
        @Override
        public void close() {
            if (retired.compareAndSet(false, true)) {
                owner.retire(this, null);
            }
        }

        /**
         * Physical retirement could not be proved. The lease is accounted as
         * no longer executing, but the issuer is permanently failed and can
         * never mint a reclamation receipt.
         */
        void fail(Throwable failure) {
            Objects.requireNonNull(failure, "retirement failure");
            if (retired.compareAndSet(false, true)) {
                owner.retire(this, failure);
            }
        }
    }

    private final long generation;
    private int activeLeases;
    private boolean sealed;
    private Throwable failure;
    private Receipt issuedReceipt;

    PhysicalRetirementReceipt(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException(
                    "physical retirement generation must be positive");
        }
        this.generation = generation;
    }

    synchronized long generation() {
        return generation;
    }

    synchronized State state() {
        if (failure != null) return State.FAILED;
        if (issuedReceipt != null) return State.DRAINED;
        return sealed ? State.SEALED : State.OPEN;
    }

    synchronized int activeLeases() {
        return activeLeases;
    }

    synchronized Throwable failure() {
        return failure;
    }

    synchronized Lease acquire(String kind) {
        Objects.requireNonNull(kind, "physical retirement lease kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException(
                    "physical retirement lease kind cannot be blank");
        }
        if (sealed) {
            throw new RejectedExecutionException(
                    "physical retirement admission is sealed for generation "
                            + generation);
        }
        if (failure != null) {
            throw new RejectedExecutionException(
                    "physical retirement generation " + generation
                            + " is quarantined after a failed retirement");
        }
        if (activeLeases == Integer.MAX_VALUE) {
            throw new RejectedExecutionException(
                    "physical retirement lease capacity exceeded");
        }
        activeLeases++;
        return new Lease(this, generation, kind);
    }

    synchronized void seal() {
        sealed = true;
        issueIfSafe();
    }

    synchronized Receipt receipt() {
        return issuedReceipt;
    }

    synchronized boolean hasValidReceipt() {
        return validates(issuedReceipt);
    }

    synchronized boolean validates(Receipt candidate) {
        return candidate != null
                && candidate == issuedReceipt
                && candidate.issuer == this
                && candidate.generation == generation
                && failure == null
                && sealed
                && activeLeases == 0;
    }

    private synchronized void retire(Lease lease, Throwable retirementFailure) {
        if (lease.owner != this || lease.generation != generation) {
            throw new IllegalStateException(
                    "foreign or stale physical retirement lease");
        }
        if (activeLeases <= 0) {
            throw new IllegalStateException(
                    "physical retirement lease accounting underflow");
        }
        activeLeases--;
        if (retirementFailure != null && failure == null) {
            failure = retirementFailure;
        }
        issueIfSafe();
    }

    private void issueIfSafe() {
        if (issuedReceipt == null
                && sealed
                && activeLeases == 0
                && failure == null) {
            issuedReceipt = new Receipt(this, generation);
        }
    }
}
