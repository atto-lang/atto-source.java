package dev.oreslang;

import dev.oreslang.runtime.TriLayoutMotionStore;
import dev.oreslang.runtime.TriLayoutMotionStore.Handle;
import dev.oreslang.runtime.TriLayoutMotionStore.Layout;
import dev.oreslang.runtime.TriLayoutMotionStore.Motion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class TriLayoutMotionStoreTest {
    @Test void switchesAuthorityAndSynchronizesAllThreeViews() {
        try (var store = new TriLayoutMotionStore(16, 4)) {
            Handle a = store.insert(new Motion(1, 2, 3, 4));
            Handle b = store.insert(new Motion(5, 6, 7, 8));
            for (Layout layout : Layout.values()) assertEquals(6.0, store.sumX(layout));
            long before = store.epoch();
            store.integrate(Layout.AOSOA, 2);
            assertEquals(Layout.AOSOA, store.authoritativeLayout());
            assertEquals(before + 1, store.epoch());
            assertNotEquals(store.epoch(), store.materializedEpoch(Layout.SOA));
            for (Layout layout : Layout.values()) {
                assertEquals(new Motion(7, 10, 3, 4), store.get(a, layout));
                assertEquals(new Motion(19, 22, 7, 8), store.get(b, layout));
            }
            store.set(a, Layout.SOA, new Motion(-1, -2, 10, 11));
            for (Layout layout : Layout.values()) assertEquals(-1, store.get(a, layout).x());
            assertEquals(-1 + 19, store.sumX(Layout.AOSOA));
        }
    }

    @Test void swapEraseRetainsSurvivorHandleAndRevokesRemovedAndForeign() {
        try (var store = new TriLayoutMotionStore(3, 2);
             var foreign = new TriLayoutMotionStore(3, 2)) {
            var first = store.insert(new Motion(1, 0, 0, 0));
            var middle = store.insert(new Motion(2, 0, 0, 0));
            var last = store.insert(new Motion(3, 0, 0, 0));
            store.eraseSwap(middle);
            assertEquals(2, store.size());
            for (Layout l : Layout.values()) assertEquals(3, store.get(last, l).x());
            assertThrows(IllegalStateException.class, () -> store.get(middle, Layout.AOS));
            assertThrows(IllegalArgumentException.class, () -> foreign.get(first, Layout.AOS));
            var replacement = store.insert(new Motion(4, 0, 0, 0));
            assertEquals(middle.slot(), replacement.slot());
            assertNotEquals(middle.generation(), replacement.generation());
            assertEquals(1, store.get(first, Layout.AOS).x());
            assertThrows(IllegalStateException.class, () -> store.get(middle, Layout.SOA));
            assertThrows(IllegalStateException.class, () -> store.insert(new Motion(5, 0, 0, 0)));
        }
    }

    @Test void fuzzMixedLayoutMutationsAgainstIndependentRowModel() {
        var random = new Random(0xA05A);
        try (var store = new TriLayoutMotionStore(128, 8)) {
            var live = new ArrayList<Handle>();
            var expected = new HashMap<Handle, Motion>();
            for (int step = 0; step < 2500; step++) {
                int op = random.nextInt(4);
                Layout layout = Layout.values()[random.nextInt(3)];
                if ((op == 0 || live.isEmpty()) && live.size() < 128) {
                    var value = new Motion(random.nextInt(100), random.nextInt(100), random.nextInt(20), random.nextInt(20));
                    var handle = store.insert(value);
                    live.add(handle);
                    expected.put(handle, value);
                } else if (op == 1 && !live.isEmpty()) {
                    int idx = random.nextInt(live.size());
                    var handle = live.remove(idx);
                    store.eraseSwap(handle);
                    expected.remove(handle);
                    assertThrows(IllegalStateException.class, () -> store.get(handle, layout));
                } else if (op == 2 && !live.isEmpty()) {
                    var handle = live.get(random.nextInt(live.size()));
                    var value = new Motion(random.nextInt(100), random.nextInt(100), random.nextInt(20), random.nextInt(20));
                    store.set(handle, layout, value);
                    expected.put(handle, value);
                } else {
                    store.integrate(layout, 0.5);
                    expected.replaceAll((h, v) -> new Motion(v.x() + v.vx() * 0.5, v.y() + v.vy() * 0.5, v.vx(), v.vy()));
                }
                if (step % 47 == 0) {
                    for (Layout check : Layout.values()) {
                        double sum = 0;
                        for (var item : live) {
                            Motion want = expected.get(item);
                            assertEquals(want, store.get(item, check));
                            sum += want.x();
                        }
                        assertEquals(sum, store.sumX(check));
                    }
                }
            }
        }
    }

    @Test void validatesTileOverflowSnapshotIsolationAndClose() {
        assertThrows(IllegalArgumentException.class, () -> new TriLayoutMotionStore(8, 3));
        assertThrows(IllegalArgumentException.class, () -> new TriLayoutMotionStore(Integer.MAX_VALUE, 1024));
        var store = new TriLayoutMotionStore(2, 2);
        var h = store.insert(new Motion(2, 2, 1, 1));
        var snapshot = store.snapshot(Layout.SOA);
        store.integrate(Layout.AOSOA, 1);
        assertEquals(2, snapshot.get(0).x());
        assertEquals(3, store.get(h, Layout.AOS).x());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(new Motion(0, 0, 0, 0)));
        store.close();
        assertThrows(IllegalStateException.class, () -> store.get(h, Layout.SOA));
        assertThrows(IllegalStateException.class, () -> store.integrate(Layout.AOS, 1));
    }
}
