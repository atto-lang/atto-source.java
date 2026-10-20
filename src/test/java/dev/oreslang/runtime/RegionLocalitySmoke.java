package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.IntUnaryOperator;

/** JDK-only source-exact host-topology regression; does not run native JNI. */
public final class RegionLocalitySmoke {
    private RegionLocalitySmoke() { }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejected(Runnable action, String label) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("unexpected admission: " + label);
    }

    public static void main(String[] args) {
        CpuTopology t = CpuTopology.of(
                new CpuTopology.Location(0, 9, 0, 3, 0),
                new CpuTopology.Location(1, 9, 0, 3, 0),
                new CpuTopology.Location(2, 9, 0, 3, 1),
                new CpuTopology.Location(3, 9, 0));
        check(t.localityRank(0, 1) == 1, "qualified shared LLC");
        check(t.localityRank(0, 2) == 2, "foreign-package LLC ID");
        check(t.localityRank(0, 3) == 2, "unqualified LLC identity");

        ExecutionPlacement.Hint hint =
                ExecutionPlacement.Hint.regionTask(91, 2);
        check(hint.kind() == ExecutionPlacement.Kind.TASK
                && hint.affinity() == ExecutionPlacement.Affinity.SOFT
                && hint.preferredNuma() == 2,
                "region hints remain soft Task placement");
        rejected(() -> ExecutionPlacement.Hint.regionTask(1, -2),
                "negative preferred NUMA");
        rejected(() -> new ExecutionPlacement.Hint(
                ExecutionPlacement.Kind.CONTROL,
                ExecutionPlacement.Affinity.SOFT, 1, 0),
                "CONTROL cannot claim region locality");
        rejected(() -> new ExecutionPlacement.Hint(
                ExecutionPlacement.Kind.ACTOR_TURN,
                ExecutionPlacement.Affinity.SOFT, 1, 0),
                "actor placement cannot masquerade as Task region");

        CpuTopology map = CpuTopology.of(
                new CpuTopology.Location(10, 8, 0, 3, 0),
                new CpuTopology.Location(11, 8, 1, 3, 0),
                new CpuTopology.Location(12, 8, 1, 3, 0),
                new CpuTopology.Location(13, CpuTopology.UNKNOWN, CpuTopology.UNKNOWN));
        int[] cpus = {10, 11, 12, 13};
        IntUnaryOperator lookup = slot -> cpus[slot];
        check(map.preferredSlotForNuma(0, 1, 4, lookup) == 1,
                "nearest eligible NUMA slot");
        check(map.preferredSlotForNuma(2, 1, 4, lookup) == 2,
                "stable preferred slot within NUMA");
        check(map.preferredSlotForNuma(-1, CpuTopology.UNKNOWN, 4, lookup) == 3,
                "no NUMA information preserves hash");
        check(CpuTopology.empty().preferredSlotForNuma(0, 1, 4, lookup) == 0,
                "unknown topology never invents a page-placement proof");

        Random rnd = new Random(0x21baff17);
        for (int trial = 0; trial < 10_000; trial++) {
            int enabled = 1 + rnd.nextInt(64);
            int affinity = rnd.nextInt();
            int node = rnd.nextInt(5) - 1;
            int[] targets = new int[enabled];
            Map<Integer, CpuTopology.Location> locations = new HashMap<>();
            for (int cpu = 0; cpu < 16; cpu++) {
                int n = rnd.nextInt(5) - 1;
                locations.put(cpu, new CpuTopology.Location(cpu, -1, n));
            }
            for (int i = 0; i < enabled; i++) targets[i] = rnd.nextInt(16);
            CpuTopology sampled = CpuTopology.of(
                    locations.values().toArray(new CpuTopology.Location[0]));
            int preferred = Math.floorMod(affinity, enabled);
            int expected = preferred;
            if (node >= 0) {
                for (int step = 0; step < enabled; step++) {
                    int candidate = (int) (((long) preferred + step) % enabled);
                    if (locations.get(targets[candidate]).numa() == node) {
                        expected = candidate;
                        break;
                    }
                }
            }
            int actual = sampled.preferredSlotForNuma(
                    affinity, node, enabled, i -> targets[i]);
            check(actual == expected, "NUMA choice diverged from oracle: " + trial);
        }
        System.out.println("REGION_LOCALITY_SMOKE_PASS 10000 NUMA selections");
    }
}
