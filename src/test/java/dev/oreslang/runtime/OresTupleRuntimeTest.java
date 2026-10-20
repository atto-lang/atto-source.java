package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresTupleRuntimeTest {
    private static final class Hostile {
        @Override public boolean equals(Object other) {
            throw new AssertionError("host equals called");
        }
        @Override public int hashCode() {
            throw new AssertionError("host hashCode called");
        }
        @Override public String toString() {
            throw new AssertionError("host toString called");
        }
    }

    @Test
    void tupleHasDistinctImmutableHostKindAndStableArity() {
        ArrayList<Object> mutable = new ArrayList<>(List.of(1, 2));
        OresTuple tuple = new OresTuple(mutable);
        mutable.add(3);

        assertEquals(2, tuple.size());
        assertEquals(1, tuple.get(0));
        assertEquals(2, tuple.get(1));
        assertThrows(UnsupportedOperationException.class, () -> tuple.elements().set(0, 9));
        assertThrows(UnsupportedOperationException.class, () -> tuple.add(9));
        assertThrows(IndexOutOfBoundsException.class, () -> tuple.get(2));

        assertEquals(0, new OresTuple(List.of()).size());
        assertEquals(1, new OresTuple(List.of("single")).size());

        assertNotEquals(tuple, List.of(1, 2));
        assertNotEquals(new OresTuple(List.of(1, 2)), tuple);
        assertNotEquals(List.of(1, 2), tuple);
        assertThrows(NullPointerException.class, () -> new OresTuple(java.util.Arrays.asList((Object) null)));
    }

    @Test
    void hostCallbacksNeverDefineTupleEqualityHashOrLogging() {
        OresTuple a = new OresTuple(List.of(new Hostile()));
        OresTuple b = new OresTuple(List.of(new Hostile()));
        assertTrue(a.equals(a));
        assertFalse(a.equals(b));
        assertNotEquals(a, b);
        assertEquals(System.identityHashCode(a), a.hashCode());
        assertEquals("OresTuple<opaque>", a.toString());
        HashSet<OresTuple> set = new HashSet<>();
        set.add(a);
        set.add(b);
        assertEquals(2, set.size());
    }

    @Test
    void actorFreezeDeepCopiesChildrenButPreservesTupleTag() {
        ArrayList<Object> array = new ArrayList<>(List.of(7, 8));
        OresTuple nested = new OresTuple(List.of(9, 10));
        OresTuple source = new OresTuple(List.of(array, nested));

        OresTuple frozen = assertInstanceOf(OresTuple.class, ActorRuntime.freeze(source));
        assertNotSame(source, frozen);
        assertInstanceOf(List.class, frozen.get(0));
        assertFalse(frozen.get(0) instanceof OresTuple);
        assertInstanceOf(OresTuple.class, frozen.get(1));
        assertNotSame(nested, frozen.get(1));
        array.add(11);
        assertEquals(List.of(7, 8), frozen.get(0));
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) frozen.get(0)).add(12));
    }

    @Test
    void privateActorCopyPreservesTupleTagAndDoesNotAliasSource() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<OresTuple> observed = new AtomicReference<>();
            var ref = runtime.<OresTuple>spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factoryContext -> (message, context) -> {
                        observed.set(message);
                        latch.countDown();
                        context.self().stop();
                    });

            ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
            OresTuple source = new OresTuple(List.of(mutable, new OresTuple(List.of(3))));
            ref.send(source);
            mutable.add(4);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            OresTuple copied = assertInstanceOf(OresTuple.class, observed.get());
            assertNotSame(source, copied);
            assertEquals(List.of(1, 2), copied.get(0));
            assertInstanceOf(OresTuple.class, copied.get(1));
        }
    }

    @Test
    void tupleCannotHideActorCapabilitiesInDataOnlyFreeze() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawn(() -> (message, context) -> context.self().stop());
            OresTuple tuple = new OresTuple(List.of(new OresTuple(List.of(actor))));
            assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(tuple));
            actor.stop();
        }
    }
}
