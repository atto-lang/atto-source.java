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

    private final ActorNativeArena arena;
    private final long maximumChunkBytes;
    private final ArrayList<ActorNativeArena.Region> blocks = new ArrayList<>();
    private boolean active;
    private long epoch;
    private int cursorBlock;
    private int cursorByte;
    private long usedBytes;
    private long reservedBytes;

    ActorMessageScratch(ActorNativeArena arena, long actorLimit) {
        this.arena = Objects.requireNonNull(arena, "arena");
        if (actorLimit <= 0) throw new IllegalArgumentException("invalid actor memory limit");
        this.maximumChunkBytes = Math.min((long) Integer.MAX_VALUE, actorLimit);
    }

    synchronized void beginMessage() {
        arena.assertOwner();
        if (active) throw new IllegalStateException("actor already owns a logical message scratch epoch");
        if (epoch == Long.MAX_VALUE) throw new IllegalStateException("actor message epoch exhausted");
        epoch++;
        cursorBlock = 0;
        cursorByte = 0;
        usedBytes = 0;
        active = true;
    }

    synchronized void finishMessage() {
        arena.assertOwner();
        if (!active) throw new IllegalStateException("no active logical message scratch epoch");
        // Views are revoked immediately. Physical pages are retained for bounded
        // reuse and explicitly freed by ActorNativeArena after actor quiescence.
        active = false;
        usedBytes = 0;
        cursorBlock = 0;
        cursorByte = 0;
    }

    synchronized void assertActive() {
        arena.assertOwner();
        if (!active) throw new IllegalStateException("message scratch is not active");
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
                long capacity = Math.max(alignedBytes, Math.min(DEFAULT_CHUNK_BYTES, maximumChunkBytes));
                // Reserve before publishing any cursor/epoch state; failure
                // leaves the prior allocations and next slot intact.
                ActorNativeArena.Region block = arena.region(Math.toIntExact(capacity));
                try {
                    blocks.add(block);
                } catch (RuntimeException | Error failure) {
                    block.close();
                    throw failure;
                }
                reservedBytes = Math.addExact(reservedBytes, capacity);
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
        for (int i = 0; i < bytes; i++) region.writeByte(start + i, (byte) 0);
        long nextUsed = Math.addExact(usedBytes, bytes);
        Slice result = new Slice(epoch, region, start, bytes);
        cursorBlock = index;
        cursorByte = Math.addExact(start, Math.toIntExact(alignedBytes));
        usedBytes = nextUsed;
        return result;
    }

    public final class Slice {
        private final long issuedEpoch;
        private final ActorNativeArena.Region region;
        private final int start;
        private final int length;

        private Slice(long issuedEpoch, ActorNativeArena.Region region, int start, int length) {
            this.issuedEpoch = issuedEpoch;
            this.region = region;
            this.start = start;
            this.length = length;
        }

        private int checkedIndex(int relative, int width) {
            ActorMessageScratch.this.assertActive();
            if (issuedEpoch != epoch) {
                throw new IllegalStateException("stale actor message scratch epoch");
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
    }
}
