package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Trusted-host fixed-size transferable byte pool (contract atto.fixed-buffer-pool.v1).
 *
 * This is deliberately distinct from actor-private ref32 arenas: an arena
 * offset is never transferred. Owners must be authenticated by the actor
 * scheduler before calling this host API; a tuple of IDs is NOT a capability.
 * Direct payload bytes and all slots are allocated at construction; acquiring
 * a managed Lease/Loan/Escrow still allocates, so strict zero-allocation and
 * compiled .atto guest parity are NOT established.
 *
 * There is no native I/O pin or production scheduler/mailbox binding yet.
 * DirectByteBuffer cleanup is JVM-managed rather than deterministic native free.
 */
public final class FixedBufferPoolV1 implements AutoCloseable {
    public record Owner(long tenantId, long actorId, long incarnation) {
        public Owner {
            if (tenantId <= 0 || actorId <= 0 || incarnation <= 0) {
                throw new IllegalArgumentException("all actor owner IDs must be positive");
            }
        }
    }

    public static final class Lease {
        private final FixedBufferPoolV1 pool;
        private final int slotIndex;
        private final long generation;
        private Owner owner;
        private boolean live = true;
        private boolean escrow;

        private Lease(FixedBufferPoolV1 pool, int slotIndex, long generation, Owner owner) {
            this.pool = pool;
            this.slotIndex = slotIndex;
            this.generation = generation;
            this.owner = owner;
        }

        public int capacity() { return pool.slotSize; }
    }

    public static final class Loan implements AutoCloseable {
        private final FixedBufferPoolV1 pool;
        private final Lease lease;
        private final boolean mutable;
        private boolean closed;

        private Loan(FixedBufferPoolV1 pool, Lease lease, boolean mutable) {
            this.pool = pool;
            this.lease = lease;
            this.mutable = mutable;
        }

        @Override
        public void close() { pool.endLoan(this); }
    }

    public static final class Escrow {
        private final FixedBufferPoolV1 pool;
        private final Lease sender;
        private boolean settled;

        private Escrow(FixedBufferPoolV1 pool, Lease sender) {
            this.pool = pool;
            this.sender = sender;
        }
    }

    private static final class Slot {
        private final ByteBuffer bytes;
        private long generation = 1;
        private Lease issued;
        private int readers;
        private boolean writer;
        private boolean disabled;

        private Slot(int bytes) { this.bytes = ByteBuffer.allocateDirect(bytes); }
    }

    private final Slot[] slots;
    private final int slotSize;
    private boolean retiring;
    private int inUse;

    public FixedBufferPoolV1(int slotCount, int slotSize) {
        if (slotCount <= 0 || slotCount > 65536 || slotSize <= 0 ||
                slotSize > (64 * 1024 * 1024) ||
                (long) slotCount * slotSize > 64L * 1024 * 1024) {
            throw new IllegalArgumentException("invalid bounded pool capacity");
        }
        this.slotSize = slotSize;
        slots = new Slot[slotCount];
        for (int i = 0; i < slotCount; ++i) {
            slots[i] = new Slot(slotSize);
        }
    }

    public synchronized Lease try_acquire(Owner owner) {
        Objects.requireNonNull(owner);
        if (retiring) { return null; }
        for (int i = 0; i < slots.length; ++i) {
            Slot slot = slots[i];
            if (slot.issued == null && !slot.disabled) {
                Lease lease = new Lease(this, i, slot.generation, owner);
                slot.issued = lease;
                ++inUse;
                return lease;
            }
        }
        return null;
    }

    private Slot check(Lease lease) {
        if (lease == null || lease.pool != this || !lease.live ||
                lease.slotIndex < 0 || lease.slotIndex >= slots.length) {
            throw new IllegalStateException("invalid or consumed pooled buffer lease");
        }
        Slot slot = slots[lease.slotIndex];
        if (slot.issued != lease || slot.generation != lease.generation) {
            throw new IllegalStateException("stale or foreign pooled buffer lease");
        }
        return slot;
    }

    private Slot checkOwner(Lease lease, Owner caller) {
        Slot slot = check(lease);
        if (!lease.owner.equals(Objects.requireNonNull(caller))) {
            throw new IllegalStateException("wrong actor identity or incarnation");
        }
        return slot;
    }

    public synchronized Loan borrow_read(Lease lease, Owner caller) {
        Slot slot = checkOwner(lease, caller);
        if (retiring || lease.escrow || slot.writer || slot.readers == Integer.MAX_VALUE) {
            throw new IllegalStateException("read loan forbidden");
        }
        Loan loan = new Loan(this, lease, false);
        ++slot.readers;
        return loan;
    }

