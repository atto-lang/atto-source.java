package dev.oreslang.runtime.compute;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

final class ComputeAuthorityTest {
    private static ComputeDeviceHandle device(String id, long generation) {
        return ComputeAuthority.issueDevice("vulkan", id, generation, "tenant-a");
    }

    @Test
    void handlesExposeNoPublicConstructors() {
        for (Class<?> type : new Class<?>[] {
                ComputeDeviceHandle.class,
                ComputeBufferHandle.class,
                ComputeFenceHandle.class
        }) {
            for (var constructor : type.getDeclaredConstructors()) {
                assertFalse(Modifier.isPublic(constructor.getModifiers()),
                        type.getSimpleName() + " must not expose a public constructor");
            }
        }
        assertFalse(Modifier.isPublic(ComputeAuthority.class.getModifiers()));
    }

    @Test
    void explicitUploadAndDownloadRequireExactAuthorityDomainAndBounds() {
        var gpu = device("gpu0", 7);
        var foreign = device("gpu0", 7);
        var buffer = ComputeAuthority.<Float>bindBuffer(
                gpu, 11, 4096, 16, "f32", "actor:42");

        assertThrows(IllegalArgumentException.class,
                () -> ComputeAuthority.beginUpload(buffer, foreign, "actor:42", 0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> ComputeAuthority.beginUpload(buffer, gpu, "actor:99", 0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> ComputeAuthority.beginUpload(buffer, gpu, "actor:42", -1, 4));
        assertThrows(IllegalArgumentException.class,
                () -> ComputeAuthority.beginUpload(buffer, gpu, "actor:42", 4095, 2));
        assertThrows(IllegalArgumentException.class,
                () -> ComputeAuthority.beginUpload(buffer, gpu, "actor:42", 0, 0));

        var upload = ComputeAuthority.beginUpload(buffer, gpu, "actor:42", 0, 4096);
        assertEquals(ComputeFenceHandle.Direction.UPLOAD, upload.direction());
        assertEquals(ComputeBufferHandle.State.IN_FLIGHT, buffer.state());
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.beginDownload(buffer, gpu, "actor:42", 0, 4));

        ComputeAuthority.complete(buffer, upload, true);
        assertEquals(ComputeBufferHandle.State.IDLE, buffer.state());

        var download = ComputeAuthority.beginDownload(buffer, gpu, "actor:42", 16, 32);
        assertEquals(ComputeFenceHandle.Direction.DOWNLOAD, download.direction());
        ComputeAuthority.complete(buffer, download, true);
    }

    @Test
    void staleFenceCannotCompleteNewTransfer() {
        var gpu = device("gpu0", 2);
        var buffer = ComputeAuthority.<Byte>bindBuffer(
                gpu, 3, 128, 8, "u8", "actor:1");

        var first = ComputeAuthority.beginUpload(buffer, gpu, "actor:1", 0, 16);
        ComputeAuthority.complete(buffer, first, true);
        var second = ComputeAuthority.beginUpload(buffer, gpu, "actor:1", 16, 16);

        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.complete(buffer, first, true));
        assertEquals(ComputeBufferHandle.State.IN_FLIGHT, buffer.state());
        ComputeAuthority.complete(buffer, second, true);
    }

    @Test
    void cancellationNeverImpliesQuiescenceOrReclamation() {
        var gpu = device("gpu0", 5);
        var buffer = ComputeAuthority.<Byte>bindBuffer(
                gpu, 9, 256, 16, "u8", "actor:1");

        var fence = ComputeAuthority.beginDownload(buffer, gpu, "actor:1", 0, 64);
        ComputeAuthority.cancel(buffer, fence);
        assertEquals(ComputeBufferHandle.State.POISONED, buffer.state());
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.retire(buffer, gpu, "actor:1"));

        ComputeAuthority.acknowledgeCancelled(buffer, fence);
        ComputeAuthority.retire(buffer, gpu, "actor:1");
        assertEquals(ComputeBufferHandle.State.RETIRED, buffer.state());
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.beginUpload(buffer, gpu, "actor:1", 0, 1));
    }

    @Test
    void staleOrForgedReceiptFailsClosed() {
        var gpu = device("gpu0", 3);
        var buffer = ComputeAuthority.<Byte>bindBuffer(
                gpu, 4, 64, 8, "u8", "actor:1");
        var real = ComputeAuthority.beginUpload(buffer, gpu, "actor:1", 0, 8);

        var forged = new ComputeFenceHandle(
                gpu,
                real.allocationGeneration(),
                real.sequence(),
                real.direction(),
                real.offset(),
                real.length(),
                new Object());

        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.complete(buffer, forged, true));
        ComputeAuthority.complete(buffer, real, true);
    }

    @Test
    void generationsAlignmentAndSequenceFailClosed() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> ComputeAuthority.issueDevice("vulkan", "gpu0", 0, "tenant-a"));

        var gpu = device("gpu0", 1);
        assertThrows(IllegalArgumentException.class,
                () -> ComputeAuthority.bindBuffer(gpu, 0, 64, 8, "u8", "actor:1"));
        assertThrows(IllegalArgumentException.class,
                () -> ComputeAuthority.bindBuffer(gpu, 1, 0, 8, "u8", "actor:1"));
        assertThrows(IllegalArgumentException.class,
                () -> ComputeAuthority.bindBuffer(gpu, 1, 64, 3, "u8", "actor:1"));

        var buffer = ComputeAuthority.<Byte>bindBuffer(
                gpu, 1, 64, 8, "u8", "actor:1");
        var sequence = ComputeBufferHandle.class.getDeclaredField("sequence");
        sequence.setAccessible(true);
        sequence.setLong(buffer, Long.MAX_VALUE);
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.beginUpload(buffer, gpu, "actor:1", 0, 1));
        assertEquals(ComputeBufferHandle.State.IDLE, buffer.state());
    }
}
