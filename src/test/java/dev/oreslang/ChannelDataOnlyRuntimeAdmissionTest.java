package dev.oreslang;

import dev.oreslang.runtime.OresSymbol;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the defense-in-depth runtime predicate used by source writech,
 * nb/try writech, static select write, and dynamic SelectCase.write.
 * These host reflection checks do NOT replace compiler-generated guest proof.
 */
final class ChannelDataOnlyRuntimeAdmissionTest {
    private static final Method ADMIT;
    private static final Method SNAPSHOT;

    static {
        try {
            Class<?> evaluator = Class.forName("dev.oreslang.nodes.OresEvalRootNode$Evaluator");
            ADMIT = evaluator.getDeclaredMethod("requireDataOnlyChannelPayload", Object.class);
            ADMIT.setAccessible(true);
            SNAPSHOT = evaluator.getDeclaredMethod("snapshotChannelMessageData", Object.class);
            SNAPSHOT.setAccessible(true);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static void admit(Object value) throws Throwable {
        try {
            ADMIT.invoke(null, new Object[] {value});
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static Object snapshot(Object value) throws Throwable {
        try {
            return SNAPSHOT.invoke(null, new Object[] {value});
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static void reject(Object payload) {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> admit(payload));
        assertTrue(error.getMessage().contains("channel payload"), error.getMessage());
    }

    @Test
    void basicAndNestedDataAreAccepted() {
        assertDoesNotThrow(() -> admit(42L));
        assertDoesNotThrow(() -> admit(OresSymbol.process("ready")));
        assertDoesNotThrow(() -> admit(Map.of("values", List.of(1L, 2L, 3L))));
        assertDoesNotThrow(() -> admit(new byte[] {1, 2, 3}));
    }

    @Test
    void snapshotsNestedListsAndMapsBeforeProducerMutates() throws Throwable {
        ArrayList<Object> originalItems = new ArrayList<>();
        originalItems.add("before");
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("items", originalItems);
        @SuppressWarnings("unchecked")
        Map<String, Object> detached = (Map<String, Object>) snapshot(original);
        originalItems.add("after");
        original.put("another", "later");

        assertTrue(detached != original);
        assertEquals(1, detached.size());
        assertEquals(List.of("before"), detached.get("items"));
        assertTrue(detached.get("items") != originalItems);
        assertTrue(((List<?>) detached.get("items")).size() == 1);
    }

    @Test
    void snapshotsArraysRatherThanRetainingProducerArrayStorage() throws Throwable {
        byte[] source = new byte[] {1, 2, 3};
        Object detached = snapshot(source);
        source[0] = 9;
        assertEquals(List.of((byte) 1, (byte) 2, (byte) 3), detached);
    }

    @Test
    void preservesGuestStructTagWhileDetachingNestedFields() throws Throwable {
        Class<?> type = Class.forName("dev.oreslang.nodes.OresEvalRootNode$MutableStructValue");
        java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor(Map.class);
        ctor.setAccessible(true);
        ArrayList<Object> nested = new ArrayList<>(List.of("before"));
        Object struct = ctor.newInstance(Map.of("nested", nested));
        Object detached = snapshot(struct);
        assertEquals(type, detached.getClass(), "snapshot must not turn a struct into a plain Map");
        nested.add("after");
        assertEquals(List.of("before"), ((Map<?, ?>) detached).get("nested"));
        assertTrue(detached != struct);
    }

    @Test
    void nominalObjectSnapshotPreservesNominalTagAndDetachesFields() throws Throwable {
        Class<?> type = Class.forName("dev.oreslang.nodes.OresEvalRootNode$OresObject");
        java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        ArrayList<Object> nested = new ArrayList<>(List.of(1L));
        dev.oreslang.ast.Ast.ClassDecl nominalClass = new dev.oreslang.ast.Ast.ClassDecl(
                "TransportedData", false, List.of(), List.of(), List.of(), List.of());
        Object nominal = ctor.newInstance(
                null, nominalClass, new LinkedHashMap<>(Map.of("nested", nested)));
        Object detached = snapshot(nominal);
        assertEquals(type, detached.getClass());
        java.lang.reflect.Field fields = type.getDeclaredField("fields");
        fields.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> received = (Map<String, Object>) fields.get(detached);
        nested.add(2L);
        assertEquals(List.of(1L), received.get("nested"));
        assertTrue(detached != nominal);
    }

    @Test
    void cannotSnapshotActorInstancesAsOrdinaryData() throws Throwable {
        Class<?> type = Class.forName("dev.oreslang.nodes.OresEvalRootNode$OresObject");
        java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        dev.oreslang.ast.Ast.ClassDecl actorClass = new dev.oreslang.ast.Ast.ClassDecl(
                "NotAValue", false, dev.oreslang.ast.Ast.ActorKind.SHARED,
                List.of(), List.of(), List.of(), List.of(), List.of());
        Object actor = ctor.newInstance(null, actorClass, new LinkedHashMap<>());
        reject(actor);
    }

    @Test
    void failedSnapshotDoesNotPublishACyclicOrCallableGraph() {
        ArrayList<Object> producer = new ArrayList<>();
        producer.add((Runnable) () -> {});
        reject(producer);
        producer.clear();
        producer.add(producer);
        reject(producer);
    }

    @Test
    void rejectsDirectClosureAndBoundMethod() {
        List<String> captured = new ArrayList<>();
        Runnable closure = () -> captured.add("not-run");
        Runnable bound = captured::clear;
        reject(closure);
        reject(bound);
        assertTrue(captured.isEmpty(), "admission must never invoke captured code");
    }

    @Test
    void rejectsCallableInAllSupportedCollectionShapes() {
        Runnable closure = () -> { };
        reject(List.of("safe", closure));
        reject(Map.of("cb", List.of(closure)));
        reject(Map.of(closure, 1L));
        reject(Set.of(closure));
        reject(new Object[] {1L, closure});
        reject(new Object[] {Map.of("nested", new Object[] {closure})});
    }

    @Test
    void rejectsHostObjectEvenIfItImplementsSerializable() {
        record Captured(java.io.Serializable reference) implements java.io.Serializable { }
        java.io.Serializable callable =
                (Runnable & java.io.Serializable) () -> { };
        reject(new Captured(callable));
        reject(callable);
    }

    @Test
    void rejectsArbitraryNumberSubtypeThatCanHideLexicalState() {
        Runnable callable = () -> { };
        Number disguised = new Number() {
            private final Runnable hidden = callable;
            @Override public int intValue() { return 0; }
            @Override public long longValue() { return 0L; }
            @Override public float floatValue() { return 0.0f; }
            @Override public double doubleValue() { return 0.0; }
        };
        reject(disguised);
    }

    @Test
    void rejectsCyclesAndBoundedDepthViolations() {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        reject(cyclic);
        Object deep = 7L;
        for (int i = 0; i < 130; i++) deep = List.of(deep);
        reject(deep);
    }

    @Test
    void rejectsWideDataGraphsEvenWithoutDeepRecursion() {
        java.util.ArrayList<Object> flat = new java.util.ArrayList<>(65537);
        for (int i = 0; i < 65537; i++) flat.add(1L);
        reject(flat);
        assertDoesNotThrow(() -> admit(List.of(1L, 2L, 3L)),
                "graph budget failure must not poison the next independent admission");
    }

    @Test
    void rejectsHostileCollectionSizesBeforeAnyValueReadsOrAllocation() {
        java.util.concurrent.atomic.AtomicInteger reads =
                new java.util.concurrent.atomic.AtomicInteger();
        List<Object> forged = new java.util.AbstractList<>() {
            @Override public int size() { return Integer.MAX_VALUE; }
            @Override public Object get(int index) {
                reads.incrementAndGet();
                return 1L;
            }
        };
        reject(forged);
        assertEquals(0, reads.get(), "size budget must fail before any indexed reads");
    }

    @Test
    void rejectsNullAsNotAnOption() {
        reject(null);
    }
}
