package dev.oreslang.runtime;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Host-owned physical guest exit evidence. A Receipt is created for a particular
 * context incarnation and physical guest entry, then completed only by the
 * surrounding runtime's TruffleContext.leave finally-path.
 *
 * This is NOT a Future returned by source code, and is deliberately not
 * cancellable or completable by a host pull consumer. The ledger is internal;
 * a future generator continuation must attach its own actual entered turn.
 */
final class PhysicalGuestExitLedger {
    static final class Receipt {
        private final PhysicalGuestExitLedger issuer;
        private final HostGeneratorTurnPump.OwnerToken owner;
        private final long generation;
        private final UUID nonce;
        private final OresFuture<Void> completion = new OresFuture<>();
        private boolean claimed;

        private Receipt(PhysicalGuestExitLedger issuer,
                        HostGeneratorTurnPump.OwnerToken owner, long generation) {
            this.issuer = issuer;
            this.owner = owner;
            this.generation = generation;
            this.nonce = UUID.randomUUID();
        }

        synchronized boolean claim(HostGeneratorTurnPump.OwnerToken expected,
                                   PhysicalGuestExitLedger expectedIssuer) {
            if (issuer != expectedIssuer || !owner.equals(expected) || claimed) return false;
            claimed = true;
            return true;
        }

        void whenCompleteRuntime(BiConsumer<? super Void, ? super Throwable> listener) {
            completion.whenCompleteRuntime(listener);
        }

        HostGeneratorTurnPump.OwnerToken owner() { return owner; }
        long generation() { return generation; }
        UUID nonce() { return nonce; }
    }

    static final class Entry {
        private final PhysicalGuestExitLedger ledger;
        private final Thread thread;
        private final Entry previous;
        private final Receipt receipt;
        private boolean exited;

        private Entry(PhysicalGuestExitLedger ledger, Entry previous, Receipt receipt) {
            this.ledger = ledger;
            this.thread = Thread.currentThread();
            this.previous = previous;
            this.receipt = receipt;
        }

        Receipt receipt() { return receipt; }
    }

    private final HostGeneratorTurnPump.OwnerToken owner;
    private final ThreadLocal<Entry> entered = new ThreadLocal<>();
    private long generation;
    // No physical receipt or nested Entry allocation for contexts that have
    // never accepted a host generator request. Once enabled, never downgrade.
    private volatile boolean trackingEnabled;

    PhysicalGuestExitLedger(HostGeneratorTurnPump.OwnerToken owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    HostGeneratorTurnPump.OwnerToken owner() { return owner; }

    boolean trackingEnabled() { return trackingEnabled; }

    void enableTracking() { trackingEnabled = true; }

    synchronized Entry enteredAfterTruffleEnter() {
        // The caller must be the context-owned physical entry/leave wrapper.
        // This method alone is not guest-entry authorization.
        Entry entry = new Entry(this, entered.get(), new Receipt(this, owner, ++generation));
        entered.set(entry);
        return entry;
    }

    void completedAfterTruffleLeave(Entry entry, Throwable leaveFailure) {
        Objects.requireNonNull(entry, "entry");
        if (entry.ledger != this || entry.thread != Thread.currentThread()) {
            throw new SecurityException("physical guest exit belongs to a different ledger or thread");
        }
        synchronized (entry) {
            if (entry.exited) throw new IllegalStateException("duplicate physical guest exit");
            if (entered.get() != entry) {
                throw new SecurityException("physical guest exit is not the current entered turn");
            }
            entry.exited = true;
        }
        if (entry.previous == null) entered.remove();
        else entered.set(entry.previous);
        if (leaveFailure == null) {
            entry.receipt.completion.completeFromRuntime(null);
        } else {
            // An unsuccessful leave is NOT a successful physical DONE.
            entry.receipt.completion.failFromRuntime(leaveFailure);
        }
    }
}
