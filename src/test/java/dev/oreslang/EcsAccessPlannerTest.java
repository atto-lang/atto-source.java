package dev.oreslang.types;

import dev.oreslang.types.EcsAccessPlanner;
import dev.oreslang.types.EcsAccessPlanner.Access;
import dev.oreslang.types.EcsAccessPlanner.AllocationIdentity;
import dev.oreslang.types.EcsAccessPlanner.Mode;
import dev.oreslang.types.EcsAccessPlanner.SystemAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class EcsAccessPlannerTest {
    private static SystemAccess sys(String name, Access... accesses) {
        return new SystemAccess(name, List.of(accesses));
    }

    private static Access read(AllocationIdentity id, long begin, long end) {
        return new Access(id, begin, end, Mode.READ);
    }

    private static Access write(AllocationIdentity id, long begin, long end) {
        return new Access(id, begin, end, Mode.WRITE);
    }

    @Test
    void permitsReadReadAndIndependentColumnsButSerializesOverlappingWrites() {
        AllocationIdentity positions = EcsAccessPlanner.newAllocationIdentity();
        AllocationIdentity velocities = EcsAccessPlanner.newAllocationIdentity();
        AllocationIdentity health = EcsAccessPlanner.newAllocationIdentity();
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
        AllocationIdentity positions = EcsAccessPlanner.newAllocationIdentity();
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
        AllocationIdentity positions = EcsAccessPlanner.newAllocationIdentity();
        assertTrue(EcsAccessPlanner.conflicts(
                sys("unknown", write(null, 0, 1)),
                sys("known", read(positions, 1000, 1001))));
        assertFalse(EcsAccessPlanner.conflicts(
                sys("unknownRead", read(null, 0, 1)),
                sys("knownRead", read(positions, 1000, 1001))));
    }

    @Test
    void neverBackfillsEarlierBatchPastAConflictingDependency() {
        AllocationIdentity x = EcsAccessPlanner.newAllocationIdentity();
        AllocationIdentity y = EcsAccessPlanner.newAllocationIdentity();
        SystemAccess a = sys("a", write(x, 0, 8));
        SystemAccess b = sys("b", read(x, 0, 8), write(y, 0, 8));
        SystemAccess c = sys("c", read(y, 0, 8));
        assertEquals(List.of(List.of(a), List.of(b), List.of(c)),
                EcsAccessPlanner.plan(List.of(a, b, c)));
    }

    @Test
    void missingEffectFootprintsAreSerializationBarriers() {
        AllocationIdentity positions = EcsAccessPlanner.newAllocationIdentity();
        SystemAccess unknownEffects = sys("unknown");
        SystemAccess readOnly = sys("read", read(positions, 0, 32));
        SystemAccess readOnly2 = sys("read2", read(positions, 0, 32));
        assertTrue(EcsAccessPlanner.conflicts(unknownEffects, readOnly));
        assertTrue(EcsAccessPlanner.conflicts(readOnly, unknownEffects));
        assertTrue(EcsAccessPlanner.conflicts(unknownEffects, sys("unknown2")));
        assertEquals(List.of(List.of(readOnly), List.of(unknownEffects), List.of(readOnly2)),
                EcsAccessPlanner.plan(List.of(readOnly, unknownEffects, readOnly2)));
    }

    @Test
    void seededRandomAccessGraphsHonorEveryConflictAndBarrier() {
        Random random = new Random(0x5eed);
        AllocationIdentity[] allocations = {
                EcsAccessPlanner.newAllocationIdentity(),
                EcsAccessPlanner.newAllocationIdentity(),
                EcsAccessPlanner.newAllocationIdentity()};
        for (int trial = 0; trial < 500; trial++) {
            List<SystemAccess> workload = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                List<Access> footprint = new ArrayList<>();
                for (int count = random.nextInt(4); count > 0; count--) {
                    int allocation = random.nextInt(4);
                    long start = random.nextInt(20);
                    footprint.add(new Access(
                            allocation == 3 ? null : allocations[allocation],
                            start, start + random.nextInt(7) + 1,
                            random.nextBoolean() ? Mode.READ : Mode.WRITE));
                }
                workload.add(new SystemAccess(Integer.toString(index), footprint));
            }
            List<List<SystemAccess>> batches = EcsAccessPlanner.plan(workload);
            List<Integer> assigned = new ArrayList<>();
            int consumed = 0;
            for (int batch = 0; batch < batches.size(); batch++) {
                List<SystemAccess> systems = batches.get(batch);
                for (int i = 0; i < systems.size(); i++) {
                    assertEquals(workload.get(consumed++), systems.get(i));
                    assigned.add(batch);
                    for (int j = i + 1; j < systems.size(); j++) {
                        assertFalse(EcsAccessPlanner.conflicts(systems.get(i), systems.get(j)),
                                "conflicting systems placed in the same batch");
                    }
                }
            }
            assertEquals(workload.size(), consumed);
            for (int i = 0; i < workload.size(); i++) {
                for (int j = i + 1; j < workload.size(); j++) {
                    if (EcsAccessPlanner.conflicts(workload.get(i), workload.get(j))) {
                        assertTrue(assigned.get(i) < assigned.get(j),
                                "a conflicting later system overtook its prerequisite");
                    }
                }
            }
        }
    }


    @Test
    void plannerRejectsOversizedSystemCountsAndQuadraticProofs() {
        SystemAccess simple = sys("read", read(EcsAccessPlanner.newAllocationIdentity(), 0, 1));
        assertThrows(IllegalArgumentException.class, () ->
                EcsAccessPlanner.plan(java.util.Collections.nCopies(
                        EcsAccessPlanner.MAX_SYSTEMS + 1, simple)));

        Access read = read(EcsAccessPlanner.newAllocationIdentity(), 0, 1);
        List<Access> largest = java.util.Collections.nCopies(
                EcsAccessPlanner.MAX_ACCESSES_PER_SYSTEM, read);
        assertThrows(IllegalArgumentException.class, () ->
                new SystemAccess("too-many-spans", new java.util.ArrayList<>(
                        java.util.Collections.nCopies(
                                EcsAccessPlanner.MAX_ACCESSES_PER_SYSTEM + 1, read))));
        SystemAccess a = new SystemAccess("a", largest);
        SystemAccess b = new SystemAccess("b", largest);
        // Even read/read is denied when the compiler cannot finish proof
        // work within its explicit complexity budget.
        assertThrows(IllegalArgumentException.class, () -> EcsAccessPlanner.conflicts(a, b));
        assertThrows(IllegalArgumentException.class, () -> EcsAccessPlanner.plan(List.of(a, b)));
        assertFalse(EcsAccessPlanner.conflicts(simple, simple));
    }

    @Test
    void provenanceUsesOpaqueCompilerTokens() {
        AllocationIdentity a = EcsAccessPlanner.newAllocationIdentity();
        AllocationIdentity b = EcsAccessPlanner.newAllocationIdentity();
        assertNotSame(a, b);
        SystemAccess first = sys("a", write(a, 0, 100));
        SystemAccess second = sys("b", write(b, 0, 100));
        assertFalse(EcsAccessPlanner.conflicts(first, second));
        SystemAccess shared = sys("shared", read(a, 0, 100));
        assertTrue(EcsAccessPlanner.conflicts(first, shared));
        assertEquals(0, AllocationIdentity.class.getConstructors().length);
    }

    @Test
    void oversizedFootprintIsRejectedBeforeCallerListEnumeration() {
        java.util.concurrent.atomic.AtomicBoolean enumerated =
                new java.util.concurrent.atomic.AtomicBoolean();
        List<Access> oversized = new java.util.AbstractList<>() {
            @Override
            public Access get(int index) {
                enumerated.set(true);
                throw new AssertionError("oversized footprint must be rejected before enumeration");
            }

            @Override
            public int size() {
                return EcsAccessPlanner.MAX_ACCESSES_PER_SYSTEM + 1;
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new SystemAccess("oversized", oversized));
        assertFalse(enumerated.get(),
                "planner copied/enumerated an input it already knew exceeded the budget");
    }

    @Test
    void aSystemCanReadModifyWriteItsOwnRegionButSerializesWithOtherSystems() {
        AllocationIdentity allocation = EcsAccessPlanner.newAllocationIdentity();
        SystemAccess update = sys("update",
                read(allocation, 0, 64), write(allocation, 0, 64));
        SystemAccess observer = sys("observer", read(allocation, 0, 64));
        assertEquals(List.of(List.of(update), List.of(observer)),
                EcsAccessPlanner.plan(List.of(update, observer)));
    }

    @Test
    void invalidRangesAndNullInputsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> read(EcsAccessPlanner.newAllocationIdentity(), -1, 1));
        assertThrows(IllegalArgumentException.class, () -> write(EcsAccessPlanner.newAllocationIdentity(), 5, 5));
        assertThrows(IllegalArgumentException.class, () -> write(EcsAccessPlanner.newAllocationIdentity(), 8, 7));
        assertThrows(NullPointerException.class, () -> new Access(EcsAccessPlanner.newAllocationIdentity(), 0, 1, null));
        assertThrows(IllegalArgumentException.class, () -> sys(""));
        assertThrows(IllegalArgumentException.class,
                () -> sys("x".repeat(EcsAccessPlanner.MAX_SYSTEM_NAME_CHARS + 1)));
        assertThrows(NullPointerException.class, () -> EcsAccessPlanner.plan(null));
        assertEquals(List.of(), EcsAccessPlanner.plan(List.of()));
    }
}
