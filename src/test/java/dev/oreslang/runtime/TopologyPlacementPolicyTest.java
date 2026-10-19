package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class TopologyPlacementPolicyTest {
    @Test
    void preferredLaneWinsThenLlcThenNumaThenRemote() {
        var workers = List.of(
                new TopologyPlacementPolicy.Worker(0, 0, 10, 0, true, true),
                new TopologyPlacementPolicy.Worker(1, 1, 10, 0, true, true),
                new TopologyPlacementPolicy.Worker(2, 2, 20, 0, true, true),
                new TopologyPlacementPolicy.Worker(3, 3, 30, 1, true, true));
        var hint = new TopologyPlacementPolicy.Hint(1, 10, 0);

        assertEquals(
                List.of(1, 0, 2, 3),
                TopologyPlacementPolicy.rank(workers, hint).stream()
                        .map(TopologyPlacementPolicy.Worker::lane).toList());
    }

    @Test
    void unhealthyAndCpuSetIneligibleWorkersAreRemovedBeforeRanking() {
        var workers = List.of(
                new TopologyPlacementPolicy.Worker(0, 0, 1, 0, false, true),
                new TopologyPlacementPolicy.Worker(1, 1, 1, 0, true, false),
                new TopologyPlacementPolicy.Worker(2, 2, 2, 1, true, true));

        assertEquals(
                List.of(2),
                TopologyPlacementPolicy.rank(
                        workers,
                        new TopologyPlacementPolicy.Hint(0, 1, 0)).stream()
                        .map(TopologyPlacementPolicy.Worker::lane).toList());
    }

    @Test
    void unknownTopologyFallsBackToDeterministicLaneOrder() {
        var workers = List.of(
                new TopologyPlacementPolicy.Worker(2, -1, -1, -1, true, true),
                new TopologyPlacementPolicy.Worker(0, -1, -1, -1, true, true),
                new TopologyPlacementPolicy.Worker(1, -1, -1, -1, true, true));

        assertEquals(
                List.of(0, 1, 2),
                TopologyPlacementPolicy.rank(
                        workers, TopologyPlacementPolicy.Hint.none()).stream()
                        .map(TopologyPlacementPolicy.Worker::lane).toList());
    }

    @Test
    void invalidTopologyIdsFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                new TopologyPlacementPolicy.Worker(0, -2, 0, 0, true, true));
        assertThrows(IllegalArgumentException.class, () ->
                new TopologyPlacementPolicy.Hint(-2, 0, 0));
    }
}
