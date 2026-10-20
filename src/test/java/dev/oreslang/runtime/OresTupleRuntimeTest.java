package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresTupleRuntimeTest {
    @Test
    void tupleOwnsImmutableSlotsAndHostMethodsNeverInspectPayloads() {
        final class Hostile {
            @Override public boolean equals(Object other) {
                throw new AssertionError("host equals callback");
            }
            @Override public int hashCode() {
                throw new AssertionError("host hash callback");
            }
            @Override public String toString() {
                throw new AssertionError("host toString callback");
            }
        }
        Object hostile = new Hostile();
        ArrayList<Object> input = new ArrayList<>(List.of("value", hostile));
        OresTuple tuple = new OresTuple(input);
        input.set(0, "changed");
        assertEquals("value", tuple.get(0));
        assertSame(hostile, tuple.get(1));
        assertEquals(2, tuple.size());
        assertSame(tuple, tuple);
        assertNotEquals(tuple, new OresTuple(List.of("value", hostile)));
        assertFalse(tuple.equals(List.of("value", hostile)));
        assertEquals(System.identityHashCode(tuple), tuple.hashCode());
        assertEquals("OresTuple<opaque>", tuple.toString());
        assertThrows(UnsupportedOperationException.class, () -> tuple.set(0, "changed"));
        assertThrows(UnsupportedOperationException.class, () -> tuple.add("extra"));
        assertThrows(UnsupportedOperationException.class, () -> tuple.remove(0));
        assertSame(hostile, tuple.get(1));
    }

    @Test
    void zeroAndSingleSlotTuplesKeepNominalTag() {
        assertTrue(new OresTuple(List.of()).isEmpty());
        OresTuple singleton = new OresTuple(List.of(7));
        assertEquals(7, singleton.getFirst());
        assertThrows(IndexOutOfBoundsException.class, () -> singleton.get(1));
        assertThrows(NullPointerException.class, () -> new OresTuple(null));
        assertThrows(NullPointerException.class,
                () -> new OresTuple(java.util.Arrays.asList("valid", null)));
    }

    @Test
    void sharedFreezePreservesNestedTupleTagAndDetachesMutableChildren() {
        ArrayList<Object> mutable = new ArrayList<>(List.of("before"));
        OresTuple tuple = new OresTuple(List.of(mutable, new OresTuple(List.of(5, 6))));
        OresEntry enclosing = new OresEntry("nested", tuple);
        OresEntry frozen = assertInstanceOf(OresEntry.class, ActorRuntime.freeze(enclosing));
        OresTuple copy = assertInstanceOf(OresTuple.class, frozen.value());
        assertNotSame(tuple, copy);
        OresTuple nested = assertInstanceOf(OresTuple.class, copy.get(1));
        assertNotSame(tuple.get(1), nested);
        mutable.add("after");
        assertEquals(List.of("before"), copy.get(0));
        assertEquals(5, nested.get(0));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) copy.get(0)).add("mutation"));
    }

    @Test
    void privateActorCopyPreservesTupleTagAndNestedEntry() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<OresTuple> observed = new AtomicReference<>();
            var ref = runtime.<OresTuple>spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factoryContext -> (message, context) -> {
                        observed.set(message);
                        received.countDown();
                        context.self().stop();
                    });

            ArrayList<Object> mutable = new ArrayList<>(List.of(1, 2));
            OresTuple sent = new OresTuple(List.of(
                    new OresEntry("key", new OresTuple(List.of("value"))),
                    mutable));
            ref.send(sent);
            mutable.add(3);
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            OresTuple delivered = observed.get();
            assertNotNull(delivered);
            assertNotSame(sent, delivered);
            OresEntry entry = assertInstanceOf(OresEntry.class, delivered.get(0));
            assertInstanceOf(OresTuple.class, entry.value());
            assertEquals(List.of(1, 2), delivered.get(1));
        }
    }

    @Test
    void nestedCapabilitiesStillFailDataOnlyFreeze() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var recipient = runtime.<String>spawn(
                    () -> (message, context) -> context.self().stop());
            OresTuple tuple = new OresTuple(List.of(new OresEntry("unsafe", recipient)));
            assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(tuple));
            recipient.stop();
        }
    }
}
