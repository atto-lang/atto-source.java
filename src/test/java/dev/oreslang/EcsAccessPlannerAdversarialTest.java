package dev.oreslang.types;

import dev.oreslang.types.EcsAccessPlanner;
import dev.oreslang.types.EcsAccessPlanner.Access;
import dev.oreslang.types.EcsAccessPlanner.Mode;
import dev.oreslang.types.EcsAccessPlanner.SystemAccess;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class EcsAccessPlannerAdversarialTest {
    private static Access read() {
        return new Access(EcsAccessPlanner.newAllocationIdentity(), 0, 1, Mode.READ);
    }

    private static SystemAccess system() {
        return new SystemAccess("simple", List.of(read()));
    }

    @Test
    void reportedSmallFootprintCannotHideMoreThanTheBound() {
        Access access = read();
        List<Access> inconsistent = new AbstractList<>() {
            @Override public Access get(int index) { return access; }
            @Override public int size() { return 1; }
            @Override public Iterator<Access> iterator() {
                return Collections.nCopies(
                        EcsAccessPlanner.MAX_ACCESSES_PER_SYSTEM + 1, access).iterator();
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new SystemAccess("bounded", inconsistent));
    }

    @Test
    void declaredAndObservedFootprintCountsMustAgree() {
        Access access = read();
        List<Access> inconsistent = new AbstractList<>() {
            @Override public Access get(int index) {
                if (index == 0) return access;
                throw new IndexOutOfBoundsException(index);
            }
            @Override public int size() { return 2; }
            @Override public Iterator<Access> iterator() {
                return List.of(access).iterator();
            }
        };
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new SystemAccess("inconsistent", inconsistent));
        assertTrue(error.getMessage().contains("inconsistent size"));
    }

    @Test
    void obviouslyOversizedSystemListIsRejectedBeforeEnumeration() {
        AtomicBoolean enumerated = new AtomicBoolean();
        List<SystemAccess> oversized = new AbstractList<>() {
            @Override public SystemAccess get(int index) {
                enumerated.set(true);
                throw new AssertionError("unexpected enumeration");
            }
            @Override public int size() {
                return EcsAccessPlanner.MAX_SYSTEMS + 1;
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> EcsAccessPlanner.plan(oversized));
        assertFalse(enumerated.get());
    }

    @Test
    void reportedSmallSystemListCannotHideMoreThanTheBound() {
        SystemAccess system = system();
        List<SystemAccess> inconsistent = new AbstractList<>() {
            @Override public SystemAccess get(int index) { return system; }
            @Override public int size() { return 1; }
            @Override public Iterator<SystemAccess> iterator() {
                return Collections.nCopies(
                        EcsAccessPlanner.MAX_SYSTEMS + 1, system).iterator();
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> EcsAccessPlanner.plan(inconsistent));
    }
}
