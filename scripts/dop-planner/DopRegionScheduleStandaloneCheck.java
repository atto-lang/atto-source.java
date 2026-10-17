package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Dependency-free, exact-production-source smoke check; not full compiler CI. */
public final class DopRegionScheduleStandaloneCheck {
    private static long checks;

    private DopRegionScheduleStandaloneCheck() { }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void reject(Class<? extends Throwable> expected, Runnable operation) {
        checks++;
        try {
            operation.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) return;
            throw new AssertionError("wrong exception: " + thrown, thrown);
        }
        throw new AssertionError("did not reject " + expected.getSimpleName());
    }

    private static DopRegionSchedule.Access access(DopRegionSchedule.Root root,
                                                    int field, long begin, long end,
                                                    DopRegionSchedule.Mode mode) {
        return new DopRegionSchedule.Access(root, field, begin, end, mode,
                mode == DopRegionSchedule.Mode.REDUCE ? "sum" : null);
    }

    private static void boundsAndImmutability() {
        var root = DopRegionSchedule.Root.trustedCreate(Long.MAX_VALUE, 3);
        var empty = DopRegionSchedule.Root.trustedCreate(0, 1);
        check(access(empty, 0, 0, 0, DopRegionSchedule.Mode.WRITE).begin() == 0,
                "zero-row legal empty footprint");
        reject(IllegalArgumentException.class,
                () -> DopRegionSchedule.Access.rows(empty, 0, 0, 1, DopRegionSchedule.Mode.WRITE));
        reject(IllegalArgumentException.class,
                () -> DopRegionSchedule.Access.rows(root, 0, 1, Long.MAX_VALUE, DopRegionSchedule.Mode.READ));
        var all = DopRegionSchedule.Access.rows(root, 2, 0, Long.MAX_VALUE,
                DopRegionSchedule.Mode.REDUCE, "sum");
        check(all.end() == Long.MAX_VALUE, "valid maximum end");
        reject(IllegalArgumentException.class,
                () -> DopRegionSchedule.Root.trustedCreate(-1, 1));
        reject(IllegalArgumentException.class,
                () -> access(root, 3, 0, 1, DopRegionSchedule.Mode.READ));
        reject(IllegalArgumentException.class,
                () -> access(root, 0, 5, 4, DopRegionSchedule.Mode.READ));
        reject(IllegalArgumentException.class,
                () -> new DopRegionSchedule.Access(root, 0, 0, 1,
                        DopRegionSchedule.Mode.REDUCE, " sum"));
        reject(IllegalArgumentException.class,
                () -> new DopRegionSchedule.Access(root, 0, 0, 1,
                        DopRegionSchedule.Mode.READ, "sum"));
        reject(NullPointerException.class,
                () -> new DopRegionSchedule.Task("null-access", Arrays.asList((DopRegionSchedule.Access) null)));
        var mutable = new ArrayList<DopRegionSchedule.Access>();
        mutable.add(all);
        var task = new DopRegionSchedule.Task("stable", mutable);
        mutable.clear();
        check(task.accesses().size() == 1, "task must snapshot caller list");
        reject(UnsupportedOperationException.class, () -> task.accesses().clear());
        var levels = DopRegionSchedule.dependencyLevels(List.of(task));
        reject(UnsupportedOperationException.class, () -> levels.get(0).clear());
        reject(UnsupportedOperationException.class, levels::clear);
    }

    private static void checkOracle() {
        var rng = new Random(0xD0F2026L);
        var modes = DopRegionSchedule.Mode.values();
        var root = DopRegionSchedule.Root.trustedCreate(32, 4);
        var other = DopRegionSchedule.Root.trustedCreate(32, 4);
        for (int i = 0; i < 100_000; i++) {
            int fieldA = rng.nextInt(4), fieldB = rng.nextInt(4);
            int startA = rng.nextInt(33), startB = rng.nextInt(33);
            int endA = startA + rng.nextInt(33 - startA);
            int endB = startB + rng.nextInt(33 - startB);
            var modeA = modes[rng.nextInt(modes.length)];
            var modeB = modes[rng.nextInt(modes.length)];
            var a = access(root, fieldA, startA, endA, modeA);
            var b = access(root, fieldB, startB, endB, modeB);
            boolean overlap = false;
            for (int row = 0; row < 32; row++) {
                if (fieldA == fieldB && row >= startA && row < endA
                        && row >= startB && row < endB) overlap = true;
            }
            boolean expected = !overlap || (modeA == DopRegionSchedule.Mode.READ
                    && modeB == DopRegionSchedule.Mode.READ);
            check(DopRegionSchedule.independent(a, b) == expected, "oracle forward " + i);
            check(DopRegionSchedule.independent(b, a) == expected, "oracle symmetric " + i);
            check(DopRegionSchedule.independent(a,
                    access(other, fieldA, startA, endA, modeA)), "different allocations independent " + i);
        }
    }

    private static void checkGraphs() {
        var rng = new Random(0xC0FFEE99L);
        var modes = DopRegionSchedule.Mode.values();
        var root = DopRegionSchedule.Root.trustedCreate(32, 4);
        for (int trial = 0; trial < 512; trial++) {
            var tasks = new ArrayList<DopRegionSchedule.Task>();
            for (int i = 0; i < 18; i++) {
                var accesses = new ArrayList<DopRegionSchedule.Access>();
                for (int j = 0, num = 1 + rng.nextInt(2); j < num; j++) {
                    int field = rng.nextInt(4), first = rng.nextInt(33);
                    int last = first + rng.nextInt(33 - first);
                    accesses.add(access(root, field, first, last,
                            modes[rng.nextInt(modes.length)]));
                }
                tasks.add(new DopRegionSchedule.Task("task" + i, accesses));
            }
            var levels = DopRegionSchedule.dependencyLevels(tasks);
            var levelOf = new int[tasks.size()];
            var seen = new boolean[tasks.size()];
            for (int level = 0; level < levels.size(); level++) {
                for (int i : levels.get(level)) {
                    check(i >= 0 && i < tasks.size(), "invalid task index");
                    check(!seen[i], "duplicate task");
                    seen[i] = true;
                    levelOf[i] = level;
                }
            }
            for (int i = 0; i < tasks.size(); i++) {
                check(seen[i], "omitted task");
                for (int j = 0; j < i; j++) {
                    if (!DopRegionSchedule.independent(tasks.get(j), tasks.get(i))) {
                        check(levelOf[j] < levelOf[i], "conflicting tasks overlap in trial " + trial);
                    }
                }
            }
        }
        check(DopRegionSchedule.dependencyLevels(List.of()).isEmpty(), "empty planner result");
    }

    public static void main(String[] args) {
        boundsAndImmutability();
        checkOracle();
        checkGraphs();
        System.out.println("PASS: " + checks + " DOP planner standalone assertions (Java 21)");
    }
}
