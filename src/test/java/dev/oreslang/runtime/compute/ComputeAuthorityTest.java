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
                ComputeQueueHandle.class,
                ComputeBufferHandle.class,
                ComputeFenceHandle.class,
                ComputeFencePair.class
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

        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.complete(buffer, upload, true));
        ComputeAuthority.markSubmitted(buffer, upload);
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.markSubmitted(buffer, upload));
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.abortBeforeEnqueue(buffer, upload));
        ComputeAuthority.complete(buffer, upload, true);
        assertEquals(ComputeBufferHandle.State.IDLE, buffer.state());

        var download = ComputeAuthority.beginDownload(buffer, gpu, "actor:42", 16, 32);
        assertEquals(ComputeFenceHandle.Direction.DOWNLOAD, download.direction());
        ComputeAuthority.markSubmitted(buffer, download);
        ComputeAuthority.complete(buffer, download, true);
    }

    @Test
    void staleFenceCannotCompleteNewTransfer() {
        var gpu = device("gpu0", 2);
        var buffer = ComputeAuthority.<Byte>bindBuffer(
                gpu, 3, 128, 8, "u8", "actor:1");

        var first = ComputeAuthority.beginUpload(buffer, gpu, "actor:1", 0, 16);
        ComputeAuthority.markSubmitted(buffer, first);
        ComputeAuthority.complete(buffer, first, true);
        var second = ComputeAuthority.beginUpload(buffer, gpu, "actor:1", 16, 16);
        ComputeAuthority.markSubmitted(buffer, second);

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
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.cancel(buffer, fence));
        ComputeAuthority.markSubmitted(buffer, fence);
        ComputeAuthority.cancel(buffer, fence);
        assertEquals(ComputeBufferHandle.State.POISONED, buffer.state());
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.retire(buffer, gpu, "actor:1"));

        ComputeAuthority.acknowledgeCancelled(buffer, fence);
        ComputeAuthority.retire(buffer, gpu, "actor:1");
        assertEquals(ComputeBufferHandle.State.RETIRED, buffer.state());
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.retire(buffer, gpu, "actor:1"));
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
                () -> ComputeAuthority.markSubmitted(buffer, forged));
        ComputeAuthority.markSubmitted(buffer, real);
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.complete(buffer, forged, true));
        ComputeAuthority.complete(buffer, real, true);
    }


    @Test
    void preEnqueueAbortRequiresExactReservedFenceAndRestoresIdle() {
        var gpu = device("gpu0", 4);
        var buffer = ComputeAuthority.<Byte>bindBuffer(
                gpu, 8, 128, 8, "u8", "actor:1");

        var reserved = ComputeAuthority.beginUpload(buffer, gpu, "actor:1", 8, 16);
        assertEquals(ComputeBufferHandle.State.IN_FLIGHT, buffer.state());

        var forged = new ComputeFenceHandle(
                gpu,
                reserved.allocationGeneration(),
                reserved.sequence(),
                reserved.direction(),
                reserved.offset(),
                reserved.length(),
                new Object());
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.abortBeforeEnqueue(buffer, forged));

        ComputeAuthority.abortBeforeEnqueue(buffer, reserved);
        assertEquals(ComputeBufferHandle.State.IDLE, buffer.state());

        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.abortBeforeEnqueue(buffer, reserved));
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.complete(buffer, reserved, true));

        var next = ComputeAuthority.beginUpload(buffer, gpu, "actor:1", 16, 16);
        assertNotEquals(reserved.sequence(), next.sequence());
        ComputeAuthority.markSubmitted(buffer, next);
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.abortBeforeEnqueue(buffer, next));
        ComputeAuthority.complete(buffer, next, true);
    }

    @Test
    void atomicKernelPairCannotHalfClaimMixOrBypassPairTransitions() {
        var gpu = device("gpu0", 8);
        var input = ComputeAuthority.<Byte>bindBuffer(
                gpu, 31, 128, 16, "u8", "actor:7");
        var output = ComputeAuthority.<Byte>bindBuffer(
                gpu, 32, 128, 16, "u8", "actor:7");

        var pair = ComputeAuthority.beginKernelPair(
                input, output, gpu, "actor:7", 128);
        assertEquals(ComputeBufferHandle.State.IN_FLIGHT, input.state());
        assertEquals(ComputeBufferHandle.State.IN_FLIGHT, output.state());

        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.markSubmitted(input, pair.input()));
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.complete(input, pair.input(), true));
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.completePair(input, output, pair, true));

        ComputeAuthority.abortPairBeforeEnqueue(input, output, pair);
        assertEquals(ComputeBufferHandle.State.IDLE, input.state());
        assertEquals(ComputeBufferHandle.State.IDLE, output.state());

        var pair2 = ComputeAuthority.beginKernelPair(
                input, output, gpu, "actor:7", 128);
        ComputeAuthority.markPairSubmitted(input, output, pair2);
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.abortPairBeforeEnqueue(input, output, pair2));
        ComputeAuthority.completePair(input, output, pair2, true);
        assertEquals(ComputeBufferHandle.State.IDLE, input.state());
        assertEquals(ComputeBufferHandle.State.IDLE, output.state());

        var pair3 = ComputeAuthority.beginKernelPair(
                input, output, gpu, "actor:7", 128);
        ComputeAuthority.markPairSubmitted(input, output, pair3);
        ComputeAuthority.cancelPair(input, output, pair3);
        assertEquals(ComputeBufferHandle.State.POISONED, input.state());
        assertEquals(ComputeBufferHandle.State.POISONED, output.state());
        assertThrows(IllegalStateException.class,
                () -> ComputeAuthority.retire(input, gpu, "actor:7"));
        ComputeAuthority.acknowledgeCancelledPair(input, output, pair3);
        ComputeAuthority.retire(input, gpu, "actor:7");
        ComputeAuthority.retire(output, gpu, "actor:7");
    }

    @Test
    void kernelPairAdmissionIsFailureAtomic() {
        var gpu = device("gpu0", 9);
        var foreign = device("gpu0", 9);
        var input = ComputeAuthority.<Byte>bindBuffer(
                gpu, 41, 64, 8, "u8", "actor:1");
        var output = ComputeAuthority.<Byte>bindBuffer(
                gpu, 42, 32, 8, "u8", "actor:1");

        assertThrows(IllegalArgumentException.class,
                () -> ComputeAuthority.beginKernelPair(
                        input, output, gpu, "actor:1", 64));
        assertEquals(ComputeBufferHandle.State.IDLE, input.state());
        assertEquals(ComputeBufferHandle.State.IDLE, output.state());

        var exactOutput = ComputeAuthority.<Byte>bindBuffer(
                gpu, 43, 64, 8, "u8", "actor:1");
        assertThrows(IllegalArgumentException.class,
                () -> ComputeAuthority.beginKernelPair(
                        input, exactOutput, foreign, "actor:1", 64));
        assertEquals(ComputeBufferHandle.State.IDLE, input.state());
        assertEquals(ComputeBufferHandle.State.IDLE, exactOutput.state());
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
