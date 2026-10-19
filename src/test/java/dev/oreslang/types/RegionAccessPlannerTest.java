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
        assertEquals(3,stages.size());
        assertEquals(List.of(readVel,readVel2,writePos),stages.get(0).parallel());
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
