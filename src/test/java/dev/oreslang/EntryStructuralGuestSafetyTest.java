package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresEntry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guest value equality must not execute arbitrary host collection callbacks.
 * Typed tuples retain their runtime identity while actor-frozen record maps
 * remain comparable to evaluator-owned records.
 */
final class EntryStructuralGuestSafetyTest {
    private record GuestEquality(Object evaluator, Method method) {
        boolean matches(Object a, Object b) throws Exception {
            return (boolean) method.invoke(evaluator, a, b);
        }
        IllegalArgumentException rejects(Object a, Object b) {
            InvocationTargetException error = assertThrows(
                    InvocationTargetException.class,
                    () -> method.invoke(evaluator, a, b));
            return assertInstanceOf(IllegalArgumentException.class, error.getCause());
        }
    }

    private static GuestEquality guest() throws Exception {
        Class<?> evaluator = Class.forName("dev.oreslang.nodes.OresEvalRootNode$Evaluator");
        Constructor<?> constructor = evaluator.getDeclaredConstructor(
                Ast.Program.class, OresContext.class, String.class);
        constructor.setAccessible(true);
        Object instance = constructor.newInstance(
                Parser.parse("fnc main(): void { return; }"), null, "structural-equality-test");
        Method method = evaluator.getDeclaredMethod("valueEquals", Object.class, Object.class);
        method.setAccessible(true);
        return new GuestEquality(instance, method);
    }

    @Test
    void foreignCollectionsAreOpaqueAndCannotRunCallbacks() throws Exception {
        GuestEquality g = guest();
        List<Object> hostileList = new AbstractList<>() {
            @Override public int size() { throw new AssertionError("host list size"); }
            @Override public Object get(int index) { throw new AssertionError("host list get"); }
        };
        Map<Object, Object> hostileMap = new AbstractMap<>() {
            @Override public int size() { throw new AssertionError("host map size"); }
            @Override public Set<Entry<Object, Object>> entrySet() {
                throw new AssertionError("host map entrySet");
            }
            @Override public boolean containsKey(Object key) {
                throw new AssertionError("host map containsKey");
            }
        };
        assertFalse(g.matches(new OresEntry(hostileList, 1L),
                new OresEntry(new ArrayList<>(), 1L)));
        assertFalse(g.matches(new OresEntry(hostileMap, 1L),
                new OresEntry(new LinkedHashMap<>(), 1L)));
    }

    @Test
    void recordKeysCannotInvokeForeignHashing() throws Exception {
        GuestEquality g = guest();
        Object hostileKey = new Object() {
            @Override public int hashCode() { return 123; }
            @Override public boolean equals(Object other) {
                throw new AssertionError("unexpected host key equals");
            }
        };
        LinkedHashMap<Object, Object> left = new LinkedHashMap<>();
        left.put(hostileKey, 1L);
        LinkedHashMap<Object, Object> right = new LinkedHashMap<>();
        right.put("safe", 1L);
        assertTrue(g.rejects(new OresEntry(left, 1L), new OresEntry(right, 1L))
                .getMessage().contains("string field names"));
    }

    @Test
    void structuralMatchersNeverCallForeignMapCallbacks() throws Exception {
        GuestEquality g = guest();
        Class<?> evaluatorType = g.evaluator().getClass();
        Method carrier = evaluatorType.getDeclaredMethod(
                "isStructuralValueCarrier", Object.class);
        Method lookup = evaluatorType.getDeclaredMethod(
                "publicStructuralMember", Object.class, String.class);
        Method structural = evaluatorType.getDeclaredMethod(
                "structuralAggregateMatches", Object.class, Map.class);
        carrier.setAccessible(true);
        lookup.setAccessible(true);
        structural.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        Map<Object, Object> hostileMap = new AbstractMap<>() {
            @Override public boolean containsKey(Object key) {
                calls.incrementAndGet();
                throw new AssertionError("foreign map containsKey callback");
            }
            @Override public Object get(Object key) {
                calls.incrementAndGet();
                throw new AssertionError("foreign map get callback");
            }
            @Override public Set<Entry<Object, Object>> entrySet() {
                calls.incrementAndGet();
                throw new AssertionError("foreign map entrySet callback");
            }
        };
        assertFalse((boolean) carrier.invoke(g.evaluator(), hostileMap));
        assertFalse((boolean) structural.invoke(g.evaluator(), hostileMap, Map.of()));
        LinkedHashMap<String, Ast.TypeRef> required = new LinkedHashMap<>();
        // An invalid type must never be read: the foreign Map is rejected
        // before the matcher can inspect any member or call a callback.
        required.put("id", null);
        assertFalse((boolean) structural.invoke(g.evaluator(), hostileMap, required));
        assertNotNull(lookup.invoke(g.evaluator(), hostileMap, "id"));
        assertEquals(0, calls.get());

        LinkedHashMap<String, Object> trusted = new LinkedHashMap<>();
        trusted.put("id", 1L);
        assertTrue((boolean) carrier.invoke(g.evaluator(), trusted));
        assertTrue((boolean) structural.invoke(g.evaluator(), trusted, Map.of()));
        assertTrue((boolean) carrier.invoke(g.evaluator(), ActorRuntime.freeze(trusted)));
    }

    @Test
    void actorFrozenMapsRemainReadonlyAndComparable() throws Exception {
        GuestEquality g = guest();
        LinkedHashMap<Object, Object> original = new LinkedHashMap<>();
        original.put("name", List.of("alice"));
        Object frozen = ActorRuntime.freeze(original);
        assertInstanceOf(ActorRuntime.FrozenGuestMap.class, frozen);
        assertTrue(g.matches(new OresEntry(frozen, "tag"),
                new OresEntry(original, "tag")));
        original.put("name", List.of("bob"));
        assertFalse(g.matches(new OresEntry(frozen, "tag"),
                new OresEntry(original, "tag")));
        @SuppressWarnings("unchecked")
        Map<Object, Object> readonly = (Map<Object, Object>) frozen;
        assertThrows(UnsupportedOperationException.class,
                () -> readonly.put("other", "value"));
    }
}
