package dev.oreslang;

import dev.oreslang.types.EcsAccessPlanner;
import dev.oreslang.types.EcsAccessPlanner.Access;
import dev.oreslang.types.EcsAccessPlanner.Mode;
import dev.oreslang.types.EcsAccessPlanner.SystemAccess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class EcsAccessPlannerTest {
    private static SystemAccess sys(String name, Access... accesses) {
        return new SystemAccess(name, List.of(accesses));
    }

    private static Access read(Object id, long begin, long end) {
        return new Access(id, begin, end, Mode.READ);
    }

    private static Access write(Object id, long begin, long end) {
        return new Access(id, begin, end, Mode.WRITE);
    }

    @Test
    void permitsReadReadAndIndependentColumnsButSerializesOverlappingWrites() {
        Object positions = new Object();
        Object velocities = new Object();
        Object health = new Object();
        SystemAccess move = sys("movement", read(velocities, 0, 4096), write(positions, 0, 4096));
        SystemAccess damage = sys("damage", write(health, 0, 4096));
        SystemAccess render = sys("render", read(positions, 0, 4096));
        SystemAccess render2 = sys("render2", read(positions, 0, 4096));

        assertFalse(EcsAccessPlanner.conflicts(move, damage));
        assertTrue(EcsAccessPlanner.conflicts(move, render));
        assertFalse(EcsAccessPlanner.conflicts(render, render2));
        assertEquals(List.of(List.of(move, damage), List.of(render, render2)),
                EcsAccessPlanner.plan(List.of(move, damage, render, render2)));
    }

    @Test
    void halfOpenChunksCanSplitWithoutOverlap() {
        Object positions = new Object();
        SystemAccess first = sys("first", write(positions, 0, 64));
        SystemAccess second = sys("second", write(positions, 64, 128));
        SystemAccess overlap = sys("overlap", read(positions, 63, 65));
        assertFalse(EcsAccessPlanner.conflicts(first, second));
        assertTrue(EcsAccessPlanner.conflicts(first, overlap));
        assertTrue(EcsAccessPlanner.conflicts(second, overlap));
        assertEquals(2, EcsAccessPlanner.plan(List.of(first, second, overlap)).size());
    }

    @Test
    void unknownAliasingFailsClosedRegardlessOfOffset() {
        Object positions = new Object();
        assertTrue(EcsAccessPlanner.conflicts(
                sys("unknown", write(null, 0, 1)),
                sys("known", read(positions, 1000, 1001))));
        assertFalse(EcsAccessPlanner.conflicts(
                sys("unknownRead", read(null, 0, 1)),
                sys("knownRead", read(positions, 1000, 1001))));
    }

    @Test
    void neverBackfillsEarlierBatchPastAConflictingDependency() {
        Object x = new Object();
        Object y = new Object();
        SystemAccess a = sys("a", write(x, 0, 8));
        SystemAccess b = sys("b", read(x, 0, 8), write(y, 0, 8));
        SystemAccess c = sys("c", read(y, 0, 8));
        assertEquals(List.of(List.of(a), List.of(b), List.of(c)),
                EcsAccessPlanner.plan(List.of(a, b, c)));
    }

    @Test
    void invalidRangesAndNullInputsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> read(new Object(), -1, 1));
        assertThrows(IllegalArgumentException.class, () -> write(new Object(), 5, 5));
        assertThrows(IllegalArgumentException.class, () -> write(new Object(), 8, 7));
        assertThrows(NullPointerException.class, () -> new Access(new Object(), 0, 1, null));
        assertThrows(IllegalArgumentException.class, () -> sys(""));
        assertThrows(NullPointerException.class, () -> EcsAccessPlanner.plan(null));
        assertEquals(List.of(), EcsAccessPlanner.plan(List.of()));
    }
}
