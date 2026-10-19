package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CpuTopologyTest {
    @Test
    void ranksSameCpuThenLlcThenNumaThenRemote() {
        CpuTopology topology = CpuTopology.of(
                new CpuTopology.Location(0, 10, 0),
                new CpuTopology.Location(1, 10, 0),
                new CpuTopology.Location(2, 20, 0),
                new CpuTopology.Location(3, 30, 1));

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
    void duplicateLogicalCpuIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CpuTopology.of(
                new CpuTopology.Location(0, 1, 0),
                new CpuTopology.Location(0, 2, 0)));
    }
}
