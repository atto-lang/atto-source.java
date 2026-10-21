package dev.oreslang.types;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

final class RegionAccessPlannerTest {
    private static final RegionAccessPlanner.Region POSITION =
            new RegionAccessPlanner.Region("world-1", "Position");
    private static final RegionAccessPlanner.Region VELOCITY =
            new RegionAccessPlanner.Region("world-1", "Velocity");
    private static final RegionAccessPlanner.Region HEALTH =
            new RegionAccessPlanner.Region("world-1", "Health");

    private static RegionAccessPlanner.Access access(
            RegionAccessPlanner.Region region, long from, long until,
            RegionAccessPlanner.Mode mode) {
        return new RegionAccessPlanner.Access(region, from, until, mode);
    }
    private static RegionAccessPlanner.System system(
            String name, RegionAccessPlanner.Access... accesses) {
        return new RegionAccessPlanner.System(name, List.of(accesses));
    }

    @Test void disjointPositionChunksCanRunConcurrentlyWithoutCopying() {
        var chunks=RegionAccessPlanner.disjointChunks(POSITION, 10_005, 1_000,
                RegionAccessPlanner.Mode.WRITE);
        assertEquals(11, chunks.size());
        assertEquals(0, chunks.getFirst().from());
        assertEquals(10_005, chunks.getLast().until());
        var work=chunks.stream().map(c -> system("integrate-"+c.from(),c)).toList();
        assertEquals(1, RegionAccessPlanner.plan(work).size());
        assertEquals(11, RegionAccessPlanner.plan(work).getFirst().parallel().size());
    }

    @Test void overlappingWritesSerializeAndReadersCanShare() {
        var readVel=system("velocity",access(VELOCITY,0,100,RegionAccessPlanner.Mode.READ));
        var readVel2=system("velocity-2",access(VELOCITY,0,100,RegionAccessPlanner.Mode.READ));
        var writePos=system("position",access(POSITION,0,100,RegionAccessPlanner.Mode.WRITE));
        var writePos2=system("position-2",access(POSITION,50,150,RegionAccessPlanner.Mode.WRITE));
        var readPos=system("sample-position",access(POSITION,30,40,RegionAccessPlanner.Mode.READ));
        var stages=RegionAccessPlanner.plan(List.of(readVel,readVel2,writePos,writePos2,readPos));
        assertEquals(2,stages.size());
        assertEquals(List.of(readVel,readVel2,writePos),stages.get(0).parallel());
        // readPos [30,40) overlaps stage-zero writePos but is disjoint
        // from stage-one writePos2 [50,150), so both may run together.
        assertEquals(List.of(writePos2,readPos),stages.get(1).parallel());
    }

    @Test void differentAllocationsOrColumnsNeverAlias() {
        var a=system("a",access(HEALTH,0,100,RegionAccessPlanner.Mode.WRITE));
        var b=system("b",access(POSITION,0,100,RegionAccessPlanner.Mode.WRITE));
        var c=system("c",access(new RegionAccessPlanner.Region("world-2","Health"),
                0,100,RegionAccessPlanner.Mode.WRITE));
        assertFalse(RegionAccessPlanner.conflicts(a,b));
        assertFalse(RegionAccessPlanner.conflicts(a,c));
        assertEquals(1,RegionAccessPlanner.plan(List.of(a,b,c)).size());
    }

    @Test void cacheAwarePlanSerializesOnlyProvenFalseSharing() {
        var left = system("left", new RegionAccessPlanner.Access(
                POSITION, 0, 8, RegionAccessPlanner.Mode.WRITE, 0L, 4));
        var right = system("right", new RegionAccessPlanner.Access(
                POSITION, 8, 16, RegionAccessPlanner.Mode.WRITE, 0L, 4));

        assertEquals(1, RegionAccessPlanner.plan(List.of(left, right)).size(),
                "logical disjointness remains a correctness-level parallel proof");
        assertEquals(2,
                RegionAccessPlanner.planCacheAware(List.of(left, right), 64).size(),
                "adjacent F32 writers share a physical 64-byte line");

        var alignedRight = system("aligned-right", new RegionAccessPlanner.Access(
                POSITION, 16, 32, RegionAccessPlanner.Mode.WRITE, 0L, 4));
        assertEquals(1,
                RegionAccessPlanner.planCacheAware(List.of(left, alignedRight), 64).size());

        var unknown = system("unknown-physical",
                access(POSITION, 8, 16, RegionAccessPlanner.Mode.WRITE));
        assertEquals(1,
                RegionAccessPlanner.planCacheAware(List.of(left, unknown), 64).size(),
                "unknown physical geometry must not fabricate a performance dependency");
    }

