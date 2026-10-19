package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable, compact carrier locality ranking. Physical placement is always a
 * hint: it does not transfer actor ownership, authorize a Task borrow, or
 * require any specific CPU topology to preserve scheduling correctness.
 *
 * Discovery is best-effort and happens ONLY at native carrier entry, never
 * from the dispatch/steal hot path. No topology => rotating global order.
 */
final class CarrierTopologyPlan {
    record Location(int cpu, int packageId, int llcId, int numaNode) {
        static final Location UNKNOWN = new Location(-1, -1, -1, -1);
    }

    private final Location[] locations;
    private final int[][][] peers; // thief -> tier (LLC, NUMA, fallback) -> victim slots

    CarrierTopologyPlan(Location[] snapshot) {
        if (snapshot == null || snapshot.length == 0) throw new IllegalArgumentException("empty worker topology");
        locations = snapshot.clone();
        for (Location location : locations) {
            if (location == null) throw new IllegalArgumentException("null carrier topology entry");
        }
        peers = new int[locations.length][3][];
        for (int thief = 0; thief < locations.length; thief++) {
            @SuppressWarnings("unchecked")
            List<Integer>[] groups = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
            for (int victim = 0; victim < locations.length; victim++) {
                if (victim != thief) groups[tier(thief, victim)].add(victim);
            }
            for (int tier = 0; tier < 3; tier++) {
                peers[thief][tier] = groups[tier].stream().mapToInt(Integer::intValue).toArray();
            }
        }
    }

    int workerCount() { return locations.length; }
    int tier(int thief, int victim) {
        Location a = locations[thief], b = locations[victim];
        if (a.llcId() >= 0 && a.packageId() >= 0
                && a.packageId() == b.packageId() && a.llcId() == b.llcId()) return 0;
        if (a.numaNode() >= 0 && a.numaNode() == b.numaNode()) return 1;
        return 2;
    }

    /** No allocations, cyclic fairness within each locality tier. */
    int victimAt(int thief, int rank, int rotation) {
        if (thief < 0 || thief >= peers.length || rank < 0 || rank >= peers.length - 1)
            throw new IllegalArgumentException("invalid steal rank");
        for (int tier = 0; tier < 3; tier++) {
            int[] group = peers[thief][tier];
            if (rank < group.length) {
                // Compute in 64 bits: int overflow would repeat/skip a
                // victim when the steal cursor crosses Integer.MAX_VALUE.
                return group[(int)Math.floorMod((long)rotation + rank, group.length)];
            }
            rank -= group.length;
        }
        throw new IllegalStateException("topology rank invariant");
    }

    /** Periodic global sweep prevents aged remote work from starving under
     * sustained local backlog. The mapping is a full permutation of all
     * other carrier slots, independent of topology and integer rollover. */
    int globalVictimAt(int thief, int rank, int rotation) {
        if (thief < 0 || thief >= peers.length || rank < 0 || rank >= peers.length - 1)
            throw new IllegalArgumentException("invalid global steal rank");
        int ordinal = (int)Math.floorMod((long)rotation + rank, peers.length - 1);
        return ordinal >= thief ? ordinal + 1 : ordinal;
    }

    private static int readInt(Path path) {
        try {
            String s = Files.readString(path).trim();
            int n = Integer.parseInt(s);
            return n >= 0 ? n : -1;
        } catch (IOException | RuntimeException ignored) {
            return -1;
        }
    }

    /** Linux cpuset is already validated by the native pthread binder. */
    static Location discover(int admittedCpu) {
        if (admittedCpu < 0 || !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux"))
            return Location.UNKNOWN;
        Path cpu = Path.of("/sys/devices/system/cpu", "cpu" + admittedCpu);
        int pkg = readInt(cpu.resolve("topology/physical_package_id"));
        int llc = readInt(cpu.resolve("cache/index3/id")); // never compare across unknown packages
        int numa = -1;
        try (DirectoryStream<Path> nodes = Files.newDirectoryStream(cpu, "node*")) {
            for (Path node : nodes) {
                String name = node.getFileName().toString();
                try {
                    int id = Integer.parseInt(name.substring(4));
                    if (id >= 0) { numa = id; break; }
                } catch (NumberFormatException ignored) { /* unknown platform entry */ }
            }
        } catch (IOException | RuntimeException ignored) {
            // Missing sysfs or container restrictions are normal.
        }
        return new Location(admittedCpu, pkg, llc, numa);
    }
}
