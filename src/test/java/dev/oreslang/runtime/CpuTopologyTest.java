package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

final class CpuTopologyTest {
    @Test
    void ranksSameCpuThenLlcThenNumaThenRemote() {
        CpuTopology topology = CpuTopology.of(
                new CpuTopology.Location(0, 10, 0, 3, 0),
                new CpuTopology.Location(1, 10, 0, 3, 0),
                new CpuTopology.Location(2, 20, 0, 3, 0),
                new CpuTopology.Location(3, 30, 1, 3, 1));

        assertEquals(0, topology.localityRank(0, 0));
        assertEquals(1, topology.localityRank(0, 1));
        assertEquals(2, topology.localityRank(0, 2));
        assertEquals(3, topology.localityRank(0, 3));
    }

    @Test
    void unknownTopologyNeverInventsCacheOrNumaLocality() {
        CpuTopology topology = CpuTopology.of(
                new CpuTopology.Location(4, CpuTopology.UNKNOWN, CpuTopology.UNKNOWN),
                new CpuTopology.Location(5, CpuTopology.UNKNOWN, CpuTopology.UNKNOWN));

        assertEquals(0, topology.localityRank(4, 4));
        assertEquals(3, topology.localityRank(4, 5));
        assertFalse(topology.hasKnownLocality(4));
        assertEquals(3, CpuTopology.empty().localityRank(4, 5));
    }

