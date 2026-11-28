package dev.oreslang.runtime;

import java.util.Arrays;

/**
 * Allocation-free hierarchical timing wheel: the runtime's logical timer
 * engine (contracts/TIMER_ENGINE_V1.md).
 *
 * <p>Six levels of 64 slots cover 2^36 ticks; later deadlines wait on an
 * overflow list. A timer is placed by the highest 6-bit tick group in which its
 * expiry tick differs from the current tick, so a slot never aliases the
 * current position and the first non-empty level always holds the next event.
 * Arm and cancel are O(1). Advancing jumps directly between occupied slots
 * using one occupancy bitmap per level, so a long idle gap costs at most one
 * cascade per level, not one step per tick.</p>
 *
 * <p>Records live in parallel primitive arrays indexed by a 32-bit slot
 * number. A handle is {@code (generation << 32) | (index + 1)}; zero is never
 * a valid handle. A slot's generation advances when the record is freed, so a
 * stale handle can neither cancel nor observe a reused record. Exhausted
 * generation slots retire permanently rather than wrap.</p>
 *
 * <p>The clock is supplied by the caller on every call (monotonic nanoseconds,
 * never negative), so tests can drive a virtual clock deterministically. A
 * timer fires on the first {@link #poll} whose {@code nowNanos} is at or past
 * its deadline, never earlier. Expired timers are emitted in expiry-tick order
 * and, within one tick, in the order they reached that tick (arm order unless
 * a cascade intervened), at most {@code max} per poll (bounded draining);
 * queued timers stay cancellable until emitted.</p>
 *
 * <p>Not thread-safe: one owner (a scheduler shard or a timer driver) serialises
 * all calls. After warm-up (capacity reached) no method allocates.</p>
 */
public final class OresTimerWheel<T> {
    public static final int LEVELS = 6;
    static final int SLOT_BITS = 6;
    static final int SLOTS = 1 << SLOT_BITS;
    static final int SPAN_BITS = LEVELS * SLOT_BITS; // 36
    /** Returned by {@link #nextDeadlineNanos()} when no timer is armed. */
    public static final long NO_DEADLINE = Long.MAX_VALUE;

    private static final int NIL = -1;
    private static final int FREE = -1;
    // List ids: 0..LEVELS*SLOTS-1 are wheel slots, then three special lists.
    private static final int DUE = LEVELS * SLOTS;
    private static final int CURRENT = DUE + 1;
    private static final int OVERFLOW = DUE + 2;
    private static final int LISTS = DUE + 3;
    private static final long NONE = Long.MAX_VALUE;

    private final long tickNanos;
    private final int maxCapacity;

    private long[] deadline;
    private int[] next;
    private int[] prev;
    private int[] where;
    private int[] generation;
    private Object[] attachment;

    // Every list is FIFO (head/tail), so timers that share a tick fire in the
    // order they reached it; Java and native wheels emit identical sequences.
    private final int[] head = new int[LISTS];
    private final int[] tail = new int[LISTS];
    private final long[] occupancy = new long[LEVELS];
    private int freeHead = NIL;
    private int size;

    private long currentTick;
    private long nowNanos;
    private int eventLevel; // set by nextEventTick(): level, or -1 for overflow

    public OresTimerWheel(int initialCapacity, int maxCapacity, long tickNanos, long nowNanos) {
        if (initialCapacity <= 0 || maxCapacity < initialCapacity) {
            throw new IllegalArgumentException("timer wheel capacity");
        }
        if (tickNanos <= 0) throw new IllegalArgumentException("tick must be positive");
        if (nowNanos < 0) throw new IllegalArgumentException("negative monotonic time");
        this.tickNanos = tickNanos;
        this.maxCapacity = maxCapacity;
        this.nowNanos = nowNanos;
        this.currentTick = nowNanos / tickNanos;
        Arrays.fill(head, NIL);
        Arrays.fill(tail, NIL);
        deadline = new long[initialCapacity];
        next = new int[initialCapacity];
        prev = new int[initialCapacity];
        where = new int[initialCapacity];
        generation = new int[initialCapacity];
        attachment = new Object[initialCapacity];
        Arrays.fill(generation, 1);
        linkFree(0, initialCapacity);
    }

