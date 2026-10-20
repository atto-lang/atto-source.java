package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresEntry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the guest comparator on host-constructed cyclic/large graphs that
 * cannot be expressed through the guest Entry.of immutable factory alone.
 * Reflection keeps the evaluator implementation private in production.
 */
final class EntryGuestEqualityGraphTest {
    private record GuestComparator(Object evaluator, Method equality) {
        boolean equal(Object left, Object right) throws Exception {
            return (boolean) equality.invoke(evaluator, left, right);
        }

        IllegalArgumentException rejects(Object left, Object right) {
            InvocationTargetException failure = assertThrows(
                    InvocationTargetException.class,
                    () -> equality.invoke(evaluator, left, right));
            return assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }

    private static GuestComparator comparator() throws Exception {
        Class<?> evaluatorClass =
                Class.forName("dev.oreslang.nodes.OresEvalRootNode$Evaluator");
        Constructor<?> constructor = evaluatorClass.getDeclaredConstructor(
                Ast.Program.class, OresContext.class, String.class);
        constructor.setAccessible(true);
        Object evaluator = constructor.newInstance(
                Parser.parse("fnc main(): void { return; }"),
                null,
                "entry-equality-graph-test");
        Method equality = evaluatorClass.getDeclaredMethod(
                "valueEquals", Object.class, Object.class);
        equality.setAccessible(true);
        return new GuestComparator(evaluator, equality);
    }

    @Test
    void recursiveEntryAndListGraphsTerminateAndDetectLateMismatches() throws Exception {
        GuestComparator guest = comparator();
        ArrayList<Object> leftCycle = new ArrayList<>();
        ArrayList<Object> rightCycle = new ArrayList<>();
        OresEntry left = new OresEntry(leftCycle, 7L);
        OresEntry right = new OresEntry(rightCycle, 7L);
        leftCycle.add(left);
        rightCycle.add(right);

        assertTrue(guest.equal(left, right));
        assertTrue(guest.equal(right, left));
        assertFalse(guest.equal(left, new OresEntry(rightCycle, 8L)));
        assertFalse(guest.equal(new OresEntry(leftCycle, 8L), right));

        LinkedHashMap<String, Object> leftMap = new LinkedHashMap<>();
        LinkedHashMap<String, Object> rightMap = new LinkedHashMap<>();
        OresEntry leftRecord = new OresEntry(leftMap, 3L);
        OresEntry rightRecord = new OresEntry(rightMap, 3L);
        leftMap.put("cycle", leftRecord);
        rightMap.put("cycle", rightRecord);
        assertTrue(guest.equal(leftRecord, rightRecord));
        rightMap.put("extra", 1L);
        assertFalse(guest.equal(leftRecord, rightRecord));
    }

    @Test
    void valueEqualityIsAliasInsensitiveWithoutCallingHostEquals() throws Exception {
        GuestComparator guest = comparator();
        List<Object> shared = List.of(11L, 12L);
        OresEntry withAlias = new OresEntry(shared, shared);
        OresEntry withoutAlias = new OresEntry(
                new ArrayList<>(shared), new ArrayList<>(shared));
        assertTrue(guest.equal(withAlias, withoutAlias));
        assertTrue(guest.equal(withoutAlias, withAlias));

        Object hostile = new Object() {
            @Override public boolean equals(Object other) {
                throw new AssertionError("untrusted host payload equals() invoked");
            }
            @Override public int hashCode() {
                throw new AssertionError("untrusted host payload hashCode() invoked");
            }
        };
        assertFalse(guest.equal(new OresEntry(hostile, "v"),
                new OresEntry(new Object(), "v")));
    }

    @Test
    void thousandsOfNestedEntriesDoNotUseJavaRecursion() throws Exception {
        GuestComparator guest = comparator();
        Object left = 0L;
        Object right = 0L;
        for (int i = 0; i < 2_500; i++) {
            left = new OresEntry(i, left);
            right = new OresEntry(i, right);
        }
        assertTrue(guest.equal(left, right));
        assertTrue(guest.equal(right, left));
        assertFalse(guest.equal(left, new OresEntry(2_500, right)));
    }

    @Test
    void unequalFirstSequenceElementStopsBeforeTouchingLaterElements() throws Exception {
        GuestComparator guest = comparator();
        List<Object> hostileTail = new AbstractList<>() {
            @Override public int size() { return 2; }
            @Override public Object get(int index) {
                if (index != 0) throw new AssertionError("late slot evaluated after mismatch");
                return 1L;
            }
        };
        assertFalse(guest.equal(hostileTail, List.of(2L, 3L)));
    }

    @Test
    void oversizedGraphsFailClosedInsteadOfExhaustingHeapOrStack() throws Exception {
        GuestComparator guest = comparator();
        List<Object> huge = new AbstractList<>() {
            @Override public int size() { return 65_537; }
            @Override public Object get(int index) {
                throw new AssertionError("oversized graph must not access its payload");
            }
        };
        List<Object> otherHuge = new AbstractList<>() {
            @Override public int size() { return 65_537; }
            @Override public Object get(int index) {
                throw new AssertionError("oversized graph must not access its payload");
            }
        };
        IllegalArgumentException failure = guest.rejects(huge, otherHuge);
        assertTrue(failure.getMessage().contains("comparison work limit"),
                failure.getMessage());
    }
}
