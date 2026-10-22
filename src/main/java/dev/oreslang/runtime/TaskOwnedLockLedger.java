package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Scheduler-owned reverse index of leases acquired by one tracked Task.
 *
 * <p>This is a trusted runtime primitive, not a guest-visible defer callback.
 * Cancelling a Task fences new registrations, but does not revoke held leases.
 * Reclamation occurs only after OresScheduler confirms physical quiescence.
 * User callbacks and potentially blocking permit handoffs run outside this
 * ledger's monitor. An unsuccessful revoker stays registered (fail-closed).</p>
 */
final class TaskOwnedLockLedger {
    interface Lease {
        /** Idempotent, runtime-only. Guest code is already quiescent. */
        void revokeAfterQuiescence();
    }

    private final IdentityHashMap<Lease, Boolean> held = new IdentityHashMap<>();
    private boolean admissionFenced;
    private boolean quiescent;

    synchronized void register(Lease permit) {
        Objects.requireNonNull(permit, "permit");
        if (admissionFenced || quiescent) {
            throw new CancellationException("Task lock admission has been fenced");
        }
        if (held.put(permit, Boolean.TRUE) != null) {
            throw new IllegalStateException("duplicate Task lock permit registration");
        }
    }

    synchronized void forget(Lease permit) {
        held.remove(permit);
    }

    synchronized void fence() {
        admissionFenced = true;
    }

    synchronized int outstanding() {
        return held.size();
    }

    /**
     * Only a scheduler-confirmed quiescence callback may invoke this. Holding
     * the Java monitor across revocation could deadlock synchronous Future
     * subscribers or nested lock handoffs.
     */
    void revokeAfterQuiescence() {
        final List<Lease> snapshot;
        synchronized (this) {
            admissionFenced = true;
            if (quiescent && held.isEmpty()) return;
            quiescent = true;
            snapshot = new ArrayList<>(held.keySet());
        }
        Throwable first = null;
        for (Lease permit : snapshot) {
            try {
                permit.revokeAfterQuiescence();
                forget(permit);
            } catch (Throwable failure) {
                // Preserve the lease as a quarantine root: never declare DONE
                // or run another owner's protected callback on an unknown
                // release outcome.
                if (first == null) first = failure;
                else first.addSuppressed(failure);
            }
        }
        if (first != null) {
            throw new IllegalStateException(
                    "Task lock retirement could not prove every permit revoked", first);
        }
    }
}
