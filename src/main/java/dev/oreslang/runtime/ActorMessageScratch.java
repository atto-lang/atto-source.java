package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Actor-owned, bounded native scratch storage for one logical mailbox message.
 *
 * <p>This is a reusable allocation layer over {@link ActorNativeArena}, not a
 * JVM heap, Java object allocator, GC, or source-language escape permission.
 * Region objects retain native capacity across messages, while their
 * {@link Slice} views are scoped to one monotonically increasing epoch.
 * A source receive that awaits MUST retain the same epoch until its logical
 * Future completes; scheduler yields do not end a message.</p>
 *
 * <p>Only compiler-proven nonescaping primitive / POD values may use this
 * allocator. No raw ByteBuffer, MemorySegment or native address escapes.
 * Cross-message references are fail-closed even when the physical page is
 * reused. An actor's persistent objects and ECS shards have separate
 * actor-lifetime storage.</p>
 */
public final class ActorMessageScratch {
    private static final int DEFAULT_CHUNK_BYTES = 4096;
    /** Limit retained burst capacity, but keep small-message native chunks hot. */
    private static final long MAX_IDLE_RETAINED_BYTES = 64 * 1024L;

    private final ActorNativeArena arena;
    private final long maximumChunkBytes;
    private final ArrayList<ActorNativeArena.Region> blocks = new ArrayList<>();
    private boolean active;
    private long epoch;
    private int cursorBlock;
    private int cursorByte;
    private long usedBytes;
    private long reservedBytes;
    /** An outstanding checked borrow pins its logical message and native backing. */
    private int readBorrows;
    /** Parent/child lexical scratch scopes are an exclusive LIFO stack. */
    private Scope currentScope;
    /** Reuse the root authority across every message: no JVM Scope allocation per turn. */
    private final Scope rootScope;

    ActorMessageScratch(ActorNativeArena arena, long actorLimit) {
        this.arena = Objects.requireNonNull(arena, "arena");
        if (actorLimit <= 0) throw new IllegalArgumentException("invalid actor memory limit");
        this.maximumChunkBytes = Math.min((long) Integer.MAX_VALUE, actorLimit);
        this.rootScope = new Scope(null, 0L, 0, 0, 0);
        this.rootScope.live = false;
    }

    synchronized void beginMessage() {
        arena.assertOwner();
        if (active) throw new IllegalStateException("actor already owns a logical message scratch epoch");
        if (epoch == Long.MAX_VALUE) throw new IllegalStateException("actor message epoch exhausted");
        // The root token is reused rather than allocating JVM objects per
        // mailbox message. Slice.issuedEpoch still rejects old root views.
        long nextEpoch = epoch + 1;
        rootScope.issuedEpoch = nextEpoch;
        rootScope.live = true;
        epoch = nextEpoch;
        cursorBlock = 0;
        cursorByte = 0;
        usedBytes = 0;
        currentScope = rootScope;
        active = true;
    }

    synchronized void finishMessage() {
        arena.assertOwner();
        if (!active) throw new IllegalStateException("no active logical message scratch epoch");
        if (readBorrows != 0) {
            throw new IllegalStateException("cannot complete a message with live scratch borrows");
        }
        if (currentScope != rootScope) {
            throw new IllegalStateException("cannot complete a message with live scratch scopes");
        }
        // Epoch checks guard stale pointers; eagerly scrub used native spans so
        // plaintext does not linger between messages. Backing is kept/charged.
        for (int index = 0; index <= cursorBlock && index < blocks.size(); index++) {
            ActorNativeArena.Region block = blocks.get(index);
            int bytes = index == cursorBlock ? cursorByte : block.capacity();
            block.clearRange(0, bytes);
        }
        rootScope.live = false;
        currentScope = null;
        active = false;
        usedBytes = 0;
        cursorBlock = 0;
        cursorByte = 0;
        // Do not pin a whole actor's quota forever after one burst-sized
        // message. Release excess native chunks only after borrows have ended
        // and the epoch is no longer active. The first/small chunks stay hot.
        trimIdleTo(MAX_IDLE_RETAINED_BYTES);
    }

    /**
     * Return inactive native scratch chunks to this actor's quota, newest
     * first, without ever touching its persistent state. Does not split an
     * already allocated chunk; the result can be below the requested cap.
     * The owner must call this after the message has logically completed.
     */
    public synchronized void trimIdleTo(long retainBytes) {
        arena.assertOwner();
        if (active || readBorrows != 0) {
            throw new IllegalStateException("cannot trim active or borrowed message scratch");
        }
        if (retainBytes < 0) throw new IllegalArgumentException("negative scratch retention");
        for (int index = blocks.size() - 1;
                index >= 0 && reservedBytes > retainBytes; index--) {
            ActorNativeArena.Region block = blocks.get(index);
            long capacity = block.capacity();
            block.close();
            blocks.remove(index);
            reservedBytes -= capacity;
        }
    }

    synchronized void assertActive() {
        arena.assertOwner();
        if (!active) throw new IllegalStateException("message scratch is not active");
    }

