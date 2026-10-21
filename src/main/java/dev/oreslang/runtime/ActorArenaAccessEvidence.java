package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TEST-ONLY forensic evidence of successful actor-native arena memory accesses.
 * Records *observed* accesses, not scheduler synchronization or complete
 * language memory coverage. No guest value or native pointer is exposed.
 *
 * All methods are called while holding the owning ActorNativeArena monitor.
 * No per-access allocation takes place; snapshots allocate only at inspection.
 */
final class ActorArenaAccessEvidence {
    static final int MAX_EVENTS = 65_536;
    /** Host-process-local scope. The trusted runner must supply a run identity. */
    private static final AtomicLong NEXT_SCOPE = new AtomicLong();
    record Access(long offset, int width, boolean write) { }
    record Snapshot(List<Access> accesses, long dropped, int capacity, long scopeId) {
        boolean completeBuffer() { return dropped == 0; }
    }

    private final long scopeId;
    private final long[] offsets;
    private final int[] widths;
    private final byte[] modes;
    private int used;
    private long dropped;

    ActorArenaAccessEvidence(int capacity) {
        if (capacity < 1 || capacity > MAX_EVENTS) {
            throw new IllegalArgumentException("access evidence event capacity must be 1..65536");
        }
        offsets = new long[capacity];
        widths = new int[capacity];
        modes = new byte[capacity];
        scopeId = NEXT_SCOPE.incrementAndGet();
        if (scopeId <= 0) {
            throw new IllegalStateException("actor access evidence scope namespace exhausted");
        }
    }

    void record(long offset, int width, boolean write) {
        if (offset < 0 || width <= 0 || offset > Long.MAX_VALUE - width) {
            throw new IllegalArgumentException("invalid actor-relative memory access identity");
        }
        if (used == offsets.length) {
            dropped = Math.addExact(dropped, 1);
            return;
        }
        offsets[used] = offset;
        widths[used] = width;
        modes[used] = (byte) (write ? 2 : 1);
        used++;
    }

    Snapshot snapshot() {
        List<Access> result = new ArrayList<>(used);
        for (int i = 0; i < used; i++) {
            result.add(new Access(offsets[i], widths[i], modes[i] == 2));
        }
        return new Snapshot(List.copyOf(result), dropped, offsets.length, scopeId);
    }

    void clear() {
        java.util.Arrays.fill(offsets, 0);
        java.util.Arrays.fill(widths, 0);
        java.util.Arrays.fill(modes, (byte) 0);
        used = 0;
        dropped = 0;
    }
}
