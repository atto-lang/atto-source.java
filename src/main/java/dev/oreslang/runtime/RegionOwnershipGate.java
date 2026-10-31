package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Runtime-only single-owner admission gate for a trusted logical region root.
 *
 * <p>This class deliberately does not expose Region<T> as actor-mailbox data.
 * A trusted mailbox adapter must atomically commit a prepared transfer only
 * after accepting the message; a rejected admission must abort the transfer.
 * Every access to a physical Region must be gated separately by the actual
 * runtime storage implementation. The root/owner receipts are opaque host
 * identities, not guest serializable/native-address capabilities.</p>
 */
final class RegionOwnershipGate {
    private final DopRegionSchedule.Root root;
    private Owner owner;
    private long epoch = 1L;
    private int readers;
    private Borrow writer;
    private Transfer pending;
    private boolean computeInFlight;
    private boolean poisoned;
    private boolean retired;

    private RegionOwnershipGate(DopRegionSchedule.Root root, Object domain) {
        this.root = Objects.requireNonNull(root, "trustedRoot");
        this.owner = new Owner(this, Objects.requireNonNull(domain, "ownerDomain"), epoch);
    }

    /** Called only by trusted storage / actor runtime bootstrap. */
    static RegionOwnershipGate trustedCreate(DopRegionSchedule.Root root, Object ownerDomain) {
        return new RegionOwnershipGate(root, ownerDomain);
    }

    /** Runtime metadata only; never a physical pointer or guest Region handle. */
    synchronized long rows() {
        requireLive();
        return root.rows();
    }

    synchronized int fields() {
        requireLive();
        return root.fields();
    }

    /** Runtime-only original owner receipt, never minted from a message's claimed id. */
    synchronized Owner initialOwner() {
        requireLive();
        return owner;
    }

    private void requireLive() {
        if (retired) throw new IllegalStateException("region retired");
    }

    private void requireOwner(Owner receipt) {
        requireLive();
        if (receipt == null || receipt.gate != this || receipt != owner
                || receipt.epoch != epoch)
            throw new IllegalStateException("stale or foreign region owner");
        if (poisoned) throw new IllegalStateException("region storage poisoned");
        if (pending != null) throw new IllegalStateException("region transfer awaiting admission");
        if (computeInFlight) throw new IllegalStateException("region compute still in flight");
    }

    synchronized void requireRead(Owner receipt) {
        requireOwner(receipt);
        if (writer != null) throw new IllegalStateException("region under exclusive writer");
    }
    synchronized void requireWrite(Owner receipt) {
        requireOwner(receipt);
        if (writer != null || readers != 0)
            throw new IllegalStateException("region borrowed; writable owner access denied");
    }

    synchronized Borrow borrowRead(Owner receipt) {
        requireOwner(receipt);
        if (writer != null) throw new IllegalStateException("exclusive region writer active");
        if (readers == Integer.MAX_VALUE) throw new IllegalStateException("too many region readers");
        readers++;
        return new Borrow(this, receipt, false);
    }

    synchronized Borrow borrowWrite(Owner receipt) {
        requireOwner(receipt);
        if (writer != null || readers != 0)
            throw new IllegalStateException("region already borrowed");
        writer = new Borrow(this, receipt, true);
        return writer;
    }

    private synchronized void release(Borrow borrow) {
        if (borrow.released) return;
        borrow.released = true;
        if (borrow.writer) {
            if (writer != borrow) throw new IllegalStateException("foreign region writer");
            writer = null;
        } else {
            if (readers <= 0) throw new IllegalStateException("region read borrow underflow");
            readers--;
        }
    }

    /** A compute lease reserves the region until *physical* quiescence. */
    synchronized ComputeLease beginCompute(Owner receipt) {
        requireOwner(receipt);
        if (readers != 0 || writer != null)
            throw new IllegalStateException("cannot submit region with live borrows");
        computeInFlight = true;
        return new ComputeLease(this, receipt);
    }

    private synchronized void backendQuiescent(ComputeLease lease) {
        if (lease.gate != this || lease.owner != owner || lease.epoch != epoch
                || !computeInFlight || lease.quiescent)
            throw new IllegalStateException("stale or duplicate region compute completion");
        // Cancellation is NOT quiescence. Partially written output must not
        // be transferred or reused after cancellation or backend failure.
        // Only the trusted provider that observed physical device/CPU
        // retirement may call this gate.
        if (lease.cancelled || !lease.succeeded) poisoned = true;
        lease.quiescent = true;
        computeInFlight = false;
    }

