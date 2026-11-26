package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

final class NativeLexicalRetirementCurrentMainTest {
    @Test
    void retirementClearsSlotButPreservesOtherLiveReference() {
        var charged = new AtomicLong();
        try (var arena = new ActorNativeArena(65536, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            Object marker = new Object();
            var list = arena.nativeList();
            var first = arena.valueCell(list, marker);
            var second = arena.valueCell(list, marker);
            long committed = arena.committedBytes();
            assertEquals(1, arena.nativeReferenceCount());
            first.close();
            first.close();
            assertThrows(IllegalStateException.class, first::get);
            assertThrows(IllegalStateException.class, first::nativeOffset);
            assertThrows(IllegalStateException.class, () -> first.trySet(42L));
            assertSame(list, second.get());
            assertEquals(1, arena.nativeReferenceCount());
            second.close();
            assertEquals(0, arena.nativeReferenceCount());
            assertEquals(committed, arena.committedBytes(), "slab lifetime is actor lifetime");
        }
        assertEquals(0, charged.get());
    }

    @Test
    void retirementRequiresOwnerEvenForIdempotentClose() throws Exception {
        Thread owner = Thread.currentThread();
        try (var arena = new ActorNativeArena(65536, () -> {
            if (Thread.currentThread() != owner) throw new SecurityException("foreign owner");
        }, bytes -> () -> {})) {
            var cell = arena.valueCell(42L, new Object());
            rejectForeignClose(cell);
            assertEquals(42L, cell.get());
            cell.close();
            rejectForeignClose(cell);
            assertThrows(IllegalStateException.class, cell::get);
        }
    }

    @Test
    void moveRetiresSourceAndKeepsRegistryAndNativeAllocationStable() {
        try (var arena = new ActorNativeArena(65536, () -> {}, bytes -> () -> {})) {
            var list = arena.nativeList();
            list.add(42L);
            var source = arena.valueCell(list, new Object());
            var target = arena.valueCell(null, new Object());
            var returned = arena.valueCell(null, new Object());
            long allocated = arena.allocatedBytes();
            long committed = arena.committedBytes();
            source.moveReferenceTo(target);
            assertThrows(IllegalStateException.class, source::get);
            assertThrows(IllegalStateException.class, source::nativeOffset);
            assertThrows(IllegalStateException.class, () -> source.trySet(list));
            source.close();
            assertSame(list, target.get());
            assertEquals(42L, list.get(0));
            target.moveReferenceTo(returned);
            assertThrows(IllegalStateException.class, target::get);
            assertSame(list, returned.get());
            assertEquals(1, arena.nativeReferenceCount());
            assertEquals(allocated, arena.allocatedBytes());
            assertEquals(committed, arena.committedBytes());
            returned.close();
            assertEquals(0, arena.nativeReferenceCount());
        }
    }

    @Test
    void rejectedMovesPreserveBothCellsAndReferenceAccounting() {
        try (var arena = new ActorNativeArena(65536, () -> {}, bytes -> () -> {});
             var other = new ActorNativeArena(65536, () -> {}, bytes -> () -> {})) {
            var list = arena.nativeList();
            var source = arena.valueCell(list, new Object());
            var empty = arena.valueCell(null, new Object());
            var occupied = arena.valueCell(7L, new Object());
            var foreign = other.valueCell(null, new Object());
            var retired = arena.valueCell(null, new Object());
            retired.close();
            assertThrows(IllegalArgumentException.class, () -> source.moveReferenceTo(source));
            assertThrows(IllegalArgumentException.class, () -> source.moveReferenceTo(foreign));
            assertThrows(IllegalArgumentException.class, () -> source.moveReferenceTo(null));
            assertThrows(IllegalStateException.class, () -> source.moveReferenceTo(occupied));
            assertThrows(IllegalStateException.class, () -> source.moveReferenceTo(retired));
            assertThrows(IllegalStateException.class, () -> occupied.moveReferenceTo(empty));
            var alias = arena.valueCell(list, new Object());
            assertThrows(IllegalStateException.class, () -> source.moveReferenceTo(empty));
            assertSame(list, alias.get());
            assertSame(list, source.get());
            assertNull(empty.get());
            assertNull(foreign.get());
            assertEquals(7L, occupied.get());
            assertEquals(1, arena.nativeReferenceCount());
            alias.close();
            source.moveReferenceTo(empty);
            assertSame(list, empty.get());
            empty.close();
            assertEquals(0, arena.nativeReferenceCount());
        }
    }

    @Test
    void foreignCarrierCannotMoveLiveReference() throws Exception {
        Thread owner = Thread.currentThread();
        try (var arena = new ActorNativeArena(65536, () -> {
            if (Thread.currentThread() != owner) throw new SecurityException("foreign owner");
        }, bytes -> () -> {})) {
            var list = arena.nativeList();
            var source = arena.valueCell(list, new Object());
            var target = arena.valueCell(null, new Object());
            var failure = new AtomicReference<Throwable>();
            Thread other = new Thread(() -> {
                try { source.moveReferenceTo(target); }
                catch (Throwable error) { failure.set(error); }
            });
            other.start();
            other.join(5000);
            assertFalse(other.isAlive());
            assertInstanceOf(SecurityException.class, failure.get());
            assertSame(list, source.get());
            assertNull(target.get());
            source.moveReferenceTo(target);
            target.close();
        }
    }

    private static void rejectForeignClose(ActorNativeArena.ValueCell cell) throws Exception {
        var failure = new AtomicReference<Throwable>();
        Thread other = new Thread(() -> {
            try { cell.close(); }
            catch (Throwable error) { failure.set(error); }
        });
        other.start();
        other.join(5000);
        assertFalse(other.isAlive());
        assertInstanceOf(SecurityException.class, failure.get());
    }
}
