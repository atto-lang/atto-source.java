package dev.oreslang.runtime.accel;

import dev.oreslang.runtime.DataOrientedLayout;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic reference-retirement assertions, not a claim that the JVM
 * immediately returns direct memory to its native allocator.
 */
final class DopHostBufferCloseReclamationTest {
    private static DataOrientedLayout.Plan shape() {
        return new DataOrientedLayout.Plan(DataOrientedLayout.Kind.SOA, 7, 3, 4, 16, 1);
    }

    private static Object stateOf(DopKernelIR.HostBuffer buffer) throws Exception {
        Field field = DopKernelIR.HostBuffer.class.getDeclaredField("state");
        field.setAccessible(true);
        return field.get(buffer);
    }

    private static ByteBuffer backingOf(Object state) throws Exception {
        Field field = state.getClass().getDeclaredField("bytes");
        field.setAccessible(true);
        return (ByteBuffer) field.get(state);
    }

    @Test
    void successfulCloseDropsStrongDirectBufferReference() throws Exception {
        var owner = new DopKernelIR.HostBuffer(shape());
        var state = stateOf(owner);
        var root = owner.regionRoot();
        assertTrue(backingOf(state).isDirect());
        owner.write(2, 1, 31f);
        assertEquals(31f, owner.read(2, 1));
        owner.close();
        assertNull(backingOf(state), "closed State must not pin direct allocation");
        assertFalse(root.isLiveOwned());
        assertThrows(IllegalStateException.class, () -> owner.read(2, 1));
        assertThrows(IllegalStateException.class, owner::borrowRead);
        owner.close();
        assertNull(backingOf(state), "repeated close must be idempotent");
    }

    @Test
    void rejectedCloseCannotDropActiveBorrowedMemory() throws Exception {
        var owner = new DopKernelIR.HostBuffer(shape());
        var state = stateOf(owner);
        owner.write(1, 2, 5f);
        try (var read = owner.borrowRead()) {
            var liveBytes = backingOf(state);
            assertThrows(IllegalStateException.class, owner::close);
            assertSame(liveBytes, backingOf(state));
            assertEquals(5f, read.read(1, 2));
        }
        try (var write = owner.borrowWrite()) {
            var liveBytes = backingOf(state);
            assertThrows(IllegalStateException.class, owner::close);
            assertSame(liveBytes, backingOf(state));
            write.write(1, 2, 17f);
        }
        assertEquals(17f, owner.read(1, 2));
        owner.close();
        assertNull(backingOf(state));
    }

    @Test
    void movedFromCloseDoesNotReclaimSuccessorMemory() throws Exception {
        var owner = new DopKernelIR.HostBuffer(shape());
        var state = stateOf(owner);
        var original = backingOf(state);
        var token = owner.regionToken();
        var root = owner.regionRoot();
        var successor = owner.take();
        owner.close();
        assertSame(state, stateOf(successor));
        assertSame(original, backingOf(state));
        assertTrue(token.isLive());
        assertTrue(root.sameRoot(successor.regionRoot()));
        successor.write(0, 0, 7f);
        assertEquals(7f, successor.read(0, 0));
        successor.close();
        assertNull(backingOf(state));
        assertFalse(token.isLive());
        assertFalse(root.isLiveOwned());
    }
}
