package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Objects;

/**
 * One private, growable byte-addressed guest memory per logical actor.
 *
 * <p>This is a WASM-like <em>logical</em> linear address space: offset 0
 * through byteLength()-1 is mapped to independent, explicitly freed 64-KiB
 * native pages owned by exactly one ActorNativeArena. Pages need not be
 * contiguous in host virtual memory; guest offsets are not native pointers.
 * Carrier thread migration never changes the logical actor owner.</p>
 *
 * <p>Access and growth are checked against the currently executing actor,
 * guarded by the arena monitor, and charged against the same actor quota as
 * source object fields and native dense shards. A failed multi-page grow
 * rolls back all newly allocated pages before becoming observable.</p>
 */
public final class ActorLinearMemory {
    public static final int PAGE_BYTES = 64 * 1024;
    /** WASM32 maximum addressable pages (4 GiB of logical bytes). */
    public static final int MAX_PAGES = 65_536;

    private final ActorNativeArena arena;
    private final int maximumPages;
    private final ArrayList<ActorNativeArena.Region> pages = new ArrayList<>();

    ActorLinearMemory(ActorNativeArena arena, int maximumPages) {
        this.arena = Objects.requireNonNull(arena);
        if (maximumPages < 0 || maximumPages > MAX_PAGES) {
            throw new IllegalArgumentException("invalid linear memory maximum page count");
        }
        this.maximumPages = maximumPages;
    }

    public int maxPages() { return maximumPages; }

    public int pages() {
        synchronized (arena) {
            arena.assertOwner();
            return pages.size();
        }
    }

    public long byteLength() {
        synchronized (arena) {
            arena.assertOwner();
            return (long) pages.size() * PAGE_BYTES;
        }
    }

    /**
     * Commit zero-filled native pages, returning the previous page count.
     * Existing guest offsets and read leases remain valid across growth.
     * Any failure keeps the old length and returns every newly charged byte.
     */
    public int grow(int additionalPages) {
        synchronized (arena) {
            arena.assertOwner();
            int previous = pages.size();
            if (additionalPages < 0 || (long) previous + additionalPages > maximumPages) {
                throw new IllegalArgumentException("linear memory growth exceeds maximum");
            }
            int target = previous + additionalPages;
            if (additionalPages == 0) return previous;
            pages.ensureCapacity(target);
            try {
                while (pages.size() < target) {
                    pages.add(arena.region(PAGE_BYTES));
                }
            } catch (RuntimeException | Error failure) {
                while (pages.size() > previous) {
                    pages.remove(pages.size() - 1).close();
                }
                throw failure;
            }
            return previous;
        }
    }

    private void checkRange(long offset, int length) {
        arena.assertOwner();
        long available = (long) pages.size() * PAGE_BYTES;
        if (offset < 0 || length < 0 || offset > available || length > available - offset) {
            throw new IndexOutOfBoundsException("linear memory access outside committed pages");
        }
    }

    private ActorNativeArena.Region page(long offset) {
        return pages.get((int) (offset / PAGE_BYTES));
    }

    private int inPage(long offset) {
        return (int) (offset % PAGE_BYTES);
    }

    public byte readByte(long offset) {
        synchronized (arena) {
            checkRange(offset, 1);
            return page(offset).readByte(inPage(offset));
        }
    }

    public int readUnsignedByte(long offset) { return Byte.toUnsignedInt(readByte(offset)); }

    public void writeByte(long offset, byte value) {
        synchronized (arena) {
            checkRange(offset, 1);
            page(offset).writeByte(inPage(offset), value);
        }
    }

    public long readLong(long offset) {
        synchronized (arena) {
            checkRange(offset, Long.BYTES);
            int local = inPage(offset);
            if (local <= PAGE_BYTES - Long.BYTES) return page(offset).readLong(local);
            long bits = 0;
            for (int i = 0; i < Long.BYTES; i++) {
                long position = offset + i;
                bits |= (long) (page(position).readByte(inPage(position)) & 0xff) << (i * 8);
            }
            return bits;
        }
    }

    public void writeLong(long offset, long value) {
        synchronized (arena) {
            checkRange(offset, Long.BYTES);
            int local = inPage(offset);
            if (local <= PAGE_BYTES - Long.BYTES) {
                page(offset).writeLong(local, value);
                return;
            }
            // A word spanning two pages must not be half-written if either
            // page is currently frozen by an immutable native borrow.
            page(offset).assertWritable();
            page(offset + Long.BYTES - 1).assertWritable();
            for (int i = 0; i < Long.BYTES; i++) {
                long position = offset + i;
                page(position).writeByte(inPage(position), (byte) (value >>> (i * 8)));
            }
        }
    }

    public double readDouble(long offset) {
        return Double.longBitsToDouble(readLong(offset));
    }

    public void writeDouble(long offset, double value) {
        writeLong(offset, Double.doubleToRawLongBits(value));
    }

    /**
     * Only the owning arena calls this during physical actor finalization.
     * Arena.close() revokes and scrubs all constituent native regions.
     */
    void forgetReleasedPages() { pages.clear(); }
}
