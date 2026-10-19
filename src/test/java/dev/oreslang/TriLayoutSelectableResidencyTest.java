package dev.oreslang;

import dev.oreslang.runtime.TriLayoutMotionStore;
import dev.oreslang.runtime.TriLayoutMotionStore.Handle;
import dev.oreslang.runtime.TriLayoutMotionStore.Layout;
import dev.oreslang.runtime.TriLayoutMotionStore.Motion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Host-level executable refinement for the seven *actual* allocation sets. */
final class TriLayoutSelectableResidencyTest {
    private static List<EnumSet<Layout>> configurations() {
        var result = new ArrayList<EnumSet<Layout>>();
        for (int mask = 1; mask < 8; mask++) {
            var resident = EnumSet.noneOf(Layout.class);
            for (Layout l : Layout.values()) {
                if ((mask & (1 << l.ordinal())) != 0) resident.add(l);
            }
            result.add(resident);
        }
        return result;
    }

    @Test void allSevenAllocateOnlyRequestedBuffersAndFailClosed() {
        var configurations = configurations();
        assertEquals(7, configurations.size());
        for (var resident : configurations) {
            for (Layout first : resident) {
                try (var store = new TriLayoutMotionStore(3, 2, resident, first,
                         Long.MAX_VALUE)) {
                    assertEquals(resident, store.residentLayouts());
                    assertEquals(first, store.authoritativeLayout());
                    assertEquals(1L, store.epoch());
                    assertTrue(store.reservedPayloadBytes() > 0);
                    assertThrows(UnsupportedOperationException.class, () ->
                            store.residentLayouts().clear());
                    Handle h = store.insert(new Motion(1, 2, 3, 4));
                    for (Layout l : Layout.values()) {
                        assertEquals(resident.contains(l), store.hasLayout(l));
                        if (resident.contains(l)) {
                            assertEquals(new Motion(1, 2, 3, 4), store.get(h, l));
                            assertEquals(store.epoch(), store.materializedEpoch(l));
                        } else {
                            long before = store.epoch();
                            assertThrows(IllegalArgumentException.class,
                                    () -> store.get(h, l));
                            assertThrows(IllegalArgumentException.class,
                                    () -> store.materializedEpoch(l));
                            assertThrows(IllegalArgumentException.class,
                                    () -> store.set(h, l, new Motion(9, 9, 9, 9)));
                            assertThrows(IllegalArgumentException.class,
                                    () -> store.integrate(l, 1));
                            assertThrows(IllegalArgumentException.class,
                                    () -> store.sumX(l));
                            assertThrows(IllegalArgumentException.class,
                                    () -> store.snapshot(l));
                            assertEquals(before, store.epoch());
                            assertEquals(new Motion(1, 2, 3, 4), store.get(h, first));
                        }
                    }
                }
            }
        }
    }

    @Test void physicallyAbsentBuffersStayUnallocated() throws ReflectiveOperationException {
        var aos = TriLayoutMotionStore.class.getDeclaredField("aos");
        var soa = TriLayoutMotionStore.class.getDeclaredField("soa");
        var aosoa = TriLayoutMotionStore.class.getDeclaredField("aosoa");
        aos.setAccessible(true);
        soa.setAccessible(true);
        aosoa.setAccessible(true);
        for (var resident : configurations()) {
            try (var region = new TriLayoutMotionStore(3, 2, resident)) {
                assertEquals(resident.contains(Layout.AOS), aos.get(region) != null);
                assertEquals(resident.contains(Layout.SOA), soa.get(region) != null);
                assertEquals(resident.contains(Layout.AOSOA), aosoa.get(region) != null);
                if (resident.contains(Layout.AOS)) {
                    assertEquals(12, ((double[]) aos.get(region)).length);
                }
                if (resident.contains(Layout.SOA)) {
                    var columns = (double[][]) soa.get(region);
                    assertEquals(4, columns.length);
                    for (double[] column : columns) assertEquals(3, column.length);
                }
                if (resident.contains(Layout.AOSOA)) {
                    assertEquals(16, ((double[]) aosoa.get(region)).length);
                }
            }
        }
    }

