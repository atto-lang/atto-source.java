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
    private static final int MAX_CPU_DIRECTORY_ENTRIES = MAX_DISCOVERED_CPUS * 2;
    private static final int MAX_NUMA_DIRECTORY_ENTRIES = 256;
    private static final int MAX_CACHE_INDEXES = 32;
    // A Task locality hint must never trigger a full scan of thousands
    // of carrier slots on the scheduling hot path.
    private static final int MAX_NUMA_SLOT_PROBES = 64;

    /* The cache ID alone is not globally unique across packages or levels.
     * Three-argument synthetic locations deliberately carry no proof of LLC
     * sharing, regardless of a matching numeric cache ID. */
    record Location(int cpu, int llc, int numa, int llcLevel, int packageId,
                    int l2, int coreId, int cacheLineBytes) {
        Location(int cpu, int llc, int numa) {
            this(cpu, llc, numa, UNKNOWN, UNKNOWN,
                    UNKNOWN, UNKNOWN, UNKNOWN);
        }
        Location(int cpu, int llc, int numa, int llcLevel, int packageId) {
            this(cpu, llc, numa, llcLevel, packageId,
                    UNKNOWN, UNKNOWN, UNKNOWN);
        }
        Location {
            if (cpu < 0 || llc < UNKNOWN || numa < UNKNOWN
                    || llcLevel < UNKNOWN || packageId < UNKNOWN
                    || l2 < UNKNOWN || coreId < UNKNOWN
                    || cacheLineBytes < UNKNOWN
                    || (llcLevel >= 0
                        && (llc < 0 || packageId < 0 || llcLevel < 2))
                    || (l2 >= 0 && packageId < 0)
                    || (coreId >= 0 && packageId < 0)
                    || (cacheLineBytes != UNKNOWN
                        && (cacheLineBytes < 16 || cacheLineBytes > 4096
                            || (cacheLineBytes & (cacheLineBytes - 1)) != 0))) {
                throw new IllegalArgumentException("invalid CPU topology location");
            }
        }
    }
    private record CacheLevel(int id, int level) {
        static CacheLevel unknown() { return new CacheLevel(UNKNOWN, UNKNOWN); }
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
                && preferred.llcLevel() >= 2 && candidate.llcLevel() >= 2
                && preferred.packageId() >= 0 && candidate.packageId() >= 0
                && preferred.llc() == candidate.llc()
                && preferred.llcLevel() == candidate.llcLevel()
                && preferred.packageId() == candidate.packageId()) return 1;
        if (preferred.numa() >= 0 && candidate.numa() >= 0
                && preferred.numa() == candidate.numa()) return 2;
        return 3;
    }

    /**
     * Fine-grained CPU placement rank without changing the older coarse
     * localityRank contract: exact logical CPU, same physical core, verified
     * L2, verified LLC, same NUMA node, then remote/unknown.
     *
     * Core identity is topology evidence only. It does not claim that SMT
     * siblings share a particular L1 implementation or that co-scheduling two
     * busy tasks on one physical core is always faster.
     */
    int cpuAffinityRank(int preferredCpu, int candidateCpu) {
        if (preferredCpu >= 0 && preferredCpu == candidateCpu) return 0;
        Location preferred = locations.get(preferredCpu);
        Location candidate = locations.get(candidateCpu);
        if (preferred == null || candidate == null) return 5;
        if (preferred.packageId() >= 0 && candidate.packageId() >= 0
                && preferred.coreId() >= 0 && candidate.coreId() >= 0
                && preferred.packageId() == candidate.packageId()
                && preferred.coreId() == candidate.coreId()) return 1;
        if (preferred.packageId() >= 0 && candidate.packageId() >= 0
                && preferred.l2() >= 0 && candidate.l2() >= 0
                && preferred.packageId() == candidate.packageId()
                && preferred.l2() == candidate.l2()) return 2;
        if (preferred.llc() >= 0 && candidate.llc() >= 0
                && preferred.llcLevel() >= 2 && candidate.llcLevel() >= 2
                && preferred.packageId() >= 0 && candidate.packageId() >= 0
                && preferred.llc() == candidate.llc()
                && preferred.llcLevel() == candidate.llcLevel()
                && preferred.packageId() == candidate.packageId()) return 3;
        if (preferred.numa() >= 0 && candidate.numa() >= 0
                && preferred.numa() == candidate.numa()) return 4;
        return 5;
    }

    int cacheLineBytes(int cpu) {
        Location location = locations.get(cpu);
        return location == null ? UNKNOWN : location.cacheLineBytes();
    }

    /**
     * Stable, allocation-free rendezvous score for one carrier slot.
     *
     * The previous first-local-match scan concentrated unrelated keys onto
     * the first NUMA-local slot after a remote run of slots. Ranking *all*
     * bounded eligible slots spreads stable keys without per-turn state or
     * round-robin writes that could bounce an L1/L2 cache line.
     */
    private static long slotScore(int affinityKey, int slot) {
        long value = ((long) affinityKey << 32) ^ Integer.toUnsignedLong(slot);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    /**
     * Choose the best observed CPU target in a bounded scan. Same advertised
     * logical CPU beats same physical core, verified L2, qualified LLC, then
     * NUMA locality.
     * Absent topology evidence, preserve the ordinary affinity hash.
     *
     * This is a SOFT scheduler hint, never a physical pin, page-placement
     * proof, actor memory authority or GPU queue admission.
     */
    int preferredSlotForCpu(int affinityKey, int preferredCpu, int enabled,
                            java.util.function.IntUnaryOperator cpuForSlot) {
        Objects.requireNonNull(cpuForSlot, "cpuForSlot");
        if (enabled < 1 || preferredCpu < UNKNOWN)
            throw new IllegalArgumentException("invalid carrier/CPU bounds");
        int fallback = Math.floorMod(affinityKey, enabled);
        if (preferredCpu == UNKNOWN || enabled > MAX_DISCOVERED_CPUS)
            return fallback;

        int bestRank = 5;
        long bestScore = 0;
        int bestSlot = fallback;
        for (int distance = 0;
                distance < Math.min(enabled, MAX_NUMA_SLOT_PROBES);
                distance++) {
            int slot = (int) (((long) fallback + distance) % enabled);
            int rank = cpuAffinityRank(preferredCpu, cpuForSlot.applyAsInt(slot));
            if (rank >= 5) continue;
            long score = slotScore(affinityKey, slot);
            if (rank < bestRank || (rank == bestRank
                    && Long.compareUnsigned(score, bestScore) > 0)) {
                bestRank = rank;
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /** Select a carrier with an observed target in the requested NUMA node.
     * Inspect at most 64 slots to keep worst-case Task admission bounded.
     * When no nearby eligible carrier is observed, use the stable hash.
     * This is advisory only: CPU target != proof of page placement. */
    int preferredSlotForNuma(int affinityKey, int preferredNuma, int enabled,
                             java.util.function.IntUnaryOperator cpuForSlot) {
        Objects.requireNonNull(cpuForSlot, "cpuForSlot");
        if (enabled < 1 || preferredNuma < UNKNOWN || preferredNuma > 65535) {
            throw new IllegalArgumentException("invalid carrier/NUMA bounds");
        }
        int fallback = Math.floorMod(affinityKey, enabled);
        // Untargeted SOFT dispatch must remain valid for larger carrier
        // pools. Never perform an unbounded NUMA topology scan on hot paths.
        if (preferredNuma == UNKNOWN || enabled > MAX_DISCOVERED_CPUS)
            return fallback;
        int bestSlot = fallback;
        long bestScore = 0;
        boolean found = false;
        for (int distance = 0;
                distance < Math.min(enabled, MAX_NUMA_SLOT_PROBES);
                distance++) {
            int slot = (int) (((long) fallback + distance) % enabled);
            Location location = locations.get(cpuForSlot.applyAsInt(slot));
            if (location == null || location.numa() != preferredNuma) continue;
            long score = slotScore(affinityKey, slot);
            if (!found || Long.compareUnsigned(score, bestScore) > 0) {
                bestSlot = slot;
                bestScore = score;
                found = true;
            }
        }
        return bestSlot;
    }

    boolean hasKnownLocality(int cpu) {
        Location location = locations.get(cpu);
        return location != null && ((location.llc() >= 0 && location.llcLevel() >= 2 && location.packageId() >= 0) || location.numa() >= 0);
    }

    static CpuTopology discoverHost() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("linux")) return empty();

        return discoverAt(Path.of("/sys/devices/system/cpu"));
    }

    /** Testable bounded sysfs ingestion; never called from an actor turn. */
    static CpuTopology discoverAt(Path cpuRoot) {
        Objects.requireNonNull(cpuRoot, "cpuRoot");
        if (!Files.isDirectory(cpuRoot)) return empty();

        Map<Integer, Location> discovered = new HashMap<>();
        try (DirectoryStream<Path> cpus = Files.newDirectoryStream(cpuRoot, "cpu[0-9]*")) {
            int examined = 0;
            for (Path cpuDir : cpus) {
                if (examined++ >= MAX_CPU_DIRECTORY_ENTRIES
                        || discovered.size() >= MAX_DISCOVERED_CPUS) {
                    // A partial host snapshot must not be treated as locality
                    // evidence. Unsupported scale degrades to ordinary affinity.
                    return empty();
                }
                int cpu = parseNumericSuffix(cpuDir.getFileName().toString(), "cpu");
                if (cpu < 0) continue;
                if (discovered.containsKey(cpu)) {
                    // Ambiguous aliases such as cpu0/cpu00 mean this is not a
                    // trustworthy sysfs topology snapshot. Degrade to unknown.
                    return empty();
                }
                int packageId = readInt(cpuDir.resolve(
                        "topology/physical_package_id"));
                int coreId = readInt(cpuDir.resolve("topology/core_id"));
                CacheLevel cache = discoverLastLevelCache(cpuDir);
                CacheLevel l2 = discoverCacheLevel(cpuDir, 2);
                int cacheLineBytes = discoverCacheLineBytes(cpuDir);
                /* Unknown package identity cannot establish cache/core
                 * locality across CPUs: fall back to NUMA or remote. */
                final boolean trustedCache = packageId >= 0 && cache.id() >= 0;
                final boolean trustedL2 = packageId >= 0 && l2.id() >= 0;
                final boolean trustedCore = packageId >= 0 && coreId >= 0;
                discovered.put(cpu, new Location(
                        cpu, trustedCache ? cache.id() : UNKNOWN,
                        discoverNumaNode(cpuDir),
                        trustedCache ? cache.level() : UNKNOWN,
                        packageId >= 0 ? packageId : UNKNOWN,
                        trustedL2 ? l2.id() : UNKNOWN,
                        trustedCore ? coreId : UNKNOWN,
                        cacheLineBytes));
            }
        } catch (IOException | SecurityException ignored) {
            return empty();
        }
        return new CpuTopology(discovered);
    }

    private static CacheLevel discoverLastLevelCache(Path cpuDir) {
        Path cacheRoot = cpuDir.resolve("cache");
        if (!Files.isDirectory(cacheRoot)) return CacheLevel.unknown();
        int bestLevel = UNKNOWN;
        int bestId = UNKNOWN;
        for (int index = 0; index < MAX_CACHE_INDEXES; index++) {
            Path dir = cacheRoot.resolve("index" + index);
            if (!Files.isDirectory(dir)) continue;
            int level = readInt(dir.resolve("level"));
            int id = readInt(dir.resolve("id"));
            String type = readString(dir.resolve("type"));
            /* A per-core L1D ID is not evidence of a shared LLC. Requiring
             * a unified L2+ avoids conflating independent cache kinds. */
            if (level < 2 || id < 0 || type == null
                    || !type.equalsIgnoreCase("Unified")) continue;
            if (level > bestLevel) {
                bestLevel = level;
                bestId = id;
            } else if (level == bestLevel && bestId >= 0 && id != bestId) {
                // Multiple conflicting identities for the same highest cache
                // level are ambiguous evidence, not a locality proof.
                return CacheLevel.unknown();
            }
        }
        return new CacheLevel(bestId, bestLevel);
    }

    private static CacheLevel discoverCacheLevel(Path cpuDir, int wantedLevel) {
        Path cacheRoot = cpuDir.resolve("cache");
        if (!Files.isDirectory(cacheRoot) || wantedLevel < 2) {
            return CacheLevel.unknown();
        }
        int result = UNKNOWN;
        for (int index = 0; index < MAX_CACHE_INDEXES; index++) {
            Path dir = cacheRoot.resolve("index" + index);
            if (!Files.isDirectory(dir)) continue;
            int level = readInt(dir.resolve("level"));
            int id = readInt(dir.resolve("id"));
            String type = readString(dir.resolve("type"));
            if (level != wantedLevel || id < 0 || type == null
                    || !type.equalsIgnoreCase("Unified")) continue;
            if (result != UNKNOWN && result != id) return CacheLevel.unknown();
            result = id;
        }
        return result == UNKNOWN
                ? CacheLevel.unknown() : new CacheLevel(result, wantedLevel);
    }

    private static int discoverCacheLineBytes(Path cpuDir) {
        Path cacheRoot = cpuDir.resolve("cache");
        if (!Files.isDirectory(cacheRoot)) return UNKNOWN;
        int result = UNKNOWN;
        for (int index = 0; index < MAX_CACHE_INDEXES; index++) {
            Path dir = cacheRoot.resolve("index" + index);
            if (!Files.isDirectory(dir)) continue;
            int line = readInt(dir.resolve("coherency_line_size"));
            if (line < 16 || line > 4096 || (line & (line - 1)) != 0) continue;
            if (result != UNKNOWN && result != line) return UNKNOWN;
            result = line;
        }
        return result;
    }

    private static int discoverNumaNode(Path cpuDir) {
        try (DirectoryStream<Path> nodes = Files.newDirectoryStream(cpuDir, "node[0-9]*")) {
            int result = UNKNOWN;
            int examined = 0;
            for (Path node : nodes) {
                if (examined++ >= MAX_NUMA_DIRECTORY_ENTRIES) return UNKNOWN;
                int parsed = parseNumericSuffix(node.getFileName().toString(), "node");
                if (parsed < 0) continue;
                if (result != UNKNOWN && parsed != result) {
                    // A logical CPU cannot prove residence in two NUMA nodes.
                    return UNKNOWN;
                }
                result = parsed;
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
        // sysfs metadata is expected to be tiny. Reading the entire file
        // before checking length lets a hostile/mis-mounted sysfs fixture
        // allocate unbounded memory during carrier bootstrap.
        try (var input = Files.newInputStream(path)) {
            byte[] bounded = input.readNBytes(65);
            if (bounded.length > 64) return null;
            for (byte value : bounded) {
                // Reject embedded NUL/non-ASCII metadata. Integer parsing
                // and cache-kind checks are intentionally stricter still.
                if (value == 0 || (value & 0x80) != 0) return null;
            }
            return new String(bounded, java.nio.charset.StandardCharsets.US_ASCII).trim();
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
