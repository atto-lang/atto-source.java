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
