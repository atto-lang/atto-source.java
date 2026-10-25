package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host identity-backed containers can have two distinct objects representing
 * the same canonical Symbol. Actor snapshots must reject key/element collisions.
 */
class OresSymbolSnapshotKeyCollisionTest {
    private static Object privateSnapshot(Object value) throws ReflectiveOperationException {
        Method copy = ActorRuntime.class.getDeclaredMethod("isolateCopy", Object.class);
        copy.setAccessible(true);
        return copy.invoke(null, value);
    }

    private static void assertPrivateCopyRejects(Object value) {
        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class, () -> privateSnapshot(value));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("duplicate logical"));
    }

    @Test void duplicateNfcEqualSymbolMapKeysAreNotSilentlyOverwritten() {
        Map<Object, Object> input = new IdentityHashMap<>();
        input.put(OresSymbol.process("café"), 1L);
        input.put(OresSymbol.process("cafe\u0301"), 2L);
        assertEquals(2, input.size());

        IllegalArgumentException shared = assertThrows(
                IllegalArgumentException.class, () -> ActorRuntime.freeze(input));
        assertTrue(shared.getMessage().contains("duplicate logical Map key"));
        assertPrivateCopyRejects(input);
    }

    @Test void duplicateNfcEqualSymbolSetElementsAreNotSilentlyCollapsed() {
        Set<Object> input = Collections.newSetFromMap(new IdentityHashMap<>());
        input.add(OresSymbol.process("café"));
        input.add(OresSymbol.process("cafe\u0301"));
        assertEquals(2, input.size());

        IllegalArgumentException shared = assertThrows(
                IllegalArgumentException.class, () -> ActorRuntime.freeze(input));
        assertTrue(shared.getMessage().contains("duplicate logical Set element"));
        assertPrivateCopyRejects(input);
    }

    @Test void ordinaryStringAndSymbolNamespaceRemainDistinct() throws Exception {
        Map<Object, Object> input = new LinkedHashMap<>();
        input.put("ready", 1L);
        input.put(OresSymbol.process("ready"), 2L);
        input.put(OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000"), 3L);
        input.put(OresSymbol.process("550e8400-e29b-41d4-a716-446655440000"), 4L);

        for (Object snapshot : new Object[]{
                ActorRuntime.freeze(input),
                privateSnapshot(input)}) {
            assertInstanceOf(Map.class, snapshot);
            Map<?, ?> output = (Map<?, ?>) snapshot;
            assertEquals(4, output.size());
            assertEquals(1L, output.get("ready"));
            assertEquals(2L, output.get(OresSymbol.process("ready")));
            assertEquals(3L, output.get(OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000")));
            assertEquals(4L, output.get(OresSymbol.process("550e8400-e29b-41d4-a716-446655440000")));
        }
    }
}