    public int size() { return size; }
    public int capacity() { return deadline.length; }
    public long tickNanos() { return tickNanos; }
    public long lastNowNanos() { return nowNanos; }

    /**
     * Arm a timer for an absolute monotonic deadline. A deadline at or before
     * the last observed time is due at the next poll. Returns 0 when the wheel
     * has no reusable capacity or the reserved {@link #NO_DEADLINE} sentinel
     * is supplied.
     */
    public long arm(long deadlineNanos, T value) {
        if (deadlineNanos < 0) throw new IllegalArgumentException("negative deadline");
        if (deadlineNanos == NO_DEADLINE || (freeHead == NIL && !grow())) return 0;
        int i = freeHead;
        freeHead = next[i];
        deadline[i] = deadlineNanos;
        attachment[i] = value;
        size++;
        place(i);
        return handle(i);
    }

    /** True only if the handle named a live (armed or queued) timer. */
    public boolean cancel(long handle) {
        int i = indexOf(handle);
        if (i < 0) return false;
        unlink(i);
        release(i);
        return true;
    }

    /** True while the handle names an armed or queued, not yet emitted, timer. */
    public boolean isLive(long handle) {
        return indexOf(handle) >= 0;
    }

    /**
     * Advance to {@code nowNanos} and emit up to {@code max} expired timers in
     * FIFO expiry order into the caller's reusable arrays. Emitted handles are
     * invalid on return. A clock reading earlier than the last one is treated
     * as the last one (monotonic clamp).
     */
    public int poll(long nowNanos, int max, long[] outHandles, Object[] outValues) {
        advance(nowNanos);
        int n = 0;
        while (n < max && head[DUE] != NIL) {
            int i = head[DUE];
            unlink(i);
            outHandles[n] = handle(i);
            outValues[n] = attachment[i];
            release(i);
            n++;
        }
        return n;
    }

    /**
     * Earliest time at which a poll can emit something: the last observed time
     * if timers are already due, else a lower bound on the next deadline (a
     * cascade boundary may come first), or {@link #NO_DEADLINE}. Never later
     * than the true next deadline, so a driver parking until it is never late.
     */
    public long nextDeadlineNanos() {
        if (head[DUE] != NIL) return nowNanos;
        long best = NONE;
        for (int i = head[CURRENT]; i != NIL; i = next[i]) best = Math.min(best, deadline[i]);
        if (best != NONE) return best;
        long t = nextEventTick();
        if (t == NONE) return NO_DEADLINE;
        return t > Long.MAX_VALUE / tickNanos ? NO_DEADLINE - 1 : t * tickNanos;
    }

    // ---- internals -------------------------------------------------------

    private long handle(int i) {
        return ((long) generation[i] << 32) | (i + 1L);
    }

    private int indexOf(long handle) {
        long low = handle & 0xffff_ffffL;
        if (low == 0 || low > deadline.length) return -1;
        int i = (int) (low - 1);
        if (generation[i] != (int) (handle >>> 32) || where[i] == FREE) return -1;
        return i;
    }

    private void release(int i) {
        attachment[i] = null;
        where[i] = FREE;
        size--;
        if (generation[i] == -1) { // final unsigned 32-bit generation
            generation[i] = 0;
            next[i] = NIL;
            return;
        }
        generation[i]++;
        next[i] = freeHead;
        freeHead = i;
    }

