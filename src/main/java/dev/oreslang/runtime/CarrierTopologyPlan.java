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
    record Location(int cpu, int packageId, int coreId, int l2Id,
                    int llcId, int numaNode) {
        static final Location UNKNOWN = new Location(-1, -1, -1, -1, -1, -1);

        /** Compatibility constructor for the earlier LLC/NUMA-only planner. */
        Location(int cpu, int packageId, int llcId, int numaNode) {
            this(cpu, packageId, -1, -1, llcId, numaNode);
        }

        Location {
            if (cpu < -1 || packageId < -1 || coreId < -1 || l2Id < -1
                    || llcId < -1 || numaNode < -1) {
                throw new IllegalArgumentException("invalid carrier topology location");
            }
            if (cpu < 0 && (packageId >= 0 || coreId >= 0 || l2Id >= 0
                    || llcId >= 0 || numaNode >= 0)) {
                throw new IllegalArgumentException(
                        "unknown CPU cannot carry topology evidence");
            }
            if ((coreId >= 0 || l2Id >= 0 || llcId >= 0) && packageId < 0) {
                throw new IllegalArgumentException(
                        "cache/core identity requires a physical package");
            }
        }
    }

    private record CacheLevel(int id, int level) {
        static CacheLevel unknown() { return new CacheLevel(-1, -1); }
    }

    private static final int MAX_CACHE_INDEXES = 32;
    private static final int MAX_NUMA_ENTRIES = 256;
    private static final int MAX_SYSFS_BYTES = 64;

    private final Location[] locations;
    // thief -> tier (core, L2, LLC, NUMA, fallback) -> victim slots
    private final int[][][] peers

    CarrierTopologyPlan(Location[] snapshot) {
        if (snapshot == null || snapshot.length == 0) throw new IllegalArgumentException("empty worker topology");
        locations = snapshot.clone();
        for (Location location : locations) {
            if (location == null) throw new IllegalArgumentException("null carrier topology entry");
        }
        peers = new int[locations.length][5][];
        for (int thief = 0; thief < locations.length; thief++) {
            @SuppressWarnings("unchecked")
            List<Integer>[] groups = new List[]{
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>()};
            for (int victim = 0; victim < locations.length; victim++) {
                if (victim != thief) groups[tier(thief, victim)].add(victim);
            }
            for (int tier = 0; tier < 5; tier++) {
                peers[thief][tier] = groups[tier].stream().mapToInt(Integer::intValue).toArray();
            }
        }
    }

    int workerCount() { return locations.length; }

    /** Bound expensive immutable O(workers²) topology re-planning during
     * native worker bootstrap: at most O(log(workers)) new snapshots, plus
     * the initial all-unknown plan. Final snapshot is always published. */
    static boolean publishAfterWorker(int observed, int maximum) {
        if (maximum < 1 || observed < 1 || observed > maximum)
            throw new IllegalArgumentException("invalid topology discovery progress");
        return observed == maximum || (observed & (observed - 1)) == 0;
    }
    int tier(int thief, int victim) {
        Location a = locations[thief], b = locations[victim];
        if (a.packageId() >= 0 && a.packageId() == b.packageId()
                && a.coreId() >= 0 && a.coreId() == b.coreId()) return 0;
        if (a.packageId() >= 0 && a.packageId() == b.packageId()
                && a.l2Id() >= 0 && a.l2Id() == b.l2Id()) return 1;
        if (a.packageId() >= 0 && a.packageId() == b.packageId()
                && a.llcId() >= 0 && a.llcId() == b.llcId()) return 2;
        if (a.numaNode() >= 0 && a.numaNode() == b.numaNode()) return 3;
        return 4;
    }

    /** No allocations, cyclic fairness within each locality tier. */
    int victimAt(int thief, int rank, int rotation) {
        if (thief < 0 || thief >= peers.length || rank < 0 || rank >= peers.length - 1)
            throw new IllegalArgumentException("invalid steal rank");
        for (int tier = 0; tier < 5; tier++) {
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

    private static String readString(Path path) {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_SYSFS_BYTES + 1);
            if (bytes.length > MAX_SYSFS_BYTES) return null;
            for (byte value : bytes) {
                if (value == 0 || (value & 0x80) != 0) return null;
            }
            return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private static int readInt(Path path) {
        String s = readString(path);
        if (s == null) return -1;
        try {
            int n = Integer.parseInt(s);
            return n >= 0 ? n : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static CacheLevel discoverCacheLevel(Path cpu, int wantedLevel) {
        Path root = cpu.resolve("cache");
        if (!Files.isDirectory(root) || wantedLevel < 2) return CacheLevel.unknown();
        int id = -1;
        for (int index = 0; index < MAX_CACHE_INDEXES; index++) {
            Path dir = root.resolve("index" + index);
            if (!Files.isDirectory(dir)) continue;
            int level = readInt(dir.resolve("level"));
            int candidate = readInt(dir.resolve("id"));
            String type = readString(dir.resolve("type"));
            if (level != wantedLevel || candidate < 0
                    || type == null || !type.equalsIgnoreCase("Unified")) continue;
            if (id >= 0 && id != candidate) return CacheLevel.unknown();
            id = candidate;
        }
        return id < 0 ? CacheLevel.unknown() : new CacheLevel(id, wantedLevel);
    }

    private static CacheLevel discoverLastLevelCache(Path cpu) {
        Path root = cpu.resolve("cache");
        if (!Files.isDirectory(root)) return CacheLevel.unknown();
        int bestLevel = -1;
        int bestId = -1;
        for (int index = 0; index < MAX_CACHE_INDEXES; index++) {
            Path dir = root.resolve("index" + index);
            if (!Files.isDirectory(dir)) continue;
            int level = readInt(dir.resolve("level"));
            int candidate = readInt(dir.resolve("id"));
            String type = readString(dir.resolve("type"));
            if (level < 2 || candidate < 0 || type == null
                    || !type.equalsIgnoreCase("Unified")) continue;
            if (level > bestLevel) {
                bestLevel = level;
                bestId = candidate;
            } else if (level == bestLevel && bestId != candidate) {
                return CacheLevel.unknown();
            }
        }
        return bestId < 0 ? CacheLevel.unknown()
                : new CacheLevel(bestId, bestLevel);
    }

    private static int discoverNuma(Path cpu) {
        int result = -1;
        int examined = 0;
        try (DirectoryStream<Path> nodes = Files.newDirectoryStream(cpu, "node[0-9]*")) {
            for (Path node : nodes) {
                if (examined++ >= MAX_NUMA_ENTRIES) return -1;
                String name = node.getFileName().toString();
                int id;
                try {
                    id = Integer.parseInt(name.substring(4));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (id < 0) continue;
                if (result >= 0 && result != id) return -1;
                result = id;
            }
        } catch (IOException | SecurityException ignored) {
            return -1;
        }
        return result;
    }

    /** Linux cpuset is already validated by the native pthread binder. */
    static Location discover(int admittedCpu) {
        if (admittedCpu < 0 || !System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("linux"))
            return Location.UNKNOWN;
        return discoverAt(admittedCpu, Path.of("/sys/devices/system/cpu"));
    }

    /** Package-private test seam for cpuset-restricted or incomplete sysfs. */
    static Location discoverAt(int admittedCpu, Path sysCpuRoot) {
        if (admittedCpu < 0 || sysCpuRoot == null) return Location.UNKNOWN;
        Path cpu = sysCpuRoot.resolve("cpu" + admittedCpu);
        int pkg = readInt(cpu.resolve("topology/physical_package_id"));
        int core = readInt(cpu.resolve("topology/core_id"));
        CacheLevel l2 = discoverCacheLevel(cpu, 2);
        CacheLevel llc = discoverLastLevelCache(cpu);

        // Cache/core numeric identities are meaningful only with a physical
        // package qualifier. Unknown evidence degrades independently.
        if (pkg < 0) {
            core = -1;
            l2 = CacheLevel.unknown();
            llc = CacheLevel.unknown();
        }
        return new Location(
                admittedCpu,
                pkg,
                core,
                l2.id(),
                llc.id(),
                discoverNuma(cpu));
    }
}
