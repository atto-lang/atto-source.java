package dev.oreslang;

import org.junit.jupiter.api.Test;
import dev.oreslang.runtime.ChannelRuntime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guest write boundary must reject forged host objects even if a caller
 * circumvents the static Channel<T> representation through interop.
 */
final class ChannelRuntimeDataGuardTest {
    private static final Method CHECK_DATA = checkMethod();

    private static Method checkMethod() {
        try {
            Class<?> evaluator = Class.forName(
                    "dev.oreslang.nodes.OresEvalRootNode$Evaluator");
            Method method = evaluator.getDeclaredMethod(
                    "requireChannelDataValue", Object.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("missing guest Channel data-only guard", e);
        }
    }

    private static void accepts(Object value) {
        assertDoesNotThrow(() -> check(value));
    }

    private static IllegalArgumentException rejects(Object value) {
        return assertThrows(IllegalArgumentException.class, () -> check(value));
    }

    private static void check(Object value) {
        try {
            CHECK_DATA.invoke(null, value);
        } catch (InvocationTargetException target) {
            Throwable cause = target.getCause();
            if (cause instanceof IllegalArgumentException bad) throw bad;
            throw new AssertionError("unexpected guard failure", cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("unable to execute guest channel data policy", failure);
        }
    }

    @Test
    void ordinaryScalarTupleArrayAndClosedRecordPayloadsRemainAllowed() {
        accepts(42);
        accepts("data");
        accepts(true);
        accepts(List.of(1, 2, 3));
        accepts(new ArrayList<>(List.of("name", 5)));
        accepts(new Object[]{1, "pair"});
        accepts(Map.of("foo", "bar", "count", 7));
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("nested", List.of(Map.of("foo", "bar")));
        accepts(record);
    }

    @Test
    void callableObjectAndMutableTextObjectCannotBecomeAChannelPayload() {
        rejects((Runnable) () -> { });
        rejects(new Object());
        rejects(new StringBuilder("mutable"));
        rejects(List.of((Runnable) () -> { }));
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("callback", (Runnable) () -> { });
        assertTrue(rejects(record).getMessage().contains("callback"));
    }

    @Test
    void hostSuppliedUntrustedCollectionImplementationsAreRejected() {
        rejects(new java.util.LinkedList<>(List.of(1, 2)));
        rejects(new java.util.TreeMap<>(Map.of("count", 1)));
        rejects(Map.of(123, "not a field name"));
    }

    @Test
    void cyclesUninitializedMarkersAndNestedNullCannotCross() {
        rejects(null);
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("self", record);
        assertTrue(rejects(record).getMessage().contains("cyclic"));
        ArrayList<Object> list = new ArrayList<>();
        list.add(list);
        assertTrue(rejects(list).getMessage().contains("cyclic"));
        ArrayList<Object> withNull = new ArrayList<>();
        withNull.add(null);
        rejects(withNull);
    }
    @Test
    void sealedGuestStyleChannelRejectsBadValuesBeforeAnyWriteOrSelectRegistration() {
        ChannelRuntime.Channel<Object> channel = new ChannelRuntime.Channel<>(1,
                ChannelRuntimeDataGuardTest::check);
        assertTrue(channel.tryWrite(42));
        assertEquals(42, channel.tryRead().orElseThrow());
        Runnable callable = () -> { };
        assertThrows(IllegalArgumentException.class, () -> channel.tryWrite(callable));
        assertThrows(IllegalArgumentException.class, () -> channel.writeAsync(callable));
        assertThrows(IllegalArgumentException.class, () -> ChannelRuntime.write(channel, callable));
        assertEquals(0, channel.size());
        assertTrue(channel.tryRead().isEmpty());
    }

}
