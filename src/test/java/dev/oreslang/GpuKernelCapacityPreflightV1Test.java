package dev.oreslang;

import dev.oreslang.compiler.GpuKernelCapacityPreflightV1;
import dev.oreslang.compiler.GpuKernelCapacityPreflightV1.Argument;
import dev.oreslang.compiler.GpuLaunchFootprints;
import dev.oreslang.compiler.GpuLaunchFootprints.Access;
import dev.oreslang.compiler.GpuLaunchFootprints.Limits;
import dev.oreslang.compiler.GpuLaunchFootprints.QueueScope;
import dev.oreslang.compiler.GpuLaunchFootprints.View;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only source capacity preflight, never proof of a provider-issued loan. */
final class GpuKernelCapacityPreflightV1Test {
    private static final QueueScope QUEUE = new QueueScope("tenant-a", "gpu-0", "stream", 7);
    private static final Limits LIMITS = new Limits(16, 1_048_576);

    private static Argument view(String name, String allocation,
                                 long size, Access expected, Access actual) {
        return new Argument(name, expected, new View(
                "tenant-a", "gpu-0", allocation, 9, size, 0, size, 4, actual));
    }

    private static List<Argument> saxpy() {
        return List.of(
                view("x", "x", 1024, Access.READ, Access.READ),
                view("y", "y", 1024, Access.READ, Access.READ),
                view("out", "out", 1024, Access.WRITE, Access.WRITE));
    }

    @Test void matchingNAndViewsYieldNonExecutableCapacityShape() {
        var proof = GpuKernelCapacityPreflightV1.validateF32(
                QUEUE, 256, 256, saxpy(), LIMITS);
        assertEquals(256, proof.logicalItems());
        assertEquals(1024, proof.requiredBytesPerView());
        assertEquals(3, proof.checkedViews());
        assertEquals(GpuKernelCapacityPreflightV1.Evidence.SNAPSHOT_CAPACITY_SHAPE_ONLY_NOT_EXECUTABLE,
                proof.evidence());
        assertFalse(proof.executable());
        assertTrue(proof.physicalDispatchRequired());
    }

    @Test void zeroExtentIsQuiescentButStillNotAnExecutionPermit() {
        var proof = GpuKernelCapacityPreflightV1.validateF32(
                QUEUE, 0, 0, saxpy(), LIMITS);
        assertFalse(proof.physicalDispatchRequired());
        assertFalse(proof.executable());
        assertEquals(0, proof.requiredBytesPerView());
    }

    @Test void logicalExtentMustMatchKernelBound() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelCapacityPreflightV1.validateF32(
                        QUEUE, 256, 257, saxpy(), LIMITS));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-005"));
    }

    @Test void truncatedInputAndOutputViewsRejectBeforeProvider() {
        for (String tooSmall : List.of("x", "y", "out")) {
            List<Argument> args = saxpy().stream().map(a ->
                    a.parameter().equals(tooSmall)
                            ? view(a.parameter(), a.parameter(), 1020,
                                   a.expectedAccess(), a.view().access())
                            : a).toList();
            var error = assertThrows(IllegalArgumentException.class,
                    () -> GpuKernelCapacityPreflightV1.validateF32(
                            QUEUE, 256, 256, args, LIMITS));
            assertTrue(error.getMessage().contains("GPU-LAUNCH-005"));
        }
    }

    @Test void largeElementCountCannotOverflowByteRequirement() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelCapacityPreflightV1.validateF32(
                        QUEUE, Long.MAX_VALUE, Long.MAX_VALUE, saxpy(), LIMITS));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-005"));
    }

    @Test void borrowedReadWriteCapabilityMustMatchKernelParameter() {
        List<Argument> wrongRead = List.of(view("x", "x", 1024, Access.READ, Access.WRITE));
        List<Argument> wrongWrite = List.of(view("out", "out", 1024, Access.WRITE, Access.READ));
        List<Argument> wrongReadWrite = List.of(
                view("state", "state", 1024, Access.READ_WRITE, Access.READ));
        for (List<Argument> args : List.of(wrongRead, wrongWrite, wrongReadWrite)) {
            assertThrows(IllegalArgumentException.class,
                    () -> GpuKernelCapacityPreflightV1.validateF32(
                            QUEUE, 256, 256, args, LIMITS));
        }
    }

    @Test void writableOverlapsAndForeignTenantStayRejected() {
        View overlap = new View("tenant-a", "gpu-0", "x", 9, 1024,
                0, 1024, 4, Access.WRITE);
        List<Argument> aliased = List.of(saxpy().getFirst(),
                new Argument("out", Access.WRITE, overlap));
        assertThrows(IllegalArgumentException.class,
                () -> GpuKernelCapacityPreflightV1.validateF32(
                        QUEUE, 256, 256, aliased, LIMITS));
        View foreign = new View("tenant-b", "gpu-0", "out", 9, 1024,
                0, 1024, 4, Access.WRITE);
        assertThrows(SecurityException.class,
                () -> GpuKernelCapacityPreflightV1.validateF32(
                        QUEUE, 256, 256,
                        List.of(new Argument("out", Access.WRITE, foreign)), LIMITS));
    }

    @Test void duplicateBindingNamesRejectWithoutAliasingPermissions() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelCapacityPreflightV1.validateF32(
                        QUEUE, 256, 256,
                        List.of(view("out", "a", 1024, Access.WRITE, Access.WRITE),
                                view("out", "b", 1024, Access.WRITE, Access.WRITE)),
                        LIMITS));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-005"));
    }

    @Test void overAlignmentAndMissingViewsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> GpuKernelCapacityPreflightV1.validateF32(
                        QUEUE, -1, -1, saxpy(), LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> GpuKernelCapacityPreflightV1.validateF32(
                        QUEUE, 0, 0, List.of(), LIMITS));
        View byteAlignedOnly = new View("tenant-a", "gpu-0", "a", 9,
                1024, 0, 1024, 1, Access.READ);
        assertThrows(IllegalArgumentException.class,
                () -> GpuKernelCapacityPreflightV1.validateF32(
                        QUEUE, 256, 256, List.of(
                                new Argument("x", Access.READ, byteAlignedOnly)), LIMITS));
    }
}
