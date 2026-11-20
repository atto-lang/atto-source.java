package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class DopRegionScheduleRandomizedTest {
    @Test
    void seededCellOracleMatchesReadWriteConflictModel() {
        var rng = new Random(0x0D0C);
        var modes = DopRegionSchedule.Mode.values();
        var root = DopRegionSchedule.Root.trustedCreate(32, 4);
        for (int i = 0; i < 100_000; i++) {
            int fieldA = rng.nextInt(4), fieldB = rng.nextInt(4);
            int beginA = rng.nextInt(33), endA = beginA + rng.nextInt(33 - beginA);
            int beginB = rng.nextInt(33), endB = beginB + rng.nextInt(33 - beginB);
            var modeA = modes[rng.nextInt(modes.length)];
            var modeB = modes[rng.nextInt(modes.length)];
            var a = new DopRegionSchedule.Access(root, fieldA, beginA, endA,
                    modeA, modeA == DopRegionSchedule.Mode.REDUCE ? "sum" : null);
            var b = new DopRegionSchedule.Access(root, fieldB, beginB, endB,
                    modeB, modeB == DopRegionSchedule.Mode.REDUCE ? "sum" : null);
            boolean overlaps = false;
            for (int row = 0; row < 32; row++) {
                if (fieldA == fieldB && beginA <= row && row < endA
                        && beginB <= row && row < endB) overlaps = true;
            }
            boolean expected = !overlaps || (modeA == DopRegionSchedule.Mode.READ
                    && modeB == DopRegionSchedule.Mode.READ);
            assertEquals(expected, DopRegionSchedule.independent(a, b), "iteration " + i);
            assertEquals(expected, DopRegionSchedule.independent(b, a), "symmetric " + i);
        }
    }

    @Test
    void seededDependencyGraphsNeverCoScheduleConflictingTasks() {
        var rng = new Random(0xBADC0DE);
        var modes = DopRegionSchedule.Mode.values();
        var root = DopRegionSchedule.Root.trustedCreate(32, 4);
        for (int trial = 0; trial < 1_000; trial++) {
            var tasks = new ArrayList<DopRegionSchedule.Task>();
            for (int i = 0; i < 12; i++) {
                int field = rng.nextInt(4);
                int begin = rng.nextInt(33);
                int end = begin + rng.nextInt(33 - begin);
                var mode = modes[rng.nextInt(modes.length)];
                var footprint = new DopRegionSchedule.Access(root, field, begin, end,
                        mode, mode == DopRegionSchedule.Mode.REDUCE ? "sum" : null);
                tasks.add(new DopRegionSchedule.Task("task" + i, List.of(footprint)));
            }
            var levels = DopRegionSchedule.dependencyLevels(tasks);
            int[] levelOf = new int[tasks.size()];
            int scheduled = 0;
            for (int level = 0; level < levels.size(); level++) {
                for (int task : levels.get(level)) {
                    assertTrue(task >= 0 && task < tasks.size());
                    levelOf[task] = level;
                    scheduled++;
                }
            }
            assertEquals(tasks.size(), scheduled);
            for (int i = 0; i < tasks.size(); i++) {
                for (int j = 0; j < i; j++) {
                    if (!DopRegionSchedule.independent(tasks.get(j), tasks.get(i))) {
                        assertTrue(levelOf[j] < levelOf[i],
                                "conflicting tasks reordered/co-scheduled in trial " + trial);
                    }
                }
            }
        }
    }
}
