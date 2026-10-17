package dev.oreslang.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit native i64 backing using Java 25 FFM Arena.ofShared().
 *
 * This class is compiled for Java 21 compatibility and resolves the FFM API
 * reflectively at runtime. Unlike a DirectByteBuffer cleaner, close of its
 * owning FFM Arena deterministically invalidates/releases the native segment.
 * The Java wrapper/control plane is still GC managed: this is a native
 * guest-data allocator, not a no-JVM-GC or independently traced actor heap.
 *
 * No raw MemorySegment escapes. ActorId rather than carrier thread controls
 * access; Arena.ofShared supports actor continuation carrier migration.
 */
public final class ExplicitNativeI64Region implements AutoCloseable {
    public static final class Slot {
        private final Object owner;
        private final int index;
        private final long generation;
        private Slot(Object owner, int index, long generation) {
            this.owner = owner;
            this.index = index;
            this.generation = generation;
        }
        public int index() { return index; }
        public long generation() { return generation; }
    }

    /**
     * Host-only lease that can outlive a logical actor turn while an async
     * completion is pending. Lease close does not dereference native memory,
     * and may run on an arbitrary completion thread.
     */
    public final class Loan implements AutoCloseable {
        private final Slot slot;
        private final AtomicBoolean released = new AtomicBoolean();

        private Loan(Slot slot) { this.slot = slot; }

        public long read() {
            requireActor.run();
            synchronized (ExplicitNativeI64Region.this) {
                if (released.get()) throw new IllegalStateException("native loan released");
                return get(requireLive(slot));
            }
        }

        @Override public void close() {
            synchronized (ExplicitNativeI64Region.this) {
                if (!released.get()) {
                    // A released loan must decrement counts exactly once.
                    if (slotLoans[slot.index] <= 0 || loanCount <= 0) {
                        throw new IllegalStateException("actor-native loan accounting underflow");
                    }
                    slotLoans[slot.index]--;
                    loanCount--;
                    released.set(true);
                }
                // A failed native close/accounting callback must be retryable
                // by the *same* completion lease after logical release.
                // Otherwise the retired actor may have no remaining carrier
                // that can call region.retireFromActor() a second time.
                if (closed.get() && loanCount == 0 && !physicalClosed.get()) {
                    finishPhysicalClose();
                }
            }
        }
    }

    /**
     * Lifetime-only pin for an external continuation/FFI/I/O owner.
     *
     * The pin intentionally exposes no native-memory read/write operation. It
     * only delays physical Arena.close() after logical retirement, and may be
     * released from an arbitrary completion thread.
     */
    public final class LifetimePin implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();

        private LifetimePin() { }

