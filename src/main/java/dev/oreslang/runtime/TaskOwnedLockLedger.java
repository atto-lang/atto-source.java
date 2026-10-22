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

    private static final int MAX_PERMITS_PER_TASK = 4096;
    private static final class Entry {
        final Lease lease;
        Entry previous;
        Entry next;
        Entry(Lease lease) { this.lease = lease; }
    }

    private final IdentityHashMap<Lease, Entry> held = new IdentityHashMap<>();
    private Entry latest;
    private boolean admissionFenced;
    private boolean quiescent;

    synchronized void register(Lease permit) {
        Objects.requireNonNull(permit, "permit");
        if (admissionFenced || quiescent) {
            throw new CancellationException("Task lock admission has been fenced");
        }
        if (held.containsKey(permit)) {
            throw new IllegalStateException("duplicate Task lock permit registration");
        }
        if (held.size() >= MAX_PERMITS_PER_TASK) {
            throw new IllegalStateException("Task lock permit quota exceeded");
        }
        Entry entry = new Entry(permit);
        entry.previous = latest;
        if (latest != null) latest.next = entry;
        latest = entry;
        held.put(permit, entry);
    }

    synchronized void forget(Lease permit) {
        Entry entry = held.remove(permit);
        if (entry == null) return;
        if (entry.previous != null) entry.previous.next = entry.next;
        if (entry.next != null) entry.next.previous = entry.previous;
        if (latest == entry) latest = entry.previous;
        entry.previous = null;
        entry.next = null;
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
            snapshot = new ArrayList<>(held.size());
            // A Task that nests multiple acquisitions releases in LIFO order.
            for (Entry entry = latest; entry != null; entry = entry.previous) {
                snapshot.add(entry.lease);
            }
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
