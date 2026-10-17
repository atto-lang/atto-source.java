package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class DopRegionScheduleTest {
    @Test
    void checkedRowFieldFootprintsMatchNativeConformance() {
        var r = DopRegionSchedule.Root.trustedCreate(10, 3);
        var other = DopRegionSchedule.Root.trustedCreate(10, 3);
        var read = DopRegionSchedule.Access.rows(r, 0, 2, 6, DopRegionSchedule.Mode.READ);
        var write = DopRegionSchedule.Access.rows(r, 0, 2, 6, DopRegionSchedule.Mode.WRITE);
        var fieldWrite = DopRegionSchedule.Access.rows(r, 1, 2, 6, DopRegionSchedule.Mode.WRITE);
        var touching = DopRegionSchedule.Access.rows(r, 0, 8, 2, DopRegionSchedule.Mode.WRITE);
        var overlapping = DopRegionSchedule.Access.rows(r, 0, 7, 2, DopRegionSchedule.Mode.WRITE);
        var empty = DopRegionSchedule.Access.rows(r, 0, 3, 0, DopRegionSchedule.Mode.WRITE);
        var reduction = DopRegionSchedule.Access.rows(r, 0, 2, 6,
                DopRegionSchedule.Mode.REDUCE, "sum");

        assertTrue(DopRegionSchedule.independent(read, read));
        assertFalse(DopRegionSchedule.independent(read, write));
        assertTrue(DopRegionSchedule.independent(write, fieldWrite));
        assertTrue(DopRegionSchedule.independent(write, touching));
        assertFalse(DopRegionSchedule.independent(write, overlapping));
        assertTrue(DopRegionSchedule.independent(write, empty));
        assertFalse(DopRegionSchedule.independent(reduction, reduction));
        assertTrue(DopRegionSchedule.independent(write,
                DopRegionSchedule.Access.rows(other, 0, 2, 6, DopRegionSchedule.Mode.WRITE)));
    }

    @Test
    void malformedBoundariesAndReductionOperatorsReject() {
        var r = DopRegionSchedule.Root.trustedCreate(10, 3);
        assertThrows(IllegalArgumentException.class,
                () -> DopRegionSchedule.Root.trustedCreate(-1, 3));
        assertThrows(IllegalArgumentException.class,
                () -> DopRegionSchedule.Root.trustedCreate(2, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DopRegionSchedule.Access.rows(r, 3, 0, 1, DopRegionSchedule.Mode.READ));
        assertThrows(IllegalArgumentException.class,
                () -> DopRegionSchedule.Access.rows(r, 0, 9, 2, DopRegionSchedule.Mode.READ));
        assertThrows(IllegalArgumentException.class,
                () -> DopRegionSchedule.Access.rows(r, 0, 2, Long.MAX_VALUE, DopRegionSchedule.Mode.READ));
        assertThrows(IllegalArgumentException.class,
                () -> DopRegionSchedule.Access.rows(r, 0, 0, 1, DopRegionSchedule.Mode.REDUCE));
        assertThrows(IllegalArgumentException.class,
                () -> DopRegionSchedule.Access.rows(r, 0, 0, 1, DopRegionSchedule.Mode.READ, "sum"));
        assertThrows(IllegalArgumentException.class,
                () -> DopRegionSchedule.Access.rows(r, 0, 0, 1, DopRegionSchedule.Mode.REDUCE, " sum"));
        assertThrows(IllegalArgumentException.class,
                () -> new DopRegionSchedule.Task("  ", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DopRegionSchedule.Task(" padded ", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DopRegionSchedule.Task("\u000bname", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DopRegionSchedule.Task("name\u0000", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> DopRegionSchedule.Access.rows(r, 0, 0, 1, DopRegionSchedule.Mode.REDUCE, "\u000bsum"));
        assertThrows(IllegalArgumentException.class,
                () -> DopRegionSchedule.Access.rows(r, 0, 0, 1, DopRegionSchedule.Mode.REDUCE, "sum\f"));
        assertThrows(NullPointerException.class,
                () -> new DopRegionSchedule.Task("bad", java.util.Arrays.asList((DopRegionSchedule.Access) null)));
    }


    @Test
    void exhaustiveReferenceCellOracleMatchesNativeRules() {
        var root = DopRegionSchedule.Root.trustedCreate(5, 2);
        var modes = DopRegionSchedule.Mode.values();
        for (int fa = 0; fa < 2; fa++)
        for (int fb = 0; fb < 2; fb++)
        for (int aa = 0; aa <= 5; aa++)
        for (int az = aa; az <= 5; az++)
        for (int ba = 0; ba <= 5; ba++)
        for (int bz = ba; bz <= 5; bz++)
        for (var ma : modes)
        for (var mb : modes) {
            var a = DopRegionSchedule.Access.rows(root, fa, aa, az-aa, ma,
                    ma == DopRegionSchedule.Mode.REDUCE ? "sum" : null);
            var b = DopRegionSchedule.Access.rows(root, fb, ba, bz-ba, mb,
                    mb == DopRegionSchedule.Mode.REDUCE ? "sum" : null);
            boolean intersects = false;
            for (int row = 0; row < 5; row++) {
                if (fa == fb && row >= aa && row < az && row >= ba && row < bz)
                    intersects = true;
            }
            boolean expected = !intersects ||
                    (ma == DopRegionSchedule.Mode.READ && mb == DopRegionSchedule.Mode.READ);
            assertEquals(expected, DopRegionSchedule.independent(a, b));
            assertEquals(expected, DopRegionSchedule.independent(b, a));
        }
    }

    @Test
    void graphLevelsPreserveEveryEarlierConflictInInputOrder() {
        var r = DopRegionSchedule.Root.trustedCreate(10, 3);
        var writeX = DopRegionSchedule.Access.rows(r, 0, 2, 6, DopRegionSchedule.Mode.WRITE);
        var readX = DopRegionSchedule.Access.rows(r, 0, 2, 6, DopRegionSchedule.Mode.READ);
        var writeY = DopRegionSchedule.Access.rows(r, 1, 2, 6, DopRegionSchedule.Mode.WRITE);
        var tasks = List.of(
                new DopRegionSchedule.Task("write-x", List.of(writeX)),
                new DopRegionSchedule.Task("read-x", List.of(readX)),
                new DopRegionSchedule.Task("write-y", List.of(writeY)));
        assertEquals(List.of(List.of(0, 2), List.of(1)),
                DopRegionSchedule.dependencyLevels(tasks));
        assertEquals(List.of(), DopRegionSchedule.dependencyLevels(List.of()));

        var dependent = new DopRegionSchedule.Task("last-writer", List.of(writeX));
        assertEquals(List.of(List.of(0, 2), List.of(1), List.of(3)),
                DopRegionSchedule.dependencyLevels(List.of(
                        tasks.get(0), tasks.get(1), tasks.get(2), dependent)));
    }
}
