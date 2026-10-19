package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CarrierTopologyPlanTest {
    @Test void ranksCacheBeforeNumaBeforeRemote() {
        CarrierTopologyPlan p = new CarrierTopologyPlan(new CarrierTopologyPlan.Location[]{
                new CarrierTopologyPlan.Location(0, 0, 12, 0),
                new CarrierTopologyPlan.Location(2, 0, 12, 0),
                new CarrierTopologyPlan.Location(4, 0, 14, 0),
                new CarrierTopologyPlan.Location(16, 1, 12, 1),
                CarrierTopologyPlan.Location.UNKNOWN
        });
        assertEquals(0, p.tier(0, 1));
        assertEquals(1, p.tier(0, 2));
        // LLC IDs alone are not globally unique across packages.
        assertEquals(2, p.tier(0, 3));
        assertEquals(2, p.tier(0, 4));
        assertEquals(1, p.victimAt(0, 0, 0));
        assertEquals(2, p.victimAt(0, 1, 0));
        for (int rotation = 0; rotation < 37; rotation++) {
            boolean[] seen = new boolean[5];
            for (int rank = 0; rank < 4; rank++) {
                int candidate = p.victimAt(0, rank, rotation);
                assertNotEquals(0, candidate);
                assertFalse(seen[candidate]);
                seen[candidate] = true;
            }
            for (int i = 1; i < 5; i++) assertTrue(seen[i]);
        }
    }

    @Test void unknownTopologyDoesNotInventAffinityAndRotatesFairly() {
        var u = CarrierTopologyPlan.Location.UNKNOWN;
        var p = new CarrierTopologyPlan(new CarrierTopologyPlan.Location[]{u, u, u, u});
        for (int i = 1; i < 4; i++) assertEquals(2, p.tier(0, i));
        assertEquals(1, p.victimAt(0, 0, 0));
        assertEquals(2, p.victimAt(0, 0, 1));
        assertEquals(3, p.victimAt(0, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> p.victimAt(0, 3, 0));
    }

    @Test void oneWorkerAndInvalidMetadataAreFailSafe() {
        var p = new CarrierTopologyPlan(new CarrierTopologyPlan.Location[]{
                CarrierTopologyPlan.Location.UNKNOWN
        });
        assertEquals(1, p.workerCount());
        assertThrows(IllegalArgumentException.class, () -> p.victimAt(0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CarrierTopologyPlan(new CarrierTopologyPlan.Location[]{null}));
        assertThrows(IllegalArgumentException.class,
                () -> new CarrierTopologyPlan(new CarrierTopologyPlan.Location[]{}));
        assertEquals(CarrierTopologyPlan.Location.UNKNOWN, CarrierTopologyPlan.discover(-1));
    }

    @Test void virtualWorkerResizePreservesAllVictimsAcrossTiers() {
        for (int workers = 2; workers <= 64; workers++) {
            CarrierTopologyPlan.Location[] loc = new CarrierTopologyPlan.Location[workers];
            for (int i = 0; i < workers; i++) {
                loc[i] = new CarrierTopologyPlan.Location(i, i / 16, i / 4, i / 16);
            }
            CarrierTopologyPlan p = new CarrierTopologyPlan(loc);
            for (int thief = 0; thief < workers; thief++) {
                boolean[] visited = new boolean[workers];
                for (int ordinal = 0; ordinal < workers - 1; ordinal++) {
                    int v = p.victimAt(thief, ordinal, thief * 17 + 3);
                    assertNotEquals(thief, v);
                    assertFalse(visited[v], "duplicate locality victim");
                    visited[v] = true;
                }
            }
        }
    }
}