        @Override public void close() {
            synchronized (ExplicitNativeI64Region.this) {
                if (!released.get()) {
                    if (lifetimePinCount <= 0) {
                        throw new IllegalStateException("actor-native lifetime pin accounting underflow");
                    }
                    lifetimePinCount--;
                    released.set(true);
                }
                // Retry physical close/accounting on repeated close after a
                // prior native/accounting failure, just like Loan.close().
                if (closed.get() && loanCount == 0 && lifetimePinCount == 0
                        && !physicalClosed.get()) {
                    finishPhysicalClose();
                }
            }
        }
    }

    private final Object identity = new Object();
    private final Runnable requireActor;
    private final Runnable onRetired;
    private final ActorRuntime.MemoryReservation reservation;
    private final Object nativeArena;
    private final Object segment;
    private final Object longLayout;
    private final MethodHandle getLong;
    private final MethodHandle putLong;
    private final Method closeArena;
    private final boolean[] live;
    private final int[] slotLoans;
    private final long[] generations;
    /** Preallocated O(1) allocator metadata; no search over occupied slots. */
    private final int[] freeSlots;
    private int freeCount;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean physicalClosed = new AtomicBoolean();
    private int loanCount;
    private int lifetimePinCount;
    private int occupied;

    ExplicitNativeI64Region(
            int slots, ActorRuntime.MemoryReservation reservation,
            Runnable requireActor, Runnable onRetired) {
        if (slots < 1 || slots > 65536) {
            throw new IllegalArgumentException("native region size must be 1..65536 slots");
        }
        this.requireActor = Objects.requireNonNull(requireActor);
        this.onRetired = Objects.requireNonNull(onRetired);
        this.reservation = Objects.requireNonNull(reservation);
        this.live = new boolean[slots];
        this.slotLoans = new int[slots];
        this.generations = new long[slots];
        this.freeSlots = new int[slots];
        this.freeCount = slots;
        Arrays.fill(generations, 1L);
        for (int i = 0; i < slots; i++) freeSlots[i] = slots - i - 1;
        Object allocatedArena = null;
        try {
            // The class is deliberately unavailable on Java 21. Never
            // silently substitute an unmanaged DirectByteBuffer.
            Class<?> arenaType = Class.forName("java.lang.foreign.Arena");
            Class<?> segmentType = Class.forName("java.lang.foreign.MemorySegment");
            Class<?> valueLayoutType = Class.forName("java.lang.foreign.ValueLayout");
            Class<?> ofLongType = Class.forName("java.lang.foreign.ValueLayout$OfLong");
            allocatedArena = arenaType.getMethod("ofShared").invoke(null);
            Object nativeLong = valueLayoutType.getField("JAVA_LONG_UNALIGNED").get(null);
            this.longLayout = ofLongType.getMethod("withOrder", ByteOrder.class)
                    .invoke(nativeLong, ByteOrder.LITTLE_ENDIAN);
            this.segment = arenaType.getMethod("allocate", long.class, long.class)
                    .invoke(allocatedArena, Math.multiplyExact((long) slots, 8L), 8L);
            // Resolve/reshape signatures once. Method.invoke on every slot
            // access creates a reflective Object[] and boxes long values;
            // invokeExact keeps primitive i64 reads/writes allocation-free.
            this.getLong = MethodHandles.publicLookup()
                    .unreflect(segmentType.getMethod("get", ofLongType, long.class))
                    .asType(MethodType.methodType(long.class,
                            Object.class, Object.class, long.class));
            this.putLong = MethodHandles.publicLookup()
                    .unreflect(segmentType.getMethod("set", ofLongType, long.class, long.class))
                    .asType(MethodType.methodType(void.class,
                            Object.class, Object.class, long.class, long.class));
            this.closeArena = arenaType.getMethod("close");
            this.nativeArena = allocatedArena;
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (allocatedArena != null) {
                try {
                    allocatedArena.getClass().getMethod("close").invoke(allocatedArena);
                } catch (ReflectiveOperationException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            throw new IllegalStateException(
                    "explicit actor-native heap requires supported Java 25 FFM; no managed-buffer fallback", error);
        }
    }

    public static boolean isAvailable() {
        try {
            Class.forName("java.lang.foreign.Arena");
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    public synchronized Slot allocate(long value) {
        requireActor.run();
        requireOpen();
        if (freeCount == 0) {
            throw new IllegalStateException("explicit actor-native heap capacity exhausted");
        }
        int index = freeSlots[freeCount - 1];
        if (index < 0 || index >= live.length || live[index]
                || generations[index] == Long.MAX_VALUE) {
            throw new IllegalStateException("actor-native allocator free-list invariant violated");
        }
        // Commit only after native FFM write succeeds.
        put(index, value);
        freeCount--;
        live[index] = true;
        occupied++;
        return new Slot(identity, index, generations[index]);
    }

    public synchronized long read(Slot slot) {
        requireActor.run();
        return get(requireLive(slot));
    }

    public synchronized void write(Slot slot, long value) {
        requireActor.run();
        int index = requireLive(slot);
        // A live shared loan may cross await/FFI boundaries. Do not mutate
        // underneath a pinned borrower; release all loans before writing.
        if (slotLoans[index] != 0) {
            throw new IllegalStateException("cannot mutate loaned actor-native storage");
        }
        put(index, value);
    }

    public synchronized Loan borrow(Slot slot) {
        requireActor.run();
        int index = requireLive(slot);
        if (slotLoans[index] == Integer.MAX_VALUE || loanCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("actor-native loan limit reached");
        }
        slotLoans[index]++;
        loanCount++;
        return new Loan(slot);
    }

    /** Acquire lifetime authority without granting native-memory access. */
    synchronized LifetimePin pinLifetime() {
        requireActor.run();
        requireOpen();
        if (lifetimePinCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("actor-native lifetime pin limit reached");
        }
        lifetimePinCount++;
        return new LifetimePin();
    }

    public synchronized void drop(Slot slot) {
        requireActor.run();
        int index = requireLive(slot);
        if (slotLoans[index] != 0) {
            throw new IllegalStateException("cannot drop loaned actor-native storage");
        }
        if (generations[index] == Long.MAX_VALUE) {
            throw new IllegalStateException("native slot generation exhausted");
        }
        put(index, 0L);
        generations[index]++;
        live[index] = false;
        if (freeCount >= freeSlots.length) {
            throw new IllegalStateException("actor-native allocator free-list overflow");
        }
        freeSlots[freeCount++] = index;
        occupied--;
    }

    public synchronized int size() { return occupied; }
    public synchronized boolean closed() { return closed.get(); }
    public synchronized boolean physicallyClosed() { return physicalClosed.get(); }
    public synchronized int activeLoans() { return loanCount; }
    public synchronized int activeLifetimePins() { return lifetimePinCount; }

    @Override public void close() {
        requireActor.run();
        retireFromActor();
    }

    /** Stop admission immediately; defer FFM arena close until loans drain. */
    void retireFromActor() {
        synchronized (this) {
            closed.set(true);
            // Retry a failed FFM close rather than permanently stranding
            // physically retained bytes behind an already-retired flag.
            // An outstanding loan still pins native storage until released.
            if (loanCount == 0 && lifetimePinCount == 0 && !physicalClosed.get()) {
                finishPhysicalClose();
            }
        }
    }

    private boolean nativeScopeClosed;

    private void finishPhysicalClose() {
        if (!closed.get() || loanCount != 0 || lifetimePinCount != 0
                || physicalClosed.get()) return;
        if (!nativeScopeClosed) {
            // Actor isolation requires explicit scrubbing before returning the
            // native allocation to the process allocator. Arena.close() gives
            // deterministic lifetime, but it does not constitute our
            // cross-backend zeroization contract. With no outstanding loans,
            // overwrite every slot (including currently-free slots) before
            // physical release so a later native allocation cannot inherit
            // another actor's residual bytes.
            for (int index = 0; index < live.length; index++) {
                put(index, 0L);
            }
            try {
                closeArena.invoke(nativeArena);
            } catch (IllegalAccessException | InvocationTargetException error) {
                // Keep the reservation pinned if native memory was not closed.
                // A retry will scrub again before attempting close.
                throw new IllegalStateException("failed physically releasing actor-native region", error);
            }
            nativeScopeClosed = true;
            Arrays.fill(live, false);
            occupied = 0;
        }
        // If bookkeeping fails after Arena.close(), retries must NOT close the
        // native Arena twice. The reservation itself is retryable/idempotent.
        reservation.close();
        onRetired.run();
        physicalClosed.set(true);
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("actor native region retired");
    }

    private int requireLive(Slot slot) {
        requireOpen();
        if (slot == null || slot.owner != identity || slot.index < 0
                || slot.index >= live.length || !live[slot.index]
                || generations[slot.index] != slot.generation) {
            throw new IllegalStateException("stale or foreign actor-native scalar");
        }
        return slot.index;
    }

    private long get(int index) {
        try {
            return (long) getLong.invokeExact(segment, longLayout, (long) index * 8);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("actor native slot read failed", failure);
        }
    }

    private void put(int index, long value) {
        try {
            putLong.invokeExact(segment, longLayout, (long) index * 8, value);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("actor native slot write failed", failure);
        }
    }
}