    synchronized Transfer prepareTransfer(Owner receipt, Object targetDomain) {
        requireOwner(receipt);
        Objects.requireNonNull(targetDomain, "targetDomain");
        if (targetDomain == owner.domain)
            throw new IllegalArgumentException("region transfer target already owns this region");
        if (readers != 0 || writer != null)
            throw new IllegalStateException("cannot transfer region with live borrows");
        if (epoch == Long.MAX_VALUE) throw new IllegalStateException("region generation exhausted");
        pending = new Transfer(this, receipt, targetDomain, epoch);
        return pending;
    }

    /** Admission success is the sole linearization point for changing owner. */
    private synchronized Owner commit(Transfer ticket) {
        checkTransfer(ticket);
        epoch++;
        owner = new Owner(this, ticket.targetDomain, epoch);
        ticket.settled = true;
        pending = null;
        return owner;
    }

    /** Mailbox close/backpressure/rejection keeps the original owner valid. */
    private synchronized void abort(Transfer ticket) {
        checkTransfer(ticket);
        ticket.settled = true;
        pending = null;
    }

    private void checkTransfer(Transfer ticket) {
        requireLive();
        if (ticket == null || ticket.gate != this || pending != ticket
                || ticket.settled || ticket.sender != owner || ticket.epoch != epoch)
            throw new IllegalStateException("stale or foreign region transfer ticket");
    }

    synchronized void retire(Owner receipt) {
        requireLive();
        if (receipt == null || receipt.gate != this || receipt != owner
                || receipt.epoch != epoch)
            throw new IllegalStateException("stale or foreign region owner");
        if (pending != null || computeInFlight)
            throw new IllegalStateException("region still active");
        if (readers != 0 || writer != null)
            throw new IllegalStateException("region has live borrows");
        retired = true;
        owner = null;
    }

    /** Opaque, incarnation-specific ownership claim; not transferable itself. */
    static final class Owner {
        private final RegionOwnershipGate gate;
        private final Object domain;
        private final long epoch;
        private Owner(RegionOwnershipGate gate, Object domain, long epoch) {
            this.gate = gate;
            this.domain = domain;
            this.epoch = epoch;
        }
        Object domain() { return domain; }
    }

    static final class Borrow implements AutoCloseable {
        private final RegionOwnershipGate gate;
        private final Owner owner;
        private final boolean writer;
        private boolean released;
        private Borrow(RegionOwnershipGate gate, Owner owner, boolean writer) {
            this.gate = gate; this.owner = owner; this.writer = writer;
        }
        void requireReadable() {
            synchronized (gate) {
                if (released || gate.retired || owner != gate.owner || owner.epoch != gate.epoch
                        || gate.poisoned || gate.computeInFlight || gate.pending != null)
                    throw new IllegalStateException("stale region borrow");
                if (writer) {
                    if (gate.writer != this) throw new IllegalStateException("writer revoked");
                } else if (gate.readers <= 0 || gate.writer != null) {
                    throw new IllegalStateException("reader revoked");
                }
            }
        }
        void requireWritable() {
            if (!writer) throw new IllegalStateException("read borrow cannot write");
            requireReadable();
        }
        @Override public void close() { gate.release(this); }
    }

    static final class Transfer {
        private final RegionOwnershipGate gate;
        private final Owner sender;
        private final Object targetDomain;
        private final long epoch;
        private boolean settled;
        private Transfer(RegionOwnershipGate gate, Owner sender, Object targetDomain, long epoch) {
            this.gate = gate; this.sender = sender;
            this.targetDomain = targetDomain; this.epoch = epoch;
        }
        Owner admitted() { return gate.commit(this); }
        void rejected() { gate.abort(this); }
    }

    static final class ComputeLease {
        private final RegionOwnershipGate gate;
        private final Owner owner;
        private final long epoch;
        private boolean cancelled;
        private boolean quiescent;
        private boolean succeeded;
        private ComputeLease(RegionOwnershipGate gate, Owner owner) {
            this.gate = gate; this.owner = owner; this.epoch = owner.epoch;
        }
        void requestCancellation() {
            synchronized (gate) {
                if (quiescent) throw new IllegalStateException("compute already retired");
                cancelled = true;
            }
        }
        boolean cancelled() { synchronized (gate) { return cancelled; } }
        /**
         * Trusted backend-only physical completion. success=false or a prior
         * cancellation poisons the region until its owner explicitly retires it.
         * This method is not a guest API or proof of real device completion.
         */
        void trustedBackendQuiescent(boolean success) {
            synchronized (gate) {
                succeeded = success;
                gate.backendQuiescent(this);
            }
        }
    }
}