    public synchronized Loan borrow_write(Lease lease, Owner caller) {
        Slot slot = checkOwner(lease, caller);
        if (retiring || lease.escrow || slot.writer || slot.readers != 0) {
            throw new IllegalStateException("mutable loan requires exclusivity");
        }
        Loan loan = new Loan(this, lease, true);
        slot.writer = true;
        return loan;
    }

    private synchronized void endLoan(Loan loan) {
        if (loan == null || loan.pool != this || loan.closed) {
            throw new IllegalStateException("double release or foreign loan");
        }
        Slot slot = check(loan.lease);
        if (loan.mutable) {
            if (!slot.writer) { throw new IllegalStateException("missing mutable loan"); }
            slot.writer = false;
        } else {
            if (slot.readers <= 0) { throw new IllegalStateException("missing read loan"); }
            --slot.readers;
        }
        loan.closed = true;
    }

    private Slot checkLoan(Loan loan, boolean writing) {
        if (loan == null || loan.pool != this || loan.closed ||
                (writing && !loan.mutable)) {
            throw new IllegalStateException("invalid pooled-buffer access loan");
        }
        Slot slot = check(loan.lease);
        if (retiring || loan.lease.escrow || (loan.mutable && !slot.writer) ||
                (!loan.mutable && slot.readers == 0)) {
            throw new IllegalStateException("loan access denied");
        }
        return slot;
    }

    public synchronized byte read_byte(Loan loan, int offset) {
        Slot slot = checkLoan(loan, false);
        requireOffset(offset);
        return slot.bytes.get(offset);
    }

    public synchronized void write_byte(Loan loan, int offset, byte value) {
        Slot slot = checkLoan(loan, true);
        requireOffset(offset);
        slot.bytes.put(offset, value);
    }

    private void requireOffset(int offset) {
        if (offset < 0 || offset >= slotSize) {
            throw new IndexOutOfBoundsException("pooled byte offset");
        }
    }

    public synchronized Escrow begin_transfer(Lease lease, Owner sender) {
        Slot slot = checkOwner(lease, sender);
        if (retiring || lease.escrow || slot.readers != 0 || slot.writer) {
            throw new IllegalStateException("buffer transfer admission denied");
        }
        Escrow escrow = new Escrow(this, lease);
        lease.escrow = true;
        return escrow;
    }

    private Slot checkEscrow(Escrow escrow) {
        if (escrow == null || escrow.pool != this || escrow.settled) {
            throw new IllegalStateException("settled or foreign transfer");
        }
        Slot slot = check(escrow.sender);
        if (!escrow.sender.escrow || slot.readers != 0 || slot.writer) {
            throw new IllegalStateException("unavailable escrow");
        }
        return slot;
    }

    /** Atomic move creates a NEW capability; the sender's original is dead. */
    public synchronized Lease commit_transfer(Escrow escrow, Owner receiver) {
        Slot slot = checkEscrow(escrow);
        Objects.requireNonNull(receiver);
        if (retiring || escrow.sender.owner.tenantId() != receiver.tenantId()) {
            throw new IllegalStateException("retired or cross-tenant transfer denied");
        }
        Lease recipient = new Lease(this, escrow.sender.slotIndex, slot.generation, receiver);
        escrow.sender.live = false;
        escrow.sender.escrow = false;
        slot.issued = recipient;
        escrow.settled = true;
        return recipient;
    }

    /** Failed mailbox admission must return the SAME sender lease. */
    public synchronized Lease abort_transfer(Escrow escrow) {
        checkEscrow(escrow);
        escrow.sender.escrow = false;
        escrow.settled = true;
        return escrow.sender;
    }

    public synchronized void release(Lease lease, Owner caller) {
        Slot slot = checkOwner(lease, caller);
        if (lease.escrow || slot.readers != 0 || slot.writer) {
            throw new IllegalStateException("cannot recycle escrowed or borrowed buffer");
        }
        // Physical bytes are scrubbed BEFORE any subsequent actor may acquire.
        for (int i = 0; i < slotSize; ++i) { slot.bytes.put(i, (byte) 0); }
        lease.live = false;
        slot.issued = null;
        if (slot.generation == Long.MAX_VALUE) { slot.disabled = true; }
        else { ++slot.generation; }
        --inUse;
    }

    public synchronized void retire() { retiring = true; }
    public synchronized int in_use() { return inUse; }
    public synchronized boolean is_quiescent() { return retiring && inUse == 0; }

    @Override
    public void close() { retire(); }
}