    @Test void allSevenCrossLayoutWriteSequencesAndTileBoundaries() {
        for (var resident : configurations()) {
            for (Layout first : resident) {
                for (Layout second : resident) {
                    for (int updatedA = 0; updatedA < 3; updatedA++) {
                        for (int updatedB = 0; updatedB < 3; updatedB++) {
                            try (var store = new TriLayoutMotionStore(
                                    3, 2, resident, first, Long.MAX_VALUE)) {
                                Handle[] rows = new Handle[3];
                                Motion[] model = {
                                        new Motion(1, 2, 3, 4),
                                        new Motion(5, 6, 7, 8),
                                        new Motion(9, 10, 11, 12)
                                };
                                for (int i = 0; i < 3; i++) rows[i] = store.insert(model[i]);
                                var a = new Motion(101 + updatedA, 201 + updatedA, 3, 4);
                                store.set(rows[updatedA], first, a);
                                model[updatedA] = a;
                                var b = new Motion(301 + updatedB, 401 + updatedB, 5, 6);
                                store.set(rows[updatedB], second, b);
                                model[updatedB] = b;
                                assertEquals(second, store.authoritativeLayout());
                                long epoch = store.epoch();
                                for (Layout l : resident) {
                                    double sum = 0;
                                    for (int i = 0; i < 3; i++) {
                                        assertEquals(model[i], store.get(rows[i], l));
                                        sum += model[i].x();
                                    }
                                    assertEquals(sum, store.sumX(l));
                                    assertEquals(epoch, store.materializedEpoch(l));
                                }
                                assertEquals(epoch, store.epoch());
                            }
                        }
                    }
                }
            }
        }
    }

    @Test void stableHandlesSurviveSwapDeleteAcrossAllResidentLayouts() {
        for (var resident : configurations()) {
            Layout authority = resident.iterator().next();
            try (var region = new TriLayoutMotionStore(3, 2, resident, authority, Long.MAX_VALUE);
                 var other = new TriLayoutMotionStore(3, 2, resident, authority, Long.MAX_VALUE)) {
                Handle a = region.insert(new Motion(1, 2, 3, 4));
                Handle erased = region.insert(new Motion(5, 6, 7, 8));
                Handle survivor = region.insert(new Motion(9, 10, 11, 12));
                region.eraseSwap(erased);
                for (Layout l : resident) {
                    assertEquals(new Motion(9, 10, 11, 12), region.get(survivor, l));
                    assertEquals(new Motion(1, 2, 3, 4), region.get(a, l));
                    assertThrows(IllegalStateException.class, () -> region.get(erased, l));
                    assertThrows(IllegalArgumentException.class, () -> other.get(a, l));
                }
                Handle newer = region.insert(new Motion(13, 14, 15, 16));
                assertEquals(erased.slot(), newer.slot());
                assertNotEquals(erased.generation(), newer.generation());
                for (Layout l : resident) {
                    assertEquals(new Motion(13, 14, 15, 16), region.get(newer, l));
                    assertThrows(IllegalStateException.class, () -> region.get(erased, l));
                }
                List<Motion> detached = region.snapshot(authority);
                region.set(newer, authority, new Motion(20, 21, 22, 23));
                assertEquals(new Motion(13, 14, 15, 16), detached.get(2));
            }
        }
    }