    /**
     * Enter a compiler-proven nonescaping lexical scratch region inside the
     * current logical message. Close it in LIFO order only after all of its
     * borrows are retired. Its allocations can reuse native bytes immediately
     * without invalidating values in enclosing scopes.
     *
     * <p>For now this is a compiler/runtime substrate: Oreslang frontend
     * lowering must prove drops and escapes before allocating guest locals
     * in a scoped region. A scope cannot silently span another actor's turn.</p>
     */
    public synchronized Scope enterScope() {
        assertActive();
        Scope child = new Scope(currentScope, epoch,
                cursorBlock, cursorByte, usedBytes);
        currentScope = child;
        return child;
    }

    public final class Scope implements AutoCloseable {
        private final Scope parent;
        private long issuedEpoch;
        private final int savedBlock;
        private final int savedByte;
        private final long savedUsedBytes;
        private boolean live = true;

        private Scope(Scope parent, long issuedEpoch, int savedBlock,
                int savedByte, long savedUsedBytes) {
            this.parent = parent;
            this.issuedEpoch = issuedEpoch;
            this.savedBlock = savedBlock;
            this.savedByte = savedByte;
            this.savedUsedBytes = savedUsedBytes;
        }

        @Override public void close() {
            synchronized (ActorMessageScratch.this) {
                assertActive();
                if (issuedEpoch != epoch || !live) {
                    throw new IllegalStateException("stale actor scratch scope");
                }
                if (this == rootScope) {
                    throw new IllegalStateException("root message scope must finish with the message");
                }
                if (this != currentScope) {
                    throw new IllegalStateException("scratch scopes must close in LIFO order");
                }
                if (readBorrows != 0) {
                    throw new IllegalStateException("cannot close a scratch scope with live borrows");
                }
                // Clear only this scope's address interval. Outer allocations
                // are untouched; a child token is revoked before any recycled
                // bytes can become accessible to a later child scope.
                for (int index = savedBlock;
                        index <= cursorBlock && index < blocks.size(); index++) {
                    ActorNativeArena.Region block = blocks.get(index);
                    int start = index == savedBlock ? savedByte : 0;
                    int end = index == cursorBlock ? cursorByte : block.capacity();
                    block.clearRange(start, end - start);
                }
                live = false;
                currentScope = parent;
                cursorBlock = savedBlock;
                cursorByte = savedByte;
                usedBytes = savedUsedBytes;
            }
        }
    }

    public synchronized long usedBytes() {
        assertActive();
        return usedBytes;
    }

    /** Physical native bytes retained for reuse and still charged to the actor quota. */
    public synchronized long reservedBytes() {
        arena.assertOwner();
        return reservedBytes;
    }

    /** Align every allocation for native scalar fields by default. */
    public Slice allocate(int bytes) {
        return allocate(bytes, Long.BYTES);
    }

    /**
     * Return a checked primitive slice; caller must prove it does not escape
     * the logical mailbox message. Alignment must be a power of two <= 16.
     */
    public synchronized Slice allocate(int bytes, int alignment) {
        assertActive();
        if (bytes <= 0 || alignment < 1 || alignment > 16
                || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("invalid message scratch allocation");
        }
        // Avoid an overflowing round-up, including for the largest int inputs.
        long alignedBytes = ((long) bytes + alignment - 1L) & -((long) alignment);
        if (alignedBytes > maximumChunkBytes) {
            throw new IllegalArgumentException("message scratch allocation exceeds actor limit");
        }

        int index = cursorBlock;
        int start;
        while (true) {
            if (index == blocks.size()) {
                long headroom = maximumChunkBytes - arena.committedBytes();
                if (headroom < alignedBytes) {
                    throw new IllegalStateException("actor scratch memory quota exhausted");
                }
                long capacity = Math.min(
                        Math.max(alignedBytes, Math.min(DEFAULT_CHUNK_BYTES, maximumChunkBytes)),
                        headroom);
                // A large preferred chunk may fail because other actor quotas
                // (including queued mail) reserve headroom. Retry just the
                // requested span instead of failing an otherwise admissible
                // small allocation. Both attempts are quota charged.
                ActorNativeArena.Region block;
                try {
                    block = arena.region(Math.toIntExact(capacity));
                } catch (IllegalArgumentException | IllegalStateException preferredFailure) {
                    if (capacity == alignedBytes) throw preferredFailure;
                    capacity = alignedBytes;
                    block = arena.region(Math.toIntExact(capacity));
                }
                try {
                    long nextReserved = Math.addExact(reservedBytes, capacity);
                    blocks.add(block);
                    reservedBytes = nextReserved;
                } catch (RuntimeException | Error failure) {
                    block.close();
                    throw failure;
                }
            }
            ActorNativeArena.Region region = blocks.get(index);
            long proposed = ((index == cursorBlock ? (long) cursorByte : 0L)
                    + alignment - 1L) & -((long) alignment);
            if (proposed <= region.capacity() && alignedBytes <= region.capacity() - proposed) {
                start = Math.toIntExact(proposed);
                break;
            }
            index++;
        }

        ActorNativeArena.Region region = blocks.get(index);
        // Reused scratch memory never discloses previous-message bytes, even
        // if the guest reads a field before explicitly initializing it.
        // Clear the full requested span under one actor-arena monitor. A loop
        // of Region.writeByte would reacquire that monitor once per byte.
        region.clearRange(start, bytes);
        long nextUsed = Math.addExact(usedBytes, bytes);
        // Bind the slice to the exact lexical scope authority. Reusing the
        // same physical address after a scope closes cannot resurrect it.
        Slice result = new Slice(epoch, currentScope, region, start, bytes);
        cursorBlock = index;
        cursorByte = Math.addExact(start, Math.toIntExact(alignedBytes));
        usedBytes = nextUsed;
        return result;
    }

