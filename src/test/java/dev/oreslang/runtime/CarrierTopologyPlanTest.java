package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

class CarrierTopologyPlanTest {
    @Test void ranksCoreThenL2ThenLlcThenNumaThenRemote() {
        CarrierTopologyPlan p = new CarrierTopologyPlan(new CarrierTopologyPlan.Location[]{
                new CarrierTopologyPlan.Location(0, 0, 10, 20, 30, 0),
                new CarrierTopologyPlan.Location(1, 0, 10, 20, 30, 0),
                new CarrierTopologyPlan.Location(2, 0, 11, 20, 30, 0),
                new CarrierTopologyPlan.Location(3, 0, 12, 21, 30, 0),
                new CarrierTopologyPlan.Location(4, 0, 13, 22, 31, 0),
                new CarrierTopologyPlan.Location(16, 1, 10, 20, 30, 1),
                CarrierTopologyPlan.Location.UNKNOWN
        });
        assertEquals(0, p.tier(0, 1));
        assertEquals(1, p.tier(0, 2));
        assertEquals(2, p.tier(0, 3));
        assertEquals(3, p.tier(0, 4));
        // Cache/core IDs alone are not globally unique across packages.
        assertEquals(4, p.tier(0, 5));
        assertEquals(4, p.tier(0, 6));
        assertEquals(1, p.victimAt(0, 0, 0));
        assertEquals(2, p.victimAt(0, 1, 0));
        for (int rotation = 0; rotation < 37; rotation++) {
            boolean[] seen = new boolean[7];
            for (int rank = 0; rank < 6; rank++) {
                int candidate = p.victimAt(0, rank, rotation);
                assertNotEquals(0, candidate);
                assertFalse(seen[candidate]);
                seen[candidate] = true;
            }
            for (int i = 1; i < 7; i++) assertTrue(seen[i]);
        }
    }

    @Test void unknownTopologyDoesNotInventAffinityAndRotatesFairly() {
        var u = CarrierTopologyPlan.Location.UNKNOWN;
        var p = new CarrierTopologyPlan(new CarrierTopologyPlan.Location[]{u, u, u, u});
        for (int i = 1; i < 4; i++) assertEquals(4, p.tier(0, i));
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

    @Test void discoversCoreL2LlcAndNumaAndFailsSoftOnAmbiguity(@TempDir Path root)
            throws Exception {
        Path cpu = root.resolve("cpu7");
        Path topo = Files.createDirectories(cpu.resolve("topology"));
        Path l2 = Files.createDirectories(cpu.resolve("cache/index2"));
        Path l3 = Files.createDirectories(cpu.resolve("cache/index3"));
        Files.createDirectories(cpu.resolve("node2"));
        Files.writeString(topo.resolve("physical_package_id"), "4\n");
        Files.writeString(topo.resolve("core_id"), "6\n");
        Files.writeString(l2.resolve("level"), "2\n");
        Files.writeString(l2.resolve("type"), "Unified\n");
        Files.writeString(l2.resolve("id"), "9\n");
        Files.writeString(l3.resolve("level"), "3\n");
        Files.writeString(l3.resolve("type"), "Unified\n");
        Files.writeString(l3.resolve("id"), "11\n");
        assertEquals(new CarrierTopologyPlan.Location(7, 4, 6, 9, 11, 2),
                CarrierTopologyPlan.discoverAt(7, root));

        Files.writeString(l2.resolve("type"), "Instruction\n");
        assertEquals(-1, CarrierTopologyPlan.discoverAt(7, root).l2Id());
        assertEquals(11, CarrierTopologyPlan.discoverAt(7, root).llcId());
        Files.writeString(l2.resolve("type"), "Unified\n");

        Path duplicateL2 = Files.createDirectories(cpu.resolve("cache/index1"));
        Files.writeString(duplicateL2.resolve("level"), "2\n");
        Files.writeString(duplicateL2.resolve("type"), "Unified\n");
        Files.writeString(duplicateL2.resolve("id"), "99\n");
        assertEquals(-1, CarrierTopologyPlan.discoverAt(7, root).l2Id());
        assertEquals(11, CarrierTopologyPlan.discoverAt(7, root).llcId(),
                "ambiguous L2 should not discard independently valid LLC");

        Files.writeString(topo.resolve("physical_package_id"), "not an integer");
        var unknownPackage = CarrierTopologyPlan.discoverAt(7, root);
        assertEquals(-1, unknownPackage.packageId());
        assertEquals(-1, unknownPackage.coreId());
        assertEquals(-1, unknownPackage.l2Id());
        assertEquals(-1, unknownPackage.llcId());
        assertEquals(2, unknownPackage.numaNode());

        Files.writeString(topo.resolve("physical_package_id"), "4\n");
        Files.writeString(l3.resolve("type"), "X".repeat(1024));
        assertEquals(-1, CarrierTopologyPlan.discoverAt(7, root).llcId(),
                "oversized sysfs text cannot create cache evidence");
        assertEquals(CarrierTopologyPlan.Location.UNKNOWN,
                CarrierTopologyPlan.discoverAt(-1, root));
    }

    @Test void ambiguousNumaMembershipFailsClosed(@TempDir Path root) throws Exception {
        Path cpu = root.resolve("cpu2");
        Path topo = Files.createDirectories(cpu.resolve("topology"));
        Files.writeString(topo.resolve("physical_package_id"), "0\n");
        Files.writeString(topo.resolve("core_id"), "1\n");
        Files.createDirectories(cpu.resolve("node0"));
        Files.createDirectories(cpu.resolve("node1"));
        assertEquals(-1, CarrierTopologyPlan.discoverAt(2, root).numaNode());
    }

    @Test void cacheIdentitiesRequirePhysicalPackageQualifier() {
        assertThrows(IllegalArgumentException.class,
                () -> new CarrierTopologyPlan.Location(1, -1, 1, 2, 3, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CarrierTopologyPlan.Location(-1, 0, -1, -1, -1, -1));
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
