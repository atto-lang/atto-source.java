package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class ActorForeignMemoryTest {
    @Test
    void explicitlyClosesNativeRegionAndInvalidatesWrapper() {
        ActorForeignMemory block = ActorForeignMemory.allocate(24);
        boolean foreignAvailable;
        try {
            Class.forName("java.lang.foreign.Arena");
            foreignAvailable = true;
        } catch (ClassNotFoundException unsupportedJdk) {
            foreignAvailable = false;
        }
        assertEquals(foreignAvailable, block.deterministicReclamation());
        ByteBuffer view = block.memory();
        view.putLong(0, 0x7A5C_3412_8877_4411L);
        assertEquals(0x7A5C_3412_8877_4411L, block.memory().getLong(0));
        block.close();
        block.close();
        assertThrows(IllegalStateException.class, block::memory);
    }

    @Test
    void closingFromDifferentCarrierReleasesSharedArena() throws Exception {
        ActorForeignMemory block = ActorForeignMemory.allocate(128);
        block.memory().putLong(0, 731L);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                assertEquals(731L, block.memory().getLong(0));
                block.close();
                block.close();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        other.start();
        other.join();
        assertNull(failure.get(), "shared arena must close from a different carrier");
        assertThrows(IllegalStateException.class, block::memory);
    }

    @Test
    void savedBufferCannotOutliveCloseWhenBackedByFfm() {
        ActorForeignMemory block = ActorForeignMemory.allocate(32);
        ByteBuffer saved = block.memory();
        saved.putInt(0, 42);
        boolean ffm = block.deterministicReclamation();
        block.close();
        if (ffm) {
            assertThrows(IllegalStateException.class, () -> saved.getInt(0));
        } else {
            // Java 21 without FFM has a direct-buffer fallback. The bytes
            // are zeroed, but the raw saved view cannot be revoked.
            assertEquals(0, saved.getInt(0));
        }
    }

    @Test
    void zeroSizedAllocationHasNoReadableBytes() {
        try (ActorForeignMemory block = ActorForeignMemory.allocate(0)) {
            assertEquals(0, block.memory().capacity());
        }
    }

    @Test
    void sizeAndBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> ActorForeignMemory.allocate(-1));
        try (ActorForeignMemory block = ActorForeignMemory.allocate(8)) {
            assertThrows(IndexOutOfBoundsException.class, () -> block.memory().getLong(1));
        }
    }
}
