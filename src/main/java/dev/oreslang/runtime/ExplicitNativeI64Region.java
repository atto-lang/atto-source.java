package dev.oreslang.runtime;

import java.lang.reflect.InvocationTargetException;
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

    private final Object identity = new Object();
    private final Runnable requireActor;
    private final Runnable onRetired;
    private final ActorRuntime.MemoryReservation reservation;
    private final Object nativeArena;
    private final Object segment;
    private final Object longLayout;
    private final Method getLong;
    private final Method putLong;
    private final Method closeArena;
    private final boolean[] live;
    private final long[] generations;
    private final AtomicBoolean closed = new AtomicBoolean();
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

    public synchronized void drop(Slot slot) {
        requireActor.run();
        int index = requireLive(slot);
        if (generations[index] == Long.MAX_VALUE) {
            throw new IllegalStateException("native slot generation exhausted");
        }
        put(index, 0L);
        generations[index]++;
        live[index] = false;
        occupied--;
    }

    public synchronized int size() { return occupied; }
    public synchronized boolean closed() { return closed.get(); }

    @Override public void close() {
        requireActor.run();
        retireFromActor();
    }

    void retireFromActor() {
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            try {
                // No live native pointer or MemorySegment escaped the wrapper.
                // FFM scope close releases the physical native region.
                closeArena.invoke(nativeArena);
            } catch (IllegalAccessException | InvocationTargetException error) {
                throw new IllegalStateException("failed physically releasing actor-native region", error);
            } finally {
                Arrays.fill(live, false);
                occupied = 0;
                try {
                    reservation.close();
                } finally {
                    onRetired.run();
                }
            }
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
