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
        Files.writeString(cache.resolve("type"), "Unified\u0000Spoofed");
        topology = CpuTopology.discoverAt(root);
        assertEquals(2, topology.localityRank(0, 1));

        Files.writeString(cache.resolve("type"), "Unified");
        topology = CpuTopology.discoverAt(root);
        assertEquals(1, topology.localityRank(0, 1));
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
        assertEquals(1, topology.preferredSlotForNuma(0, 1, 4, cpuAt));
        assertEquals(2, topology.preferredSlotForNuma(2, 1, 4, cpuAt));
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
    void duplicateLogicalCpuIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CpuTopology.of(
                new CpuTopology.Location(0, 1, 0),
                new CpuTopology.Location(0, 2, 0)));
    }
}
