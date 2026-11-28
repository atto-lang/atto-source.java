package dev.oreslang.runtime.accel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Device generation and event-ticket regressions; host-only, never a GPU dispatch test. */
final class GpuBufferFenceGenerationTest {
    private static GpuBufferFence.Identity identity(long generation) {
        return new GpuBufferFence.Identity("tenant-a", "device-0", generation);
    }

    @Test
    void allocationGenerationMustBePositive() {
        for (long invalid : new long[] {Long.MIN_VALUE, -1L, 0L}) {
            assertThrows(IllegalArgumentException.class, () -> identity(invalid));
        }
        assertEquals(1L, identity(1L).allocationGeneration());
        assertEquals(Long.MAX_VALUE, identity(Long.MAX_VALUE).allocationGeneration());
    }

    @Test
    void foreignOrMismatchedGenerationNeverOpensACommand() {
        var owner = new GpuBufferFence(identity(7L), 256L);
        for (var caller : new GpuBufferFence.Identity[] {
                new GpuBufferFence.Identity("tenant-b", "device-0", 7L),
                new GpuBufferFence.Identity("tenant-a", "device-1", 7L),
                identity(8L)
        }) {
            assertThrows(SecurityException.class, () -> owner.beginCommand(caller));
            assertEquals(GpuBufferFence.Status.IDLE, owner.status());
        }
        owner.retireAfterBackendRelease(identity(7L));
        owner.close();
    }

    @Test
    void staleCompletionCannotRetireNewSubmission() {
        var owner = new GpuBufferFence(identity(3L), 64L);
        var first = owner.beginCommand(identity(3L));
        assertEquals(3L, first.generation());
        assertEquals(1L, first.sequence());
        owner.complete(first, true);
        var second = owner.beginCommand(identity(3L));
        assertEquals(2L, second.sequence());
        assertThrows(IllegalStateException.class, () -> owner.complete(first, true));
        assertEquals(GpuBufferFence.Status.IN_FLIGHT, owner.status());
        owner.complete(second, true);
        owner.retireAfterBackendRelease(identity(3L));
        owner.close();
    }

    @Test
    void cancellationRequiresActualDeviceEventBeforeRetirement() {
        var owner = new GpuBufferFence(identity(11L), 128L);
        var ticket = owner.beginCommand(identity(11L));
        owner.cancelPending(ticket);
        assertEquals(GpuBufferFence.Status.POISONED, owner.status());
        assertThrows(IllegalStateException.class, owner::take);
        assertThrows(IllegalStateException.class,
                () -> owner.retireAfterBackendRelease(identity(11L)));
        owner.acknowledgeCancelledDeviceEvent(ticket, identity(11L));
        assertThrows(IllegalStateException.class,
                () -> owner.acknowledgeCancelledDeviceEvent(ticket, identity(11L)));
        assertEquals(GpuBufferFence.Status.POISONED, owner.status());
        owner.retireAfterBackendRelease(identity(11L));
        owner.close();
    }
}
