package dev.oreslang;

import dev.oreslang.compiler.GpuLaunchFootprints;
import dev.oreslang.compiler.GpuLaunchFootprints.Access;
import dev.oreslang.compiler.GpuLaunchFootprints.Limits;
import dev.oreslang.compiler.GpuLaunchFootprints.QueueScope;
import dev.oreslang.compiler.GpuLaunchFootprints.View;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only GPU loan preflight tests. Passing these tests must never be
 * counted as compiler-backed AttoLang execution or a physical GPU receipt.
 */
final class GpuLaunchFootprintsTest {
    private static final QueueScope QUEUE =
            new QueueScope("tenant-a", "device-a", "queue-1", 9L);
    private static final Limits LIMITS = new Limits(256, 4096);

    private static View view(String allocation, long generation,
                             long offset, long length, Access mode) {
        return new View("tenant-a", "device-a", allocation, generation,
                1024, offset, length, 4, mode);
    }

    @Test
    void disjointMutableSlicesAndOverlappingReadOnlyViewsAreAccepted() {
        assertDoesNotThrow(() -> GpuLaunchFootprints.validate(QUEUE, List.of(
                view("same", 7, 0, 128, Access.READ),
                view("same", 7, 64, 128, Access.READ),
                view("same", 7, 192, 64, Access.WRITE),
                view("same", 7, 256, 64, Access.READ_WRITE),
                view("different", 1, 0, 256, Access.WRITE)), LIMITS));
    }

    @Test
    void readWriteAndWriteWriteOverlapsAreRejectedRegardlessOfOrder() {
        for (List<View> views : List.of(
                List.of(view("a", 1, 0, 128, Access.READ),
                        view("a", 1, 64, 128, Access.WRITE)),
                List.of(view("a", 1, 0, 128, Access.WRITE),
                        view("a", 1, 64, 128, Access.READ)),
                List.of(view("a", 1, 0, 128, Access.WRITE),
                        view("a", 1, 64, 128, Access.READ_WRITE)),
                List.of(view("a", 1, 64, 128, Access.READ),
                        view("a", 1, 0, 128, Access.READ_WRITE)))) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> GpuLaunchFootprints.validate(QUEUE, views, LIMITS));
            assertTrue(ex.getMessage().contains("GPU-LAUNCH-005"));
        }
    }

    @Test
    void staleGenerationAndContradictoryAllocationSizeFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                GpuLaunchFootprints.validate(QUEUE, List.of(
                        view("a", 3, 0, 16, Access.READ),
                        view("a", 4, 16, 16, Access.WRITE)), LIMITS));
        View contradictory = new View("tenant-a", "device-a", "a", 3,
                2048, 16, 16, 4, Access.READ);
        assertThrows(IllegalArgumentException.class, () ->
                GpuLaunchFootprints.validate(QUEUE, List.of(
                        view("a", 3, 0, 16, Access.READ), contradictory), LIMITS));
    }

    @Test
    void foreignTenantOrDeviceCannotEnterAQueue() {
        for (View foreign : List.of(
                new View("tenant-b", "device-a", "a", 1, 1024, 0, 16, 4, Access.READ),
                new View("tenant-a", "device-b", "a", 1, 1024, 0, 16, 4, Access.READ))) {
            SecurityException ex = assertThrows(SecurityException.class,
                    () -> GpuLaunchFootprints.validate(
                            QUEUE, List.of(foreign), LIMITS));
            assertTrue(ex.getMessage().contains("GPU-LAUNCH-004"));
        }
    }

    @Test
    void malformedIdentitiesRangesAndAlignmentsRejectBeforeEnqueue() {
        assertThrows(IllegalArgumentException.class,
                () -> new QueueScope("tenant-a", "device-a", "queue", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new QueueScope(" ", "device-a", "queue", 1));
        assertThrows(IllegalArgumentException.class,
                () -> view("a", 0, 0, 16, Access.WRITE));
        assertThrows(IllegalArgumentException.class,
                () -> view("a", 1, 1020, 8, Access.READ));
        assertThrows(IllegalArgumentException.class,
                () -> view("a", 1, 1, 4, Access.READ));
        assertThrows(IllegalArgumentException.class,
                () -> view("a", 1, 0, 6, Access.READ));
        assertThrows(IllegalArgumentException.class, () -> new View(
                "tenant-a", "device-a", "a", 1,
                Long.MAX_VALUE, Long.MAX_VALUE - 3, 8, 1, Access.READ));
        assertThrows(IllegalArgumentException.class, () -> new View(
                "tenant-a", "device-a", "a", 1, 1024, 0, 16, 3, Access.READ));
    }

    @Test
    void explicitArgumentAndByteBudgetsAreEnforced() {
        List<View> many = new ArrayList<>();
        for (int i = 0; i < 257; i++) {
            many.add(view("read-" + i, 1, 0, 16, Access.READ));
        }
        assertThrows(IllegalArgumentException.class,
                () -> GpuLaunchFootprints.validate(QUEUE, many, LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> GpuLaunchFootprints.validate(
                        QUEUE, List.of(view("a", 1, 0, 1024, Access.READ)),
                        new Limits(256, 1023)));
        assertThrows(IllegalArgumentException.class,
                () -> new Limits(0, 1));
    }

    @Test
    void largeReadOnlyAliasSetsDoNotMaskLaterWriter() {
        List<View> ranges = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ranges.add(view("a", 1, 0, 128, Access.READ));
        }
        assertDoesNotThrow(() -> GpuLaunchFootprints.validate(
                QUEUE, ranges, new Limits(128, 12800)));
        ranges.add(view("a", 1, 124, 4, Access.WRITE));
        assertThrows(IllegalArgumentException.class,
                () -> GpuLaunchFootprints.validate(
                        QUEUE, ranges, new Limits(128, 12804)));
    }
}