    @Test void budgetCountsOnlyResidentPhysicalPayloadAndRejectsBeforeMutation() {
        for (var resident : configurations()) {
            Layout first = resident.iterator().next();
            long needed;
            try (var s = new TriLayoutMotionStore(3, 2, resident, first, Long.MAX_VALUE)) {
                needed = s.reservedPayloadBytes();
                assertEquals(resident, s.residentLayouts());
            }
            try (var s = new TriLayoutMotionStore(3, 2, resident, first, needed)) {
                assertEquals(needed, s.reservedPayloadBytes());
            }
            long smaller = needed - 1;
            assertThrows(IllegalArgumentException.class, () ->
                    new TriLayoutMotionStore(3, 2, resident, first, smaller));
            assertThrows(IllegalArgumentException.class, () ->
                    new TriLayoutMotionStore(3, 2, resident, first, -1));
        }
        try (var aos = new TriLayoutMotionStore(3, 2, Set.of(Layout.AOS));
             var soa = new TriLayoutMotionStore(3, 2, Set.of(Layout.SOA));
             var tiled = new TriLayoutMotionStore(3, 2, Set.of(Layout.AOSOA));
             var all = new TriLayoutMotionStore(3, 2)) {
            assertEquals(aos.reservedPayloadBytes(), soa.reservedPayloadBytes());
            assertTrue(tiled.reservedPayloadBytes() > aos.reservedPayloadBytes());
            assertTrue(all.reservedPayloadBytes() > tiled.reservedPayloadBytes());
        }
        assertThrows(IllegalArgumentException.class, () ->
                new TriLayoutMotionStore(3, 2, Set.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new TriLayoutMotionStore(3, 2, Set.of(Layout.SOA), Layout.AOS, 4096));
        assertThrows(IllegalArgumentException.class, () ->
                new TriLayoutMotionStore(Integer.MAX_VALUE, 1024,
                        Set.of(Layout.AOSOA), Layout.AOSOA, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () ->
                new TriLayoutMotionStore(3, 2, Set.of(Layout.AOS), Layout.AOS, 0));
    }

    @Test void seededDifferentialMutationsForAllSevenResidencies() {
        int config = 0;
        for (var resident : configurations()) {
            var rand = new Random(0xD0A2026L + config++);
            var selected = List.copyOf(resident);
            try (var region = new TriLayoutMotionStore(23, 4, resident,
                    selected.get(0), Long.MAX_VALUE)) {
                var live = new ArrayList<Handle>();
                var logical = new HashMap<Handle, Motion>();
                for (int step = 0; step < 1200; step++) {
                    Layout layout = selected.get(rand.nextInt(selected.size()));
                    int op = rand.nextInt(4);
                    if (live.isEmpty() || (op == 0 && live.size() < 23)) {
                        var m = new Motion(rand.nextInt(50), rand.nextInt(50),
                                rand.nextInt(12), rand.nextInt(12));
                        Handle h = region.insert(m);
                        live.add(h);
                        logical.put(h, m);
                    } else if (op == 1) {
                        Handle h = live.remove(rand.nextInt(live.size()));
                        region.eraseSwap(h);
                        logical.remove(h);
                        assertThrows(IllegalStateException.class, () -> region.get(h, layout));
                    } else if (op == 2) {
                        Handle h = live.get(rand.nextInt(live.size()));
                        var m = new Motion(rand.nextInt(50), rand.nextInt(50),
                                rand.nextInt(12), rand.nextInt(12));
                        region.set(h, layout, m);
                        logical.put(h, m);
                    } else {
                        region.integrate(layout, 0.25);
                        logical.replaceAll((h, m) ->
                                new Motion(m.x() + m.vx() * 0.25,
                                        m.y() + m.vy() * 0.25, m.vx(), m.vy()));
                    }
                    if (step % 19 == 0) {
                        long epoch = region.epoch();
                        for (Layout check : selected) {
                            double sum = 0;
                            for (Handle h : live) {
                                Motion expected = logical.get(h);
                                assertEquals(expected, region.get(h, check));
                                sum += expected.x();
                            }
                            assertEquals(sum, region.sumX(check));
                            assertEquals(epoch, region.materializedEpoch(check));
                        }
                    }
                }
            }
        }
    }
}
