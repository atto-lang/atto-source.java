package dev.oreslang.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
            boolean publishRetirement = false;
            synchronized (ExplicitNativeI64Region.this) {
                if (released.get()) return;
                if (slotLoans[slot.index] <= 0 || loanCount <= 0) {
                    throw new IllegalStateException("actor-native loan accounting underflow");
                }
                // Linearize one-use release under the same monitor protecting
                // the slot and total loan counts. A failed precondition never
                // consumes this loan's sole release authority.
                released.set(true);
                slotLoans[slot.index]--;
                loanCount--;
                if (closed.get() && loanCount == 0) {
                    finishPhysicalClose();
                    publishRetirement = true;
                }
            }
            // Never invoke registry callbacks while holding the FFM region
            // monitor. Other actor lifecycle callbacks may use lifecycleLock.
            if (publishRetirement) publishPhysicalRetirement();
        }
    }

    private final Object identity = new Object();
    private final Runnable requireActor;
    private final Runnable onRetired;
    private final Consumer<Throwable> onPhysicalCloseFailure;
    private final ActorRuntime.MemoryReservation reservation;
    private final Object nativeArena;
    private final Object segment;
    private final Object longLayout;
    private final Method getLong;
    private final Method putLong;
    private final Method closeArena;
    private final boolean[] live;
    private final int[] slotLoans;
    private final long[] generations;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean physicalClosed = new AtomicBoolean();
    /**
     * Package-private fault seam for deterministic tests of the split
     * Arena.close -> quota refund -> ownership-registry removal protocol.
     * The owning actor must arm it before STOP; never exposed to guest code.
     */
    private volatile Runnable afterArenaCloseProbe = () -> { };
    private volatile Runnable afterQuotaRefundProbe = () -> { };
    private int loanCount;
    private int occupied;

    ExplicitNativeI64Region(
            int slots, ActorRuntime.MemoryReservation reservation,
            Runnable requireActor, Runnable onRetired,
            Consumer<Throwable> onPhysicalCloseFailure) {
        if (slots < 1 || slots > 65536) {
            throw new IllegalArgumentException("native region size must be 1..65536 slots");
        }
        this.requireActor = Objects.requireNonNull(requireActor);
        this.onRetired = Objects.requireNonNull(onRetired);
        this.onPhysicalCloseFailure = Objects.requireNonNull(onPhysicalCloseFailure);
        this.reservation = Objects.requireNonNull(reservation);
        this.live = new boolean[slots];
        this.slotLoans = new int[slots];
        this.generations = new long[slots];
        Arrays.fill(generations, 1L);
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
            this.getLong = segmentType.getMethod("get", ofLongType, long.class);
            this.putLong = segmentType.getMethod("set", ofLongType, long.class, long.class);
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
        for (int i = 0; i < live.length; i++) {
            if (!live[i] && generations[i] != Long.MAX_VALUE) {
                put(i, value);
                live[i] = true;
                occupied++;
                return new Slot(identity, i, generations[i]);
            }
        }
        throw new IllegalStateException("explicit actor-native heap capacity exhausted");
    }

    public synchronized long read(Slot slot) {
        requireActor.run();
        return get(requireLive(slot));
    }

    public synchronized void write(Slot slot, long value) {
        requireActor.run();
        put(requireLive(slot), value);
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
        occupied--;
    }

    void installRetirementProbesForTesting(
            Runnable afterArenaClose, Runnable afterQuotaRefund) {
        requireActor.run();
        synchronized (this) {
            requireOpen();
            afterArenaCloseProbe = Objects.requireNonNull(afterArenaClose);
            afterQuotaRefundProbe = Objects.requireNonNull(afterQuotaRefund);
        }
    }

    public synchronized int size() { return occupied; }
    public synchronized boolean closed() { return closed.get(); }
    public synchronized boolean physicallyClosed() { return physicalClosed.get(); }
    public synchronized int activeLoans() { return loanCount; }

    @Override public void close() {
        requireActor.run();
        retireFromActor();
    }

    /** Stop admission immediately; defer FFM arena close until loans drain. */
    void retireFromActor() {
        boolean publishRetirement = false;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            if (loanCount == 0) {
                finishPhysicalClose();
                publishRetirement = true;
            }
        }
        if (publishRetirement) publishPhysicalRetirement();
    }

    private void finishPhysicalClose() {
        if (!closed.get() || loanCount != 0 || physicalClosed.get()) return;
        try {
            closeArena.invoke(nativeArena);
        } catch (IllegalAccessException | InvocationTargetException error) {
            // Fail closed: never report physical done or refund pinned quota.
            IllegalStateException failure = new IllegalStateException(
                    "failed physically releasing actor-native region", error);
            onPhysicalCloseFailure.accept(failure);
            throw failure;
        }
        physicalClosed.set(true);
        Arrays.fill(live, false);
        occupied = 0;
        try {
            // The executor can block or throw at each fault seam in a
            // completion thread, but never reopens guest actor admission.
            // Published completion must wait for ALL stages.
            afterArenaCloseProbe.run();
            reservation.close();
            afterQuotaRefundProbe.run();
        } catch (RuntimeException | Error releaseFailure) {
            onPhysicalCloseFailure.accept(releaseFailure);
            throw releaseFailure;
        }
        // Ownership registry publication deliberately occurs after the FFM
        // monitor is released, in publishPhysicalRetirement().
    }

    private void publishPhysicalRetirement() {
        try {
            onRetired.run();
        } catch (RuntimeException | Error failure) {
            onPhysicalCloseFailure.accept(failure);
            throw failure;
        }
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
            return (long) getLong.invoke(segment, longLayout, (long) index * 8);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("actor native slot read failed", failure);
        }
    }

    private void put(int index, long value) {
        try {
            putLong.invoke(segment, longLayout, (long) index * 8, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("actor native slot write failed", failure);
        }
    }
}