    @Test void differentColumnsCanStillShareOnePhysicalCacheLine() {
        var position = system("position", new RegionAccessPlanner.Access(
                POSITION, 0, 4, RegionAccessPlanner.Mode.WRITE, 0L, 8));
        var velocity = system("velocity", new RegionAccessPlanner.Access(
                VELOCITY, 0, 4, RegionAccessPlanner.Mode.WRITE, 32L, 8));

        assertFalse(RegionAccessPlanner.conflicts(position, velocity));
        assertTrue(RegionAccessPlanner.cacheLineConflicts(position, velocity, 64));
        assertEquals(2,
                RegionAccessPlanner.planCacheAware(List.of(position, velocity), 64).size());

        var separateLine = system("velocity-far", new RegionAccessPlanner.Access(
                VELOCITY, 0, 4, RegionAccessPlanner.Mode.WRITE, 64L, 8));
        assertFalse(RegionAccessPlanner.cacheLineConflicts(position, separateLine, 64));
    }

    @Test void cacheSafeChunksAlignInteriorWriterBoundaries() {
        var chunks = RegionAccessPlanner.cacheSafeDisjointChunks(
                POSITION, 40, 10, RegionAccessPlanner.Mode.WRITE,
                0, 4, 64);
        assertEquals(List.of(0L, 16L, 32L),
                chunks.stream().map(RegionAccessPlanner.Access::from).toList());
        assertEquals(List.of(16L, 32L, 40L),
                chunks.stream().map(RegionAccessPlanner.Access::until).toList());

        var work = chunks.stream()
                .map(a -> system("chunk-" + a.from(), a))
                .toList();
        assertEquals(1, RegionAccessPlanner.planCacheAware(work, 64).size());

        assertThrows(IllegalArgumentException.class,
                () -> RegionAccessPlanner.cacheSafeDisjointChunks(
                        POSITION, 100, 10, RegionAccessPlanner.Mode.WRITE,
                        1, 4, 64),
                "misaligned base with no reachable line boundary must fail closed");
    }

    @Test void cacheGeometryRejectsInvalidWidthsAndOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> RegionAccessPlanner.planCacheAware(List.of(), 48));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionAccessPlanner.Access(
                        POSITION, 0, Long.MAX_VALUE,
                        RegionAccessPlanner.Mode.READ, Long.MAX_VALUE - 8, 8));
    }

    @Test void failClosedForUnknownOrEmptyAccess() {
        assertThrows(IllegalArgumentException.class,()->system("unknown"));
        assertThrows(IllegalArgumentException.class,()->new RegionAccessPlanner.Region("", "X"));
        assertThrows(IllegalArgumentException.class,()->
                access(POSITION,1,1,RegionAccessPlanner.Mode.READ));
        assertThrows(IllegalArgumentException.class,()->
                access(POSITION,-1,10,RegionAccessPlanner.Mode.READ));
    }

    @Test void conflictOrderRetainedAcrossIndependentSystems() {
        var p1=system("write-positions",
                access(POSITION,0,128,RegionAccessPlanner.Mode.WRITE));
        var h=system("update-health",
                access(HEALTH,0,128,RegionAccessPlanner.Mode.WRITE));
        var p2=system("read-positions",
                access(POSITION,0,128,RegionAccessPlanner.Mode.READ));
        var h2=system("read-health",
                access(HEALTH,0,128,RegionAccessPlanner.Mode.READ));
        var stages=RegionAccessPlanner.plan(List.of(p1,h,p2,h2));
        assertEquals(2,stages.size());
        assertEquals(List.of(p1,h),stages.get(0).parallel());
        assertEquals(List.of(p2,h2),stages.get(1).parallel());
    }
}
