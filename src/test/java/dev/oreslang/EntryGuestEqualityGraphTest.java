package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresEntry;
import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
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

    private static Object complex(double real, double imaginary) throws Exception {
        Class<?> complexClass =
                Class.forName("dev.oreslang.nodes.OresEvalRootNode$Complex");
        Constructor<?> constructor =
                complexClass.getDeclaredConstructor(double.class, double.class);
        constructor.setAccessible(true);
        return constructor.newInstance(real, imaginary);
    }

    @Test
    void trustedNumberHardeningPreservesRealComplexEquality() throws Exception {
        GuestComparator guest = comparator();
        Object one = complex(1.0, 0.0);
        Object two = complex(2.0, 0.0);

        assertTrue(guest.equal(new OresEntry(1L, "v"), new OresEntry(one, "v")));
        assertTrue(guest.equal(new OresEntry(one, "v"), new OresEntry(1L, "v")));
        assertFalse(guest.equal(new OresEntry(1L, "v"), new OresEntry(two, "v")));
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
    void unknownHostNumberCallbacksAreNotInvoked() throws Exception {
        GuestComparator guest = comparator();
        java.util.concurrent.atomic.AtomicInteger callbacks =
                new java.util.concurrent.atomic.AtomicInteger();
        Number hostile = new Number() {
            private AssertionError callback(String method) {
                callbacks.incrementAndGet();
                return new AssertionError("host Number." + method + " invoked");
            }
            @Override public int intValue() { throw callback("intValue"); }
            @Override public long longValue() { throw callback("longValue"); }
            @Override public float floatValue() { throw callback("floatValue"); }
            @Override public double doubleValue() { throw callback("doubleValue"); }
            @Override public String toString() { throw callback("toString"); }
        };

        assertFalse(guest.equal(
                new OresEntry(hostile, "v"),
                new OresEntry(1L, "v")));
        assertFalse(guest.equal(
                new OresEntry(1L, "v"),
                new OresEntry(hostile, "v")));
        assertEquals(0, callbacks.get());
    }

    @Test
    void unknownHostCollectionCallbacksAreNotInvoked() throws Exception {
        GuestComparator guest = comparator();
        List<Object> hostileList = new AbstractList<>() {
            @Override public int size() {
                throw new AssertionError("host List.size invoked");
            }
            @Override public Object get(int index) {
                throw new AssertionError("host List.get invoked");
            }
        };
        Map<Object, Object> hostileMap = new AbstractMap<>() {
            @Override public int size() {
                throw new AssertionError("host Map.size invoked");
            }
            @Override public Set<Entry<Object, Object>> entrySet() {
                throw new AssertionError("host Map.entrySet invoked");
            }
            @Override public boolean containsKey(Object key) {
                throw new AssertionError("host Map.containsKey invoked");
            }
        };
        assertFalse(guest.equal(new OresEntry(hostileList, 1),
                new OresEntry(new ArrayList<>(), 1)));
        assertFalse(guest.equal(new OresEntry(hostileMap, 1),
                new OresEntry(new LinkedHashMap<>(), 1)));
    }

    @Test
    void unequalFirstSequenceElementShortCircuitsBeforeOversizedTail() throws Exception {
        GuestComparator guest = comparator();
        Object[] hugeLeft = new Object[65_537];
        Object[] hugeRight = new Object[65_537];
        assertFalse(guest.equal(
                new OresEntry(new ArrayList<>(List.of(1L, hugeLeft)), 0L),
                new OresEntry(new ArrayList<>(List.of(2L, hugeRight)), 0L)));
    }

    @Test
    void structuralMapNonStringKeysFailClosedBeforeHashing() throws Exception {
        GuestComparator guest = comparator();
        java.util.concurrent.atomic.AtomicInteger hashCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        Object maliciousKey = new Object() {
            @Override public int hashCode() {
                if (hashCalls.incrementAndGet() > 1) {
                    throw new AssertionError("host key rehashed during guest equality");
                }
                return 12345;
            }
            @Override public boolean equals(Object other) {
                throw new AssertionError("host key equals invoked");
            }
        };
        LinkedHashMap<Object, Object> left = new LinkedHashMap<>();
        // Insertion necessarily hashes this host key. The equality path must
        // not hash it again: construct map with one key that is already stored.
        left.put(maliciousKey, 1L);
        LinkedHashMap<Object, Object> right = new LinkedHashMap<>();
        right.put("x", 1L);
        IllegalArgumentException failure = guest.rejects(
                new OresEntry(left, "v"), new OresEntry(right, "v"));
        assertTrue(failure.getMessage().contains("string field names"),
                failure.getMessage());
        assertEquals(1, hashCalls.get());
    }

    @Test
    void actorFrozenMapRemainsComparableAndCannotBeMutated() throws Exception {
        GuestComparator guest = comparator();
        LinkedHashMap<Object, Object> source = new LinkedHashMap<>();
        source.put("key", List.of(1L, 2L));
        Object frozen = ActorRuntime.freeze(source);
        assertInstanceOf(ActorRuntime.FrozenGuestMap.class, frozen);
        assertTrue(guest.equal(new OresEntry(frozen, "v"),
                new OresEntry(source, "v")));

        source.put("key", List.of(99L));
        assertFalse(guest.equal(new OresEntry(frozen, "v"),
                new OresEntry(source, "v")));
        @SuppressWarnings("unchecked")
        Map<Object, Object> readonly = (Map<Object, Object>) frozen;
        assertThrows(UnsupportedOperationException.class,
                () -> readonly.put("new", 1L));
    }

    @Test
    void oversizedGraphsFailClosedInsteadOfExhaustingHeapOrStack() throws Exception {
        GuestComparator guest = comparator();
        Object[] huge = new Object[65_537];
        Object[] otherHuge = new Object[65_537];
        IllegalArgumentException failure = guest.rejects(huge, otherHuge);
        assertTrue(failure.getMessage().contains("comparison work limit"),
                failure.getMessage());
    }
}
