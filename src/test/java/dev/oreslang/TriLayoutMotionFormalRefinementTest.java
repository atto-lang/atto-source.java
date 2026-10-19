package dev.oreslang;

import dev.oreslang.runtime.TriLayoutMotionStore;
import dev.oreslang.runtime.TriLayoutMotionStore.Handle;
import dev.oreslang.runtime.TriLayoutMotionStore.Layout;
import dev.oreslang.runtime.TriLayoutMotionStore.Motion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Finite trace checks over the real Java HOST reference, not guest lowering. */
final class TriLayoutMotionFormalRefinementTest {
    private static void assertAllLayouts(TriLayoutMotionStore store, Handle[] handles, Motion[] model) {
        long epoch = store.epoch();
        for (Layout layout : Layout.values()) {
            for (int row = 0; row < handles.length; ++row) {
                assertEquals(model[row], store.get(handles[row], layout));
            }
            double sum = 0;
            for (Motion row : model) sum += row.x();
            assertEquals(sum, store.sumX(layout));
            assertEquals(epoch, store.materializedEpoch(layout));
            assertEquals(epoch, store.epoch(), "reads may refresh caches but must not commit");
        }
    }

    @Test void exhaustiveTwoWritesAcrossAllThreeLayoutsAndTileBoundary() {
        // 3*3 authority transitions, 3*3 row pairs. Row 2 straddles tile=2.
        for (Layout first : Layout.values()) {
            for (Layout second : Layout.values()) {
                for (int rowA = 0; rowA < 3; rowA++) {
                    for (int rowB = 0; rowB < 3; rowB++) {
                        try (var store = new TriLayoutMotionStore(3, 2)) {
                            Handle[] handles = new Handle[3];
                            Motion[] model = new Motion[] {
                                    new Motion(1, 2, 3, 4),
                                    new Motion(5, 6, 7, 8),
                                    new Motion(9, 10, 11, 12) };
                            for (int i = 0; i < 3; i++) handles[i] = store.insert(model[i]);
                            Motion a = new Motion(101 + rowA, 201 + rowA, 3, 4);
                            store.set(handles[rowA], first, a);
                            model[rowA] = a;
                            assertEquals(first, store.authoritativeLayout());
                            Motion b = new Motion(301 + rowB, 401 + rowB, 5, 6);
                            store.set(handles[rowB], second, b);
                            model[rowB] = b;
                            assertEquals(second, store.authoritativeLayout());
                            assertAllLayouts(store, handles, model);
                        }
                    }
                }
            }
        }
    }

    @Test void swapEraseAcrossAoSoaTilePreservesMovedSurvivorHandle() {
        for (Layout source : Layout.values()) {
            for (Layout read : Layout.values()) {
                try (var store = new TriLayoutMotionStore(3, 2)) {
                    Handle a = store.insert(new Motion(1, 2, 3, 4));
                    Handle deleted = store.insert(new Motion(5, 6, 7, 8));
                    Handle survivor = store.insert(new Motion(9, 10, 11, 12));
                    store.set(survivor, source, new Motion(17, 18, 19, 20));
                    store.eraseSwap(deleted);
                    assertEquals(new Motion(17, 18, 19, 20), store.get(survivor, read));
                    assertEquals(new Motion(1, 2, 3, 4), store.get(a, read));
                    assertThrows(IllegalStateException.class, () -> store.get(deleted, read));
                    Handle replacement = store.insert(new Motion(29, 30, 31, 32));
                    assertEquals(deleted.slot(), replacement.slot());
                    assertNotEquals(deleted.generation(), replacement.generation());
                    assertThrows(IllegalStateException.class, () -> store.get(deleted, read));
                    for (Layout l : Layout.values()) {
                        assertEquals(new Motion(29, 30, 31, 32), store.get(replacement, l));
                        assertEquals(new Motion(17, 18, 19, 20), store.get(survivor, l));
                    }
                }
            }
        }
    }

    @Test void detachedSnapshotsDoNotChangeAfterOtherLayoutWrites() {
        for (Layout snapshotLayout : Layout.values()) {
            for (Layout writeLayout : Layout.values()) {
                try (var store = new TriLayoutMotionStore(3, 2)) {
                    Handle h = store.insert(new Motion(1, 2, 3, 4));
                    var old = store.snapshot(snapshotLayout);
                    store.set(h, writeLayout, new Motion(5, 6, 7, 8));
                    assertEquals(new Motion(1, 2, 3, 4), old.get(0));
                    assertEquals(new Motion(5, 6, 7, 8), store.get(h, snapshotLayout));
                }
            }
        }
    }
}
