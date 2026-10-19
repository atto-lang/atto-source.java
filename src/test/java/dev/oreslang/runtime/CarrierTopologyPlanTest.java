package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

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
    @Test void wraparoundAndGlobalSweepsNeverLoseOrDuplicateVictims() {
        for (int workers = 2; workers <= 64; workers++) {
            CarrierTopologyPlan.Location[] loc = new CarrierTopologyPlan.Location[workers];
            for (int i = 0; i < workers; i++) {
                loc[i] = new CarrierTopologyPlan.Location(i, i / 32, i / 4, i / 16);
            }
            var plan = new CarrierTopologyPlan(loc);
            for (int thief = 0; thief < workers; thief++) {
                for (int rotation : new int[]{
                        Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1, 0, 1,
                        Integer.MAX_VALUE - 1, Integer.MAX_VALUE
                }) {
                    boolean[] tierVisited = new boolean[workers];
                    boolean[] globalVisited = new boolean[workers];
                    for (int rank = 0; rank < workers - 1; rank++) {
                        int tierVictim = plan.victimAt(thief, rank, rotation);
                        int globalVictim = plan.globalVictimAt(thief, rank, rotation);
                        assertNotEquals(thief, tierVictim);
                        assertNotEquals(thief, globalVictim);
                        assertFalse(tierVisited[tierVictim], "overflow repeated a local victim");
                        assertFalse(globalVisited[globalVictim], "overflow repeated a global victim");
                        tierVisited[tierVictim] = true;
                        globalVisited[globalVictim] = true;
                    }
                    for (int i = 0; i < workers; i++) {
                        if (i != thief) {
                            assertTrue(tierVisited[i], "omitted a cache-tier victim");
                            assertTrue(globalVisited[i], "omitted a global victim");
                        }
                    }
                }
            }
        }
    }

    @Test void malformedOrIncompleteLinuxCacheTopologyFailsSoft(@TempDir Path root)
            throws Exception {
        Path cpu = root.resolve("cpu7");
        Path topo = Files.createDirectories(cpu.resolve("topology"));
        Path cache = Files.createDirectories(cpu.resolve("cache/index3"));
        Files.createDirectories(cpu.resolve("node2"));
        Files.writeString(topo.resolve("physical_package_id"), "4\n");
        Files.writeString(cache.resolve("level"), "3\n");
        Files.writeString(cache.resolve("type"), "Unified\n");
        Files.writeString(cache.resolve("id"), "11\n");
        assertEquals(new CarrierTopologyPlan.Location(7, 4, 11, 2),
                CarrierTopologyPlan.discoverAt(7, root));

        Files.writeString(cache.resolve("type"), "Instruction\n");
        assertEquals(-1, CarrierTopologyPlan.discoverAt(7, root).llcId());
        Files.writeString(cache.resolve("type"), "Unified\n");
        Files.writeString(cache.resolve("level"), "2\n");
        assertEquals(-1, CarrierTopologyPlan.discoverAt(7, root).llcId());
        Files.delete(cache.resolve("level"));
        assertEquals(-1, CarrierTopologyPlan.discoverAt(7, root).llcId());
        Files.writeString(topo.resolve("physical_package_id"), "not an integer");
        assertEquals(-1, CarrierTopologyPlan.discoverAt(7, root).packageId());
        assertEquals(CarrierTopologyPlan.Location.UNKNOWN,
                CarrierTopologyPlan.discoverAt(-1, root));
    }

    @Test void boundedGlobalProbesEventuallyVisitEveryRemoteLane() {
        for (int workers = 2; workers <= 128; workers++) {
            CarrierTopologyPlan.Location[] loc = new CarrierTopologyPlan.Location[workers];
            java.util.Arrays.fill(loc, CarrierTopologyPlan.Location.UNKNOWN);
            var plan = new CarrierTopologyPlan(loc);
            for (int thief = 0; thief < workers; thief++) {
                boolean[] visited = new boolean[workers];
                // Model a maximum of eight successful, aged steal probes per
                // periodic global sweep. The cursor advances independently of
                // the 16-poll frequency.
                for (int sweep = 0; sweep < workers; sweep++) {
                    for (int rank = 0; rank < Math.min(workers - 1, 8); rank++) {
                        visited[plan.globalVictimAt(thief, rank, sweep)] = true;
                    }
                }
                for (int victim = 0; victim < workers; victim++)
                    if (victim != thief) assertTrue(visited[victim],
                            "starved remote lane " + victim + " of " + workers);
            }
        }
    }

    @Test void batchTopologyBuildsNeverScaleWithEveryWorker() {
        for (int maximum = 1; maximum <= 512; maximum++) {
            int published = 0;
            for (int observed = 1; observed <= maximum; observed++) {
                if (CarrierTopologyPlan.publishAfterWorker(observed, maximum))
                    published++;
            }
            assertTrue(published <= 11, "unbounded O(worker²) plan rebuilding");
            assertTrue(CarrierTopologyPlan.publishAfterWorker(maximum, maximum));
        }
        assertThrows(IllegalArgumentException.class,
                () -> CarrierTopologyPlan.publishAfterWorker(0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> CarrierTopologyPlan.publishAfterWorker(5, 4));
        assertThrows(IllegalArgumentException.class,
                () -> CarrierTopologyPlan.publishAfterWorker(1, 0));
    }

}