    @Test
    void sameNumericCacheIdAcrossPackagesOrLevelsIsNotAnLlcProof() {
        CpuTopology topology = CpuTopology.of(
                new CpuTopology.Location(0, 7, 0, 3, 0),
                new CpuTopology.Location(1, 7, 0, 3, 1),
                new CpuTopology.Location(2, 7, 0, 2, 0),
                new CpuTopology.Location(3, 7, 0, 3, 0));
        assertEquals(2, topology.localityRank(0, 1),
                "same numeric cache id in different packages is not same LLC");
        assertEquals(2, topology.localityRank(0, 2),
                "same numeric cache id at different levels is not same LLC");
        assertEquals(1, topology.localityRank(0, 3));
        CpuTopology unqualified = CpuTopology.of(
                new CpuTopology.Location(4, 77, 0),
                new CpuTopology.Location(5, 77, 0));
        assertEquals(2, unqualified.localityRank(4, 5),
                "unqualified numeric cache id must not prove shared LLC");
        CpuTopology noNuma = CpuTopology.of(
                new CpuTopology.Location(6, 77, CpuTopology.UNKNOWN));
        assertFalse(noNuma.hasKnownLocality(6),
                "unknown package/level cache IDs must not initiate peer scans");
        assertTrue(topology.hasKnownLocality(0),
                "qualified LLC should remain eligible for locality scans");
        assertThrows(IllegalArgumentException.class,
                () -> new CpuTopology.Location(4, 7, 0, 3, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new CpuTopology.Location(4, 7, 0, 1, 0));
    }

    @Test
    void sysfsFixtureRejectsL1DataAndUnknownPackageAsSharedLlc(@TempDir Path root)
            throws IOException {
        cpuFixture(root, 0, "0", "Unified", "3", "7", "0");
        cpuFixture(root, 1, "1", "Unified", "3", "7", "0");
        cpuFixture(root, 2, "0", "Unified", "2", "7", "0");
        cpuFixture(root, 3, "0", "Data", "3", "7", "0");
        cpuFixture(root, 4, "0", "Unified", "3", "7", "0");
        cpuFixture(root, 5, null, "Unified", "3", "7", "0");
        CpuTopology topology = CpuTopology.discoverAt(root);
        assertEquals(2, topology.localityRank(0, 1));
        assertEquals(2, topology.localityRank(0, 2));
        assertEquals(2, topology.localityRank(0, 3));
        assertEquals(1, topology.localityRank(0, 4));
        assertEquals(2, topology.localityRank(0, 5));
        assertEquals(3, topology.localityRank(0, 99));
    }

    private static void cpuFixture(Path root, int cpu, String pkg,
            String kind, String level, String id, String numa)
            throws IOException {
        Path dir = root.resolve("cpu" + cpu);
        Path cache = dir.resolve("cache/index0");
        Files.createDirectories(cache);
        Files.createDirectories(dir.resolve("node" + numa));
        Files.writeString(cache.resolve("level"), level);
        Files.writeString(cache.resolve("id"), id);
        Files.writeString(cache.resolve("type"), kind);
        if (pkg != null) {
            Path topology = dir.resolve("topology");
            Files.createDirectories(topology);
            Files.writeString(topology.resolve("physical_package_id"), pkg);
        }
    }

    @Test
    void oversizedAndMalformedSysfsMetadataCannotInventLlc(@TempDir Path root)
            throws IOException {
        cpuFixture(root, 0, "0", "Unified", "3", "7", "0");
        cpuFixture(root, 1, "0", "Unified", "3", "7", "0");
        Path cache = root.resolve("cpu1/cache/index0");
        // A bogus sysfs file can be arbitrarily large; the reader must cap
        // bytes *before* allocating a decoded String.
        Files.writeString(cache.resolve("id"), "7".repeat(1024 * 1024));
        CpuTopology topology = CpuTopology.discoverAt(root);
        assertEquals(2, topology.localityRank(0, 1));

        Files.writeString(cache.resolve("id"), "7");
        Files.writeString(cache.resolve("type"), "Unified\0Spoofed");
        topology = CpuTopology.discoverAt(root);
        assertEquals(2, topology.localityRank(0, 1));

        Files.writeString(cache.resolve("type"), "Unified");
        topology = CpuTopology.discoverAt(root);
        assertEquals(1, topology.localityRank(0, 1));
    }

    @Test
    void conflictingHighestCacheIdsFailClosed(@TempDir Path root) throws IOException {
        cpuFixture(root, 0, "0", "Unified", "3", "7", "0");
        cpuFixture(root, 1, "0", "Unified", "3", "7", "0");
        Path second = root.resolve("cpu1/cache/index1");
        Files.createDirectories(second);
        Files.writeString(second.resolve("level"), "3");
        Files.writeString(second.resolve("id"), "9");
        Files.writeString(second.resolve("type"), "Unified");

        CpuTopology topology = CpuTopology.discoverAt(root);
        assertEquals(2, topology.localityRank(0, 1),
                "conflicting highest-level cache IDs must not prove same LLC");
    }

    @Test
    void multipleNumaNodesForOneCpuFailClosed(@TempDir Path root) throws IOException {
        cpuFixture(root, 0, "0", "Unified", "3", "7", "0");
        cpuFixture(root, 1, "0", "Unified", "3", "8", "0");
        Files.createDirectories(root.resolve("cpu0/node1"));

        CpuTopology topology = CpuTopology.discoverAt(root);
        assertEquals(3, topology.localityRank(0, 1),
                "ambiguous NUMA membership must not prove same node");
    }

    @Test
    void duplicateSysfsCpuAliasesFailClosed(@TempDir Path root) throws IOException {
        cpuFixture(root, 0, "0", "Unified", "3", "7", "0");
        Path alias = root.resolve("cpu00");
        Path cache = alias.resolve("cache/index0");
        Files.createDirectories(cache);
        Files.createDirectories(alias.resolve("node0"));
        Files.createDirectories(alias.resolve("topology"));
        Files.writeString(alias.resolve("topology/physical_package_id"), "0");
        Files.writeString(cache.resolve("level"), "3");
        Files.writeString(cache.resolve("id"), "7");
        Files.writeString(cache.resolve("type"), "Unified");

        CpuTopology topology = CpuTopology.discoverAt(root);
        assertEquals(3, topology.localityRank(0, 1),
                "ambiguous logical CPU aliases must degrade the whole snapshot to unknown");
        assertFalse(topology.hasKnownLocality(0));
    }

    @Test
    void excessiveNumaAliasesDegradeThatCpuToUnknownNuma(@TempDir Path root)
            throws IOException {
        cpuFixture(root, 0, "0", "Unified", "3", "7", "0");
        cpuFixture(root, 1, "0", "Unified", "3", "8", "0");
        Path cpu0 = root.resolve("cpu0");
        for (int node = 1; node <= 256; node++) {
            Files.createDirectories(cpu0.resolve("node" + node));
        }

        CpuTopology topology = CpuTopology.discoverAt(root);
        assertEquals(3, topology.localityRank(0, 1),
                "overlarge NUMA alias sets must not manufacture same-node locality");
    }

    @Test
    void regionTaskPrefersKnownNumaAndFallsBackWithoutTopology() {
        CpuTopology topology = CpuTopology.of(
                new CpuTopology.Location(10, 12, 0, 3, 0),
                new CpuTopology.Location(11, 13, 1, 3, 0),
                new CpuTopology.Location(12, 13, 1, 3, 0),
                new CpuTopology.Location(13, -1, -1));
        int[] targetCpus = {10, 11, 12, 13};
        java.util.function.IntUnaryOperator cpuAt = lane -> targetCpus[lane];

        assertEquals(0, topology.preferredSlotForNuma(0, 0, 4, cpuAt));
        int chosen = topology.preferredSlotForNuma(0, 1, 4, cpuAt);
        assertTrue(chosen == 1 || chosen == 2);
        assertEquals(chosen, topology.preferredSlotForNuma(0, 1, 4, cpuAt),
                "same Task key retains a stable physical carrier preference");
        int other = topology.preferredSlotForNuma(2, 1, 4, cpuAt);
        assertTrue(other == 1 || other == 2);
        assertEquals(0, topology.preferredSlotForNuma(0, 55, 4, cpuAt));
        assertEquals(3, topology.preferredSlotForNuma(-1, -1, 4, cpuAt));
        assertEquals(3, CpuTopology.empty().preferredSlotForNuma(
                Integer.MIN_VALUE + 3, 1, 4, cpuAt));
        assertThrows(IllegalArgumentException.class,
                () -> topology.preferredSlotForNuma(1, -2, 4, cpuAt));
        assertThrows(IllegalArgumentException.class,
                () -> topology.preferredSlotForNuma(1, 0, 0, cpuAt));
        // Optional NUMA must not reject ordinary existing soft affinity
        // pools just because the topology discovery cap is smaller.
        assertEquals(Math.floorMod(Integer.MIN_VALUE, 8193),
                topology.preferredSlotForNuma(
                        Integer.MIN_VALUE, CpuTopology.UNKNOWN, 8193, cpuAt));
        assertEquals(Math.floorMod(-55, 8193),
                topology.preferredSlotForNuma(-55, 1, 8193, cpuAt));
        assertThrows(IllegalArgumentException.class,
                () -> topology.preferredSlotForNuma(1, 65536, 4, cpuAt));
    }

    @Test
    void largeNumaPoolProbesAtMost64CarrierSlots() {
        CpuTopology topology = CpuTopology.of(
                new CpuTopology.Location(10, -1, 1),
                new CpuTopology.Location(11, -1, 2));
        java.util.concurrent.atomic.AtomicInteger probes =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.IntUnaryOperator targets = slot -> {
            if (probes.incrementAndGet() > 64) {
                throw new AssertionError("Task scheduling scanned unbounded carriers");
            }
            return slot == 63 ? 10 : 11;
        };
        assertEquals(63, topology.preferredSlotForNuma(0, 1, 8192, targets));
        assertEquals(64, probes.get());

        probes.set(0);
        assertEquals(0, topology.preferredSlotForNuma(0, 55, 8192, targets));
        assertEquals(64, probes.get(),
                "missing NUMA node must not scan all 8192 carriers");
    }

    @Test
    void stableNumaPlacementDoesNotHotspotTheFirstLocalSlot() {
        CpuTopology topology = CpuTopology.of(
                new CpuTopology.Location(100, -1, 0),
                new CpuTopology.Location(101, -1, 1));
        int[] hits = new int[8];
        // Most hashed starting slots target NUMA 1. The old first-match
        // search collapsed all those keys onto NUMA 0 slot zero.
        java.util.function.IntUnaryOperator cpuAt = slot -> slot < 8 ? 100 : 101;
        for (int key = 0; key < 4096; key++) {
            int selected = topology.preferredSlotForNuma(key, 0, 64, cpuAt);
            assertTrue(selected >= 0 && selected < 8);
            assertEquals(selected, topology.preferredSlotForNuma(key, 0, 64, cpuAt));
            hits[selected]++;
        }
        for (int count : hits) {
            assertTrue(count > 300 && count < 800,
                    "stable NUMA keys should use all local workers, not one boundary");
        }
    }

    @Test
    void fineCpuRankSeparatesCoreL2LlcNumaAndRemote() {
        CpuTopology topology = CpuTopology.of(
                new CpuTopology.Location(0, 30, 0, 3, 0, 20, 10, 64),
                new CpuTopology.Location(1, 30, 0, 3, 0, 20, 10, 64),
                new CpuTopology.Location(2, 30, 0, 3, 0, 20, 11, 64),
                new CpuTopology.Location(3, 30, 0, 3, 0, 21, 12, 64),
                new CpuTopology.Location(4, 40, 0, 3, 0, 22, 13, 64),
                new CpuTopology.Location(5, 50, 1, 3, 1, 23, 14, 128));

        assertEquals(0, topology.cpuAffinityRank(0, 0));
        assertEquals(1, topology.cpuAffinityRank(0, 1),
                "same package/core is the nearest non-identical CPU");
        assertEquals(2, topology.cpuAffinityRank(0, 2),
                "verified shared L2 outranks LLC-only placement");
        assertEquals(3, topology.cpuAffinityRank(0, 3));
        assertEquals(4, topology.cpuAffinityRank(0, 4));
        assertEquals(5, topology.cpuAffinityRank(0, 5));
        assertEquals(5, topology.cpuAffinityRank(0, 99));
        assertEquals(64, topology.cacheLineBytes(0));
        assertEquals(128, topology.cacheLineBytes(5));
        assertEquals(CpuTopology.UNKNOWN, topology.cacheLineBytes(99));
    }

    @Test
    void fineTopologyRequiresQualifiedPackageAndValidLineSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new CpuTopology.Location(0, 3, 0, 3, -1, 2, -1, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new CpuTopology.Location(0, 3, 0, 3, -1, -1, 7, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new CpuTopology.Location(0, 3, 0, 3, 0, 2, 7, 63));
        assertThrows(IllegalArgumentException.class,
                () -> new CpuTopology.Location(0, 3, 0, 3, 0, 2, 7, 8192));
    }

    @Test
    void discoversCoreL2AndCacheLineWithoutInventingConflictingEvidence(
            @TempDir Path root) throws IOException {
        for (int cpu = 0; cpu < 4; cpu++) {
            cpuFixture(root, cpu, "0", "Unified", "3", "30", "0");
            Path topology = root.resolve("cpu" + cpu + "/topology");
            Files.writeString(topology.resolve("core_id"), cpu < 2 ? "10" : "11");

            Path l2 = root.resolve("cpu" + cpu + "/cache/index1");
            Files.createDirectories(l2);
            Files.writeString(l2.resolve("level"), "2");
            Files.writeString(l2.resolve("id"), cpu < 3 ? "20" : "21");
            Files.writeString(l2.resolve("type"), "Unified");
            Files.writeString(l2.resolve("coherency_line_size"), "64");

            Files.writeString(root.resolve("cpu" + cpu + "/cache/index0/coherency_line_size"),
                    "64");
        }

        CpuTopology topology = CpuTopology.discoverAt(root);
        assertEquals(1, topology.cpuAffinityRank(0, 1));
        assertEquals(2, topology.cpuAffinityRank(0, 2));
        assertEquals(3, topology.cpuAffinityRank(0, 3));
        assertEquals(64, topology.cacheLineBytes(0));

        // Conflicting line sizes degrade only the cache-line fact; locality
        // evidence remains independently usable.
        Files.writeString(root.resolve("cpu2/cache/index1/coherency_line_size"), "128");
        topology = CpuTopology.discoverAt(root);
        assertEquals(CpuTopology.UNKNOWN, topology.cacheLineBytes(2));
        assertEquals(2, topology.cpuAffinityRank(0, 2));

        // Conflicting same-level L2 IDs for one CPU fail closed for L2 only.
        Path duplicateL2 = root.resolve("cpu2/cache/index2");
        Files.createDirectories(duplicateL2);
        Files.writeString(duplicateL2.resolve("level"), "2");
        Files.writeString(duplicateL2.resolve("id"), "99");
        Files.writeString(duplicateL2.resolve("type"), "Unified");
        Files.writeString(duplicateL2.resolve("coherency_line_size"), "128");
        topology = CpuTopology.discoverAt(root);
        assertEquals(3, topology.cpuAffinityRank(0, 2),
                "ambiguous L2 identity must fall back to qualified LLC");
    }

    @Test
    void cpuPreferenceRanksExactCoreAboveCacheAndNumaWithBoundedProbes() {
        CpuTopology topology = CpuTopology.of(
                new CpuTopology.Location(20, 7, 0, 3, 0),
                new CpuTopology.Location(21, 7, 0, 3, 0),
                new CpuTopology.Location(22, 8, 0, 3, 0),
                new CpuTopology.Location(23, 7, 1, 3, 1),
                new CpuTopology.Location(24, 7, 0, 3, 1),
                new CpuTopology.Location(30, 7, 0, 3, 0));
        int[] cpus = {22, 21, 20, 23, 24};
        java.util.function.IntUnaryOperator cpuAt = slot -> cpus[slot];
        assertEquals(2, topology.preferredSlotForCpu(0, 20, cpus.length, cpuAt),
                "a same-core target beats same LLC");
        int cachePeer = topology.preferredSlotForCpu(0, 30, cpus.length, cpuAt);
        assertTrue(cachePeer == 1 || cachePeer == 2,
                "same package and cache beats merely same NUMA");
        assertEquals(0, topology.preferredSlotForCpu(0, CpuTopology.UNKNOWN,
                cpus.length, cpuAt));
        assertEquals(3, CpuTopology.empty().preferredSlotForCpu(
                3, 30, cpus.length, cpuAt),
                "unknown topology must preserve hashed fallback");
        assertThrows(IllegalArgumentException.class,
                () -> topology.preferredSlotForCpu(0, -2, 3, cpuAt));
        assertThrows(IllegalArgumentException.class,
                () -> topology.preferredSlotForCpu(0, 3, 0, cpuAt));

        var probes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.IntUnaryOperator count = slot -> {
            if (probes.incrementAndGet() > 64) {
                throw new AssertionError("unbounded CPU locality scan");
            }
            return slot == 63 ? 20 : 23;
        };
        assertEquals(63, topology.preferredSlotForCpu(0, 20, 8192, count));
        assertEquals(64, probes.get());
        probes.set(0);
        assertEquals(0, topology.preferredSlotForCpu(0, 20, 8193, count));
        assertEquals(0, probes.get());
    }

    @Test
    void duplicateLogicalCpuIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CpuTopology.of(
                new CpuTopology.Location(0, 1, 0),
                new CpuTopology.Location(0, 2, 0)));
    }
}
