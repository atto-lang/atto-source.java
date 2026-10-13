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

    private static final Method SNAPSHOT_DATA = snapshotMethod();

    private static Method snapshotMethod() {
        try {
            Class<?> evaluator = Class.forName(
                    "dev.oreslang.nodes.OresEvalRootNode$Evaluator");
            Method method = evaluator.getDeclaredMethod(
                    "snapshotChannelDataValue", Object.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("missing channel snapshot implementation", failure);
        }
    }

    private static Object snapshot(Object value) {
        try {
            return SNAPSHOT_DATA.invoke(null, value);
        } catch (InvocationTargetException thrown) {
            if (thrown.getCause() instanceof IllegalArgumentException bad) throw bad;
            throw new AssertionError("snapshot failed", thrown.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("snapshot invocation failed", failure);
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
    void guestChannelPolicyIsDistinctFromPrivilegedInternalMailboxChannel() {
        ChannelRuntime.Channel<Object> internal = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Object> guest = new ChannelRuntime.Channel<>(
                1, ChannelRuntimeDataGuardTest::check,
                ChannelRuntimeDataGuardTest::snapshot);
        assertFalse(internal.hasGuestDataPolicy());
        assertTrue(guest.hasGuestDataPolicy());
        assertThrows(SecurityException.class,
                () -> checkGuestChannel(internal, "readch"));
        assertDoesNotThrow(() -> checkGuestChannel(guest, "readch"));
    }

    private static void checkGuestChannel(
            ChannelRuntime.Channel<?> channel, String operation) {
        try {
            Class<?> evaluator = Class.forName(
                    "dev.oreslang.nodes.OresEvalRootNode$Evaluator");
            Method method = evaluator.getDeclaredMethod(
                    "requireGuestDataChannel", ChannelRuntime.Channel.class, String.class);
            method.setAccessible(true);
            method.invoke(null, channel, operation);
        } catch (InvocationTargetException invocation) {
            if (invocation.getCause() instanceof SecurityException denied) throw denied;
            throw new AssertionError("unexpected guest-channel failure", invocation.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("missing guest channel sealing gate", failure);
        }
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

    @Test
    void sealedChannelDetachesNestedDataBeforeBuffering() {
        ChannelRuntime.Channel<Object> channel = new ChannelRuntime.Channel<>(
                1, ChannelRuntimeDataGuardTest::check, ChannelRuntimeDataGuardTest::snapshot);
        ArrayList<Object> names = new ArrayList<>(List.of("before"));
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("names", names);
        assertTrue(channel.tryWrite(record));
        names.add((Runnable) () -> { });
        record.put("late", new Object());

        @SuppressWarnings("unchecked")
        Map<String, Object> received = (Map<String, Object>) channel.tryRead().orElseThrow();
        assertEquals(List.of("before"), received.get("names"));
        assertFalse(received.containsKey("late"));
    }

    @Test
    void dynamicSelectWriteCapturesDetachedValueAtCaseCreation() {
        ChannelRuntime.Channel<Object> channel = new ChannelRuntime.Channel<>(
                1, ChannelRuntimeDataGuardTest::check, ChannelRuntimeDataGuardTest::snapshot);
        ArrayList<Object> source = new ArrayList<>(List.of("first"));
        ChannelRuntime.WriteCase<Object> write = ChannelRuntime.write(channel, source);
        source.set(0, "after");
        assertEquals(List.of("first"), write.value());
        assertTrue(ChannelRuntime.SelectSet.of(write).trySelect().isPresent());
        assertEquals(List.of("first"), channel.tryRead().orElseThrow());
    }

    @Test
    void reusedSelectSetResnapshotsEachDeliveryRatherThanSharingReaderMutation() {
        ChannelRuntime.Channel<Object> channel = new ChannelRuntime.Channel<>(
                1, ChannelRuntimeDataGuardTest::check, ChannelRuntimeDataGuardTest::snapshot);
        ArrayList<Object> source = new ArrayList<>(List.of("original"));
        ChannelRuntime.SelectSet set =
                ChannelRuntime.SelectSet.of(ChannelRuntime.write(channel, source));

        assertTrue(set.trySelect().isPresent());
        @SuppressWarnings("unchecked")
        List<Object> first = (List<Object>) channel.tryRead().orElseThrow();
        first.set(0, "receiver changed this");

        assertTrue(set.trySelect().isPresent());
        assertEquals(List.of("original"), channel.tryRead().orElseThrow());
    }

}
