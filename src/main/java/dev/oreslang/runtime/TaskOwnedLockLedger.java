package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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

        /** A claimed asynchronous handoff may still be physically in flight.
         * By default, ordinary guards settle immediately. Runtime-owned
         * asynchronous waiters override this and return a non-cancellable
         * settlement witness, not their public GuardFuture. */
        default CompletionStage<Void> settleAfterQuiescence() {
            revokeAfterQuiescence();
            return CompletableFuture.completedFuture(null);
        }
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
    /** Single one-way retirement barrier; repeated STOP/cancel calls must
     * never run a native permit revoker or handoff cleanup a second time. */
    private CompletableFuture<Void> retirementReceipt;

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
        CompletableFuture<Void> settled = retireAfterQuiescence();
        if (!settled.isDone()) {
            throw new IllegalStateException(
                    "Task lock retirement has pending physical handoffs; use the async barrier");
        }
        try {
            settled.join();
        } catch (java.util.concurrent.CompletionException failure) {
            throw new IllegalStateException(
                    "Task lock retirement could not prove every permit revoked",
                    failure.getCause() == null ? failure : failure.getCause());
        }
    }

    /**
     * Stage trusted revocation immediately, then keep physical handoff
     * witnesses alive until all claimed grants have fully unwound. Never
     * wait on an actor or scheduler carrier. Failure retains the entry and
     * propagates to the barrier rather than reporting a false Task DONE.
     */
    CompletableFuture<Void> retireAfterQuiescence() {
        final List<Lease> snapshot;
        final CompletableFuture<Void> receipt;
        synchronized (this) {
            admissionFenced = true;
            if (retirementReceipt != null) return retirementReceipt;
            quiescent = true;
            // Publish before callbacks: even a re-entrant revoker sees exactly
            // the same pending receipt instead of recursively revoking itself.
            receipt = retirementReceipt = new CompletableFuture<>();
            snapshot = new ArrayList<>(held.size());
            // A Task that nests multiple acquisitions releases in LIFO order.
            for (Entry entry = latest; entry != null; entry = entry.previous) {
                snapshot.add(entry.lease);
            }
        }
        List<CompletableFuture<Void>> settle = new ArrayList<>(snapshot.size());
        for (Lease permit : snapshot) {
            try {
                CompletionStage<Void> pending =
                        Objects.requireNonNull(permit.settleAfterQuiescence(),
                                "null permit retirement witness");
                // Only a successful, physically settled receipt can release
                // this ledger's reverse ownership index.
                settle.add(pending.toCompletableFuture().thenRun(
                        () -> forget(permit)));
            } catch (Throwable failure) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(failure);
                settle.add(failed);
            }
        }
        CompletableFuture.allOf(settle.toArray(new CompletableFuture<?>[0]))
                .whenComplete((ignored, failure) -> {
                    if (failure == null) receipt.complete(null);
                    else receipt.completeExceptionally(failure);
                });
        return receipt;
    }
}
