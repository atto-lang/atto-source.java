package dev.oreslang.runtime.accel;

import dev.oreslang.runtime.accel.GpuBufferFence;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GpuBufferFenceTest {
    @Test void transferRequiresExclusiveQuiescenceAndGeneration() {
        var id = new GpuBufferFence.Identity("tenant-A", "device-0", 7);
        var foreign = new GpuBufferFence.Identity("tenant-B", "device-0", 7);
        var buffer = new GpuBufferFence(id, 1024);
        assertThrows(SecurityException.class, () -> buffer.beginCommand(foreign));
        var command = buffer.beginCommand(id);
        assertEquals(GpuBufferFence.Status.IN_FLIGHT, buffer.status());
        assertThrows(IllegalStateException.class, buffer::take);
        assertThrows(IllegalStateException.class, buffer::close);
        assertThrows(IllegalStateException.class,
                () -> buffer.complete(null, true));
        buffer.complete(command, true);
        assertThrows(IllegalStateException.class, () -> buffer.complete(command, true));
        var owner = buffer.take();
        assertThrows(IllegalStateException.class, buffer::status);
        buffer.close(); // A moved-from wrapper has relinquished ownership.
        assertEquals(1024, owner.byteLength());
        var next = owner.beginCommand(id);
        assertThrows(IllegalStateException.class, () -> owner.complete(command, true));
        assertNotEquals(command.sequence(), next.sequence());
        owner.cancelPending(next);
        assertEquals(GpuBufferFence.Status.POISONED, owner.status());
        assertThrows(IllegalStateException.class, owner::take);
        assertThrows(IllegalStateException.class, () -> owner.retireAfterBackendRelease(id));
        owner.acknowledgeCancelledDeviceEvent(next, id);
        assertThrows(IllegalStateException.class, () -> owner.acknowledgeCancelledDeviceEvent(next, id));
        owner.retireAfterBackendRelease(id);
        assertDoesNotThrow(owner::close);
        assertThrows(IllegalStateException.class, () -> owner.beginCommand(id));
    }

    @Test void errorsRemainPoisonedUntilBackendReleasesHandle() {
        var id = new GpuBufferFence.Identity("tenant", "device", 1);
        var fence = new GpuBufferFence(id, 4);
        var ticket = fence.beginCommand(id);
        fence.complete(ticket, false);
        assertEquals(GpuBufferFence.Status.POISONED, fence.status());
        assertThrows(IllegalStateException.class, () -> fence.beginCommand(id));
        fence.retireAfterBackendRelease(id);
        fence.close();
        assertThrows(IllegalArgumentException.class, () -> new GpuBufferFence(id, 0));
    }
}
