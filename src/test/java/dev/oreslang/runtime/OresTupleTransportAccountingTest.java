package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class OresTupleTransportAccountingTest {
    @Test
    void tupleWrapperIsChargedAboveEquivalentListAcrossAllAccountingPaths() throws Exception {
        Object tuple = new OresTuple(List.of(7L, 9L));
        Object list = List.of(7L, 9L);

        assertWrapperPremium("estimateSharedTransportBytes", tuple, list, true);
        assertWrapperPremium("estimatePrivateTransportBytes", tuple, list, true);
        assertWrapperPremium("estimateSharedMailboxBytes", tuple, list, false);
        assertWrapperPremium("estimateFrozenBytes", tuple, list, false);
    }

    @Test
    void nestedTupleWrappersAccumulateInsteadOfCollapsingIntoListAccounting() throws Exception {
        Object tuple = new OresTuple(List.of(new OresTuple(List.of(1L))));
        Object list = List.of(List.of(1L));

        for (String name : List.of(
                "estimateSharedTransportBytes",
                "estimatePrivateTransportBytes",
                "estimateSharedMailboxBytes",
                "estimateFrozenBytes")) {
            long tupleBytes = invoke(name, tuple, name.contains("Transport"));
            long listBytes = invoke(name, list, name.contains("Transport"));
            assertTrue(tupleBytes >= listBytes + 48L,
                    () -> name + " must charge both tuple wrappers: tuple="
                            + tupleBytes + " list=" + listBytes);
        }
    }

    private static void assertWrapperPremium(
            String method,
            Object tuple,
            Object list,
            boolean limited) throws Exception {
        long tupleBytes = invoke(method, tuple, limited);
        long listBytes = invoke(method, list, limited);
        assertTrue(tupleBytes >= listBytes + 24L,
                () -> method + " must charge the nominal tuple wrapper: tuple="
                        + tupleBytes + " list=" + listBytes);
    }

    private static long invoke(String name, Object value, boolean limited) throws Exception {
        Method method;
        Object result;
        if (limited) {
            method = ActorRuntime.class.getDeclaredMethod(
                    name,
                    Object.class,
                    IdentityHashMap.class,
                    int.class,
                    long.class);
            method.setAccessible(true);
            result = method.invoke(
                    null,
                    value,
                    new IdentityHashMap<>(),
                    0,
                    Long.MAX_VALUE);
        } else {
            method = ActorRuntime.class.getDeclaredMethod(
                    name,
                    Object.class,
                    IdentityHashMap.class,
                    int.class);
            method.setAccessible(true);
            result = method.invoke(
                    null,
                    value,
                    new IdentityHashMap<>(),
                    0);
        }
        return (long) result;
    }
}
