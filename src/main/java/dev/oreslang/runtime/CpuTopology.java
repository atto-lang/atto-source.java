package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable host-topology snapshot used only for placement preference.
 *
 * <p>Topology never grants memory authority. Unknown or partially discovered
 * topology degrades to ordinary soft-affinity behavior. Linux discovery is
 * bounded and reads sysfs only; other platforms currently report unknown
 * topology rather than inventing a cache/NUMA hierarchy.</p>
 */
final class CpuTopology {
    static final int UNKNOWN = -1;
    private static final int MAX_DISCOVERED_CPUS = 8192;
    private static final int MAX_CACHE_INDEXES = 32;

    record Location(int cpu, int llc, int numa) {
        Location {
            if (cpu < 0 || llc < UNKNOWN || numa < UNKNOWN) {
                throw new IllegalArgumentException("invalid CPU topology location");
            }
        }
    }

    private final Map<Integer, Location> locations;

    private CpuTopology(Map<Integer, Location> locations) {
        this.locations = Map.copyOf(locations);
    }

    static CpuTopology empty() {
        return new CpuTopology(Map.of());
    }

    static CpuTopology of(Location... locations) {
        Objects.requireNonNull(locations, "locations");
        Map<Integer, Location> byCpu = new HashMap<>();
        for (Location location : locations) {
            Objects.requireNonNull(location, "location");
            if (byCpu.putIfAbsent(location.cpu(), location) != null) {
                throw new IllegalArgumentException("duplicate logical CPU " + location.cpu());
            }
        }
        return new CpuTopology(byCpu);
    }

    /**
     * 0=same logical CPU, 1=same known LLC, 2=same known NUMA node,
     * 3=remote/unknown. Unknown topology never creates a locality proof.
     */
    int localityRank(int preferredCpu, int candidateCpu) {
        if (preferredCpu >= 0 && preferredCpu == candidateCpu) return 0;
        Location preferred = locations.get(preferredCpu);
        Location candidate = locations.get(candidateCpu);
        if (preferred == null || candidate == null) return 3;
        if (preferred.llc() >= 0 && candidate.llc() >= 0
                && preferred.llc() == candidate.llc()) return 1;
        if (preferred.numa() >= 0 && candidate.numa() >= 0
                && preferred.numa() == candidate.numa()) return 2;
        return 3;
    }

    boolean hasKnownLocality(int cpu) {
        Location location = locations.get(cpu);
        return location != null && (location.llc() >= 0 || location.numa() >= 0);
    }

    static CpuTopology discoverHost() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("linux")) return empty();

        Path cpuRoot = Path.of("/sys/devices/system/cpu");
        if (!Files.isDirectory(cpuRoot)) return empty();

        Map<Integer, Location> discovered = new HashMap<>();
        try (DirectoryStream<Path> cpus = Files.newDirectoryStream(cpuRoot, "cpu[0-9]*")) {
            for (Path cpuDir : cpus) {
                if (discovered.size() >= MAX_DISCOVERED_CPUS) break;
                int cpu = parseNumericSuffix(cpuDir.getFileName().toString(), "cpu");
                if (cpu < 0) continue;
                discovered.put(cpu, new Location(
                        cpu,
                        discoverLastLevelCache(cpuDir),
                        discoverNumaNode(cpuDir)));
            }
        } catch (IOException | SecurityException ignored) {
            return empty();
        }
        return new CpuTopology(discovered);
    }

    private static int discoverLastLevelCache(Path cpuDir) {
        Path cacheRoot = cpuDir.resolve("cache");
        if (!Files.isDirectory(cacheRoot)) return UNKNOWN;
        int bestLevel = -1;
        int bestId = UNKNOWN;
        for (int index = 0; index < MAX_CACHE_INDEXES; index++) {
            Path dir = cacheRoot.resolve("index" + index);
            if (!Files.isDirectory(dir)) continue;
            int level = readInt(dir.resolve("level"));
            int id = readInt(dir.resolve("id"));
            String type = readString(dir.resolve("type"));
            if (level <= 0 || id < 0 || type == null) continue;
            if (!type.equalsIgnoreCase("Unified") && !type.equalsIgnoreCase("Data")) continue;
            if (level > bestLevel) {
                bestLevel = level;
                bestId = id;
            }
        }
        return bestId;
    }

    private static int discoverNumaNode(Path cpuDir) {
        try (DirectoryStream<Path> nodes = Files.newDirectoryStream(cpuDir, "node[0-9]*")) {
            int result = UNKNOWN;
            for (Path node : nodes) {
                int parsed = parseNumericSuffix(node.getFileName().toString(), "node");
                if (parsed >= 0 && (result == UNKNOWN || parsed < result)) result = parsed;
            }
            return result;
        } catch (IOException | SecurityException ignored) {
            return UNKNOWN;
        }
    }

    private static int readInt(Path path) {
        String value = readString(path);
        if (value == null) return UNKNOWN;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return UNKNOWN;
        }
    }

    private static String readString(Path path) {
        try {
            String value = Files.readString(path).trim();
            return value.length() <= 64 ? value : null;
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private static int parseNumericSuffix(String value, String prefix) {
        if (!value.startsWith(prefix) || value.length() == prefix.length()) return UNKNOWN;
        try {
            int parsed = Integer.parseInt(value.substring(prefix.length()));
            return parsed >= 0 ? parsed : UNKNOWN;
        } catch (NumberFormatException ignored) {
            return UNKNOWN;
        }
    }
}