    public final class Slice {
        private final long issuedEpoch;
        private final Scope owningScope;
        private final ActorNativeArena.Region region;
        private final int start;
        private final int length;

        private Slice(long issuedEpoch, Scope owningScope,
                ActorNativeArena.Region region, int start, int length) {
            this.issuedEpoch = issuedEpoch;
            this.owningScope = owningScope;
            this.region = region;
            this.start = start;
            this.length = length;
        }

        private int checkedIndex(int relative, int width) {
            ActorMessageScratch.this.assertActive();
            if (issuedEpoch != epoch || !owningScope.live) {
                throw new IllegalStateException("stale actor message scratch lifetime");
            }
            if (relative < 0 || width > length || relative > length - width) {
                throw new IndexOutOfBoundsException("message scratch slice bounds");
            }
            return Math.addExact(start, relative);
        }

        public int length() {
            synchronized (ActorMessageScratch.this) {
                checkedIndex(0, 0);
                return length;
            }
        }

        public byte readByte(int offset) {
            synchronized (ActorMessageScratch.this) {
                return region.readByte(checkedIndex(offset, 1));
            }
        }

        public void writeByte(int offset, byte value) {
            synchronized (ActorMessageScratch.this) {
                region.writeByte(checkedIndex(offset, 1), value);
            }
        }

        public long readLong(int offset) {
            synchronized (ActorMessageScratch.this) {
                return region.readLong(checkedIndex(offset, Long.BYTES));
            }
        }

        public void writeLong(int offset, long value) {
            synchronized (ActorMessageScratch.this) {
                region.writeLong(checkedIndex(offset, Long.BYTES), value);
            }
        }

        public double readDouble(int offset) {
            synchronized (ActorMessageScratch.this) {
                return region.readDouble(checkedIndex(offset, Double.BYTES));
            }
        }

        public void writeDouble(int offset, double value) {
            synchronized (ActorMessageScratch.this) {
                region.writeDouble(checkedIndex(offset, Double.BYTES), value);
            }
        }

        /**
         * Native-backed, epoch-checked read borrow. A live read borrow freezes
         * its entire native block and prevents the logical message from ending.
         * No host pointer, raw MemorySegment or ByteBuffer escapes to guest code.
         */
        public ReadBorrow borrowReadOnly() {
            synchronized (ActorMessageScratch.this) {
                checkedIndex(0, length);
                ActorNativeArena.Region.ReadOnlyLease lease =
                        region.leaseReadOnly(start, length);
                try {
                    // Allocate the managed, non-authoritative lease facade
                    // before publishing the pin count. If construction throws
                    // OOME, the native lease is closed with no leaked pin.
                    ReadBorrow borrow = new ReadBorrow(issuedEpoch, lease);
                    readBorrows = Math.addExact(readBorrows, 1);
                    return borrow;
                } catch (RuntimeException | Error failure) {
                    lease.close();
                    throw failure;
                }
            }
        }
    }

    /** An actor-scoped immutable borrow that pins the message until release. */
    public final class ReadBorrow implements AutoCloseable {
        private final long issuedEpoch;
        private final ActorNativeArena.Region.ReadOnlyLease nativeLease;
        private boolean released;

        private ReadBorrow(long issuedEpoch,
                ActorNativeArena.Region.ReadOnlyLease nativeLease) {
            this.issuedEpoch = issuedEpoch;
            this.nativeLease = nativeLease;
        }

        private void checkLive() {
            arena.assertOwner();
            if (released) throw new IllegalStateException("message scratch read borrow is closed");
            if (!active || issuedEpoch != epoch) {
                throw new IllegalStateException("stale message scratch read borrow");
            }
        }

        public byte readByte(int relative) {
            synchronized (ActorMessageScratch.this) {
                checkLive();
                return nativeLease.readByte(relative);
            }
        }

        public long readLong(int relative) {
            synchronized (ActorMessageScratch.this) {
                checkLive();
                return nativeLease.readLong(relative);
            }
        }

        public double readDouble(int relative) {
            synchronized (ActorMessageScratch.this) {
                checkLive();
                return nativeLease.readDouble(relative);
            }
        }

        @Override public void close() {
            synchronized (ActorMessageScratch.this) {
                if (released) return;
                // While the arena lives, Region.ReadOnlyLease.close checks
                // ownership before releasing. A foreign actor cannot unpin.
                nativeLease.close();
                released = true;
                readBorrows--;
            }
        }
    }
}
