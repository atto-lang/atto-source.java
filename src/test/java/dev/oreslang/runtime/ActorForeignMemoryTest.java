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
