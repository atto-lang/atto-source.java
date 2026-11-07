package dev.oreslang;

import dev.oreslang.compiler.GpuLaunchFootprints;
import dev.oreslang.compiler.GpuLaunchFootprints.Access;
import dev.oreslang.compiler.GpuLaunchFootprints.Limits;
import dev.oreslang.compiler.GpuLaunchFootprints.QueueScope;
import dev.oreslang.compiler.GpuLaunchFootprints.View;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential host-only regression: compare sorted O(n log n) launch-footprint
 * validation to an independent O(n^2) overlap oracle on fixed-seed inputs.
 * This does not prove physical buffer identity or GPU kernel execution.
 */
final class GpuLaunchFootprintsPropertyTest {
    private static final QueueScope QUEUE =
            new QueueScope("tenant", "gpu-0", "queue", 1);
    private static final Limits BUDGET = new Limits(256, 256 * 1024);

    private static boolean referenceConflict(List<View> views) {
        for (int i = 0; i < views.size(); i++) {
            View a = views.get(i);
            for (int j = i + 1; j < views.size(); j++) {
                View b = views.get(j);
                if (!a.allocationId().equals(b.allocationId())) continue;
                if (!a.writes() && !b.writes()) continue;
                if (a.byteOffset() < b.endExclusive()
                        && b.byteOffset() < a.endExclusive()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void fixedSeedRandomizedIntervalsAgreeWithIndependentQuadraticOracle() {
        SplittableRandom rng = new SplittableRandom(0xA770C0DEL);
        for (int trial = 0; trial < 2048; trial++) {
            int count = 1 + rng.nextInt(48);
            List<View> views = new ArrayList<>(count);
            for (int j = 0; j < count; j++) {
                String allocation = "allocation-" + rng.nextInt(4);
                long start = 4L * rng.nextInt(232);
                long length = 4L * (1 + rng.nextInt(16));
                Access mode = Access.values()[rng.nextInt(Access.values().length)];
                views.add(new View("tenant", "gpu-0", allocation, 17L,
                        1024L, start, length, 4L, mode));
            }
            boolean expectedConflict = referenceConflict(views);
            try {
                GpuLaunchFootprints.validate(QUEUE, views, BUDGET);
                assertFalse(expectedConflict,
                        "unexpected acceptance on trial " + trial);
            } catch (IllegalArgumentException reject) {
                assertTrue(expectedConflict,
                        "unexpected rejection on trial " + trial + ": " + reject);
                assertTrue(reject.getMessage().contains("GPU-LAUNCH-005"));
            }
        }
    }

    @Test
    void adjacentWritesDoNotBecomeFalseConflictsAtMaximumViewBudget() {
        List<View> views = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            views.add(new View("tenant", "gpu-0", "one", 1, 1024,
                    i * 4L, 4L, 4L, Access.WRITE));
        }
        assertFalse(referenceConflict(views));
        assertDoesNotThrow(() -> GpuLaunchFootprints.validate(
                QUEUE, views, BUDGET));
        views.add(new View("tenant", "gpu-0", "one", 1, 1024,
                0, 4, 4, Access.READ));
        assertThrows(IllegalArgumentException.class,
                () -> GpuLaunchFootprints.validate(QUEUE, views, BUDGET),
                "oversized argument list must fail even if a later alias is also invalid");
    }
}
