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
        int chosenLocal = map.preferredSlotForNuma(0, 1, 4, lookup);
        check(chosenLocal == 1 || chosenLocal == 2,
                "NUMA placement must select a node-local carrier");
        check(map.preferredSlotForNuma(0, 1, 4, lookup) == chosenLocal,
                "same affinity key must remain sticky");
        check(map.preferredSlotForNuma(2, 1, 4, lookup) == 2
                        || map.preferredSlotForNuma(2, 1, 4, lookup) == 1,
                "stable preferred slot within NUMA");
        check(map.preferredSlotForNuma(-1, CpuTopology.UNKNOWN, 4, lookup) == 3,
                "no NUMA information preserves hash");
        check(CpuTopology.empty().preferredSlotForNuma(0, 1, 4, lookup) == 0,
                "unknown topology never invents a page-placement proof");

        CpuTopology unqualified = CpuTopology.of(
                new CpuTopology.Location(25, 5, CpuTopology.UNKNOWN),
                new CpuTopology.Location(26, 5, CpuTopology.UNKNOWN, 3, 0));
        check(!unqualified.hasKnownLocality(25),
                "an unqualified cache ID cannot trigger locality scans");
        check(unqualified.hasKnownLocality(26),
                "a qualified LLC can trigger locality scans");

        // A hostile/bad topology hint cannot turn admission into an O(N)
        // walk of every carrier in a large pool.
        int[] largeCpus = new int[8192];
        java.util.Arrays.fill(largeCpus, 11);
        largeCpus[63] = 10;
        largeCpus[64] = 10;
        java.util.concurrent.atomic.AtomicInteger scans =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.IntUnaryOperator counted = slot -> {
            check(scans.incrementAndGet() <= 64,
                    "NUMA hint scanned more than the fixed budget");
            return largeCpus[slot];
        };
        check(map.preferredSlotForNuma(0, 0, 8192, counted) == 63,
                "find NUMA-local carrier at final permitted probe");
        check(scans.get() == 64, "locality probe budget");
        scans.set(0);
        largeCpus[63] = 11;
        check(map.preferredSlotForNuma(0, 0, 8192, counted) == 0,
                "fall back when the matching slot is past the probe budget");
        check(scans.get() == 64, "fallback has fixed probe budget");
        scans.set(0);
        check(map.preferredSlotForNuma(0, 0, 8193, counted) == 0
                && scans.get() == 0,
                "a pool larger than the discovery cap must hash directly");

        // CPU affinity is advisory: rank advertised targets, never grant
        // native pthread, arena, actor state or GPU device authority.
        check(map.preferredSlotForCpu(0, 10, cpus.length, lookup) == 0,
                "exact preferred CPU target takes precedence");
        int nearCpu = map.preferredSlotForCpu(0, 12, cpus.length, lookup);
        check(nearCpu == 2, "same preferred CPU is best when observable");
        check(map.preferredSlotForCpu(3, CpuTopology.UNKNOWN, 4, lookup) == 3,
                "unknown target preserves stable affinity hash");
        rejected(() -> map.preferredSlotForCpu(0, -2, 4, lookup),
                "invalid CPU locality hint");
        java.util.concurrent.atomic.AtomicInteger cpuScans =
                new java.util.concurrent.atomic.AtomicInteger();
        IntUnaryOperator boundedCpuLookup = slot -> {
            check(cpuScans.incrementAndGet() <= 64,
                    "CPU hint performed an unbounded topology scan");
            return slot == 63 ? 10 : 11;
        };
        check(map.preferredSlotForCpu(0, 10, 8192, boundedCpuLookup) == 63,
                "last bounded CPU target must be reachable");

        int[] localHits = new int[8];
        CpuTopology localBias = CpuTopology.of(
                new CpuTopology.Location(100, -1, 0),
                new CpuTopology.Location(101, -1, 1));
        IntUnaryOperator splitCpus = slot -> slot < 8 ? 100 : 101;
        for (int key = 0; key < 4096; key++) {
            int slot = localBias.preferredSlotForNuma(key, 0, 64, splitCpus);
            check(slot >= 0 && slot < 8, "NUMA choice left local set");
            localHits[slot]++;
        }
        for (int count : localHits)
            check(count > 300 && count < 800,
                    "NUMA boundary hotspot under stable per-key load");

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
            boolean haveLocal = false;
            for (int slot = 0; slot < enabled; slot++) {
                if (locations.get(targets[slot]).numa() == node
                        && node >= 0) haveLocal = true;
            }
            int actual = sampled.preferredSlotForNuma(
                    affinity, node, enabled, i -> targets[i]);
            check(actual >= 0 && actual < enabled, "invalid slot: " + trial);
            check(actual == sampled.preferredSlotForNuma(
                    affinity, node, enabled, i -> targets[i]),
                    "sticky choice not deterministic: " + trial);
            if (haveLocal) {
                check(locations.get(targets[actual]).numa() == node,
                        "non-local choice despite eligible carrier: " + trial);
            } else {
                check(actual == preferred,
                        "no locality evidence must preserve original hash: " + trial);
            }
        }
        System.out.println("REGION_LOCALITY_SMOKE_PASS 10000 NUMA selections");
    }
}