    private void linkFree(int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            where[i] = FREE;
            next[i] = freeHead;
            freeHead = i;
        }
    }

    private boolean grow() {
        int old = deadline.length;
        if (old >= maxCapacity) return false;
        int cap = (int) Math.min((long) maxCapacity, Math.max(old * 2L, old + 1L));
        deadline = Arrays.copyOf(deadline, cap);
        next = Arrays.copyOf(next, cap);
        prev = Arrays.copyOf(prev, cap);
        where = Arrays.copyOf(where, cap);
        generation = Arrays.copyOf(generation, cap);
        attachment = Arrays.copyOf(attachment, cap);
        Arrays.fill(generation, old, cap, 1);
        linkFree(old, cap);
        return true;
    }

    private void place(int i) {
        long d = deadline[i];
        if (d <= nowNanos) { append(i, DUE); return; }
        long e = d / tickNanos;
        if (e <= currentTick) { append(i, CURRENT); return; }
        int level = (63 - Long.numberOfLeadingZeros(e ^ currentTick)) / SLOT_BITS;
        if (level >= LEVELS) { append(i, OVERFLOW); return; }
        int s = (int) ((e >>> (level * SLOT_BITS)) & (SLOTS - 1));
        append(i, level * SLOTS + s);
        occupancy[level] |= 1L << s;
    }

    private void append(int i, int list) {
        where[i] = list;
        next[i] = NIL;
        prev[i] = tail[list];
        if (tail[list] != NIL) next[tail[list]] = i; else head[list] = i;
        tail[list] = i;
    }

    private void unlink(int i) {
        int list = where[i], p = prev[i], n = next[i];
        if (p != NIL) next[p] = n; else head[list] = n;
        if (n != NIL) prev[n] = p; else tail[list] = p;
        if (head[list] == NIL && list < DUE) {
            occupancy[list / SLOTS] &= ~(1L << (list % SLOTS));
        }
        next[i] = prev[i] = NIL;
    }

    /** Detach a whole list, returning its first record (chained by next[]). */
    private int take(int list) {
        int first = head[list];
        head[list] = tail[list] = NIL;
        if (list < DUE) occupancy[list / SLOTS] &= ~(1L << (list % SLOTS));
        return first;
    }

    /** Next tick at which a wheel slot (or the overflow list) must be visited. */
    private long nextEventTick() {
        for (int level = 0; level < LEVELS; level++) {
            long occ = occupancy[level];
            if (occ == 0) continue;
            int shift = level * SLOT_BITS;
            int cur = (int) ((currentTick >>> shift) & (SLOTS - 1));
            long above = cur == SLOTS - 1 ? 0 : occ & (-1L << (cur + 1));
            if (above == 0) continue; // cannot happen with differing-group placement
            int s = Long.numberOfTrailingZeros(above);
            eventLevel = level;
            return ((currentTick >>> (shift + SLOT_BITS)) << (shift + SLOT_BITS))
                    | ((long) s << shift);
        }
        if (head[OVERFLOW] != NIL) {
            eventLevel = -1;
            return ((currentTick >>> SPAN_BITS) + 1) << SPAN_BITS;
        }
        return NONE;
    }

    private void advance(long now) {
        if (now < nowNanos) now = nowNanos;
        long target = now / tickNanos;
        nowNanos = now;
        if (target > currentTick) drainCurrent(true);
        for (;;) {
            long t = nextEventTick();
            if (t == NONE || t > target) break;
            currentTick = t;
            int level = eventLevel;
            int list = level < 0 ? OVERFLOW
                    : level * SLOTS + (int) ((t >>> (level * SLOT_BITS)) & (SLOTS - 1));
            int first = take(list);
            // Re-place relative to the new current tick: level-0 entries become
            // due (or CURRENT within the target tick); higher levels cascade.
            while (first != NIL) {
                int i = first;
                first = next[i];
                place(i);
            }
        }
        if (target > currentTick) currentTick = target;
        drainCurrent(false);
    }

    /** Move CURRENT-tick timers that have reached their deadline to DUE. */
    private void drainCurrent(boolean all) {
        int i = head[CURRENT];
        while (i != NIL) {
            int n = next[i];
            if (all || deadline[i] <= nowNanos) {
                unlink(i);
                append(i, DUE);
            }
            i = n;
        }
    }
}
