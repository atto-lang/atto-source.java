package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trusted-host, test-only injected registry failure during ActorCell construction.
 * A throw from registry.add() may occur after it has inserted the stream,
 * before spawnInternal ever obtains a constructed ActorCell.
 */
@Timeout(25)
final class ActorRuntimeSpawnConstructionAtomicityTest {
    private static Field field(String name) throws ReflectiveOperationException {
        Field f = ActorRuntime.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @SuppressWarnings("unchecked")
    @Test
    void addThenThrowRegistryFailureDoesNotOrphanOutputStreamOrActorSlot()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            Field streamField = field("actorOutputStreams");
            Set<ActorRuntime.ActorOutputStream<?>> streams =
                    (Set<ActorRuntime.ActorOutputStream<?>>)
                            streamField.get(runtime);
            AtomicInteger slots = (AtomicInteger) field("actorCount").get(runtime);
            assertEquals(0, slots.get());
            assertTrue(streams.isEmpty());

            OutOfMemoryError injected = new OutOfMemoryError(
                    "injected stream-registry failure after insertion");
            OutOfMemoryError cleanupFault = new OutOfMemoryError(
                    "injected registry cleanup failure after removal");
            AtomicBoolean removeFault = new AtomicBoolean();
            Set<ActorRuntime.ActorOutputStream<?>> faulty = new AbstractSet<>() {
                @Override public int size() { return streams.size(); }
                @Override public Iterator<ActorRuntime.ActorOutputStream<?>>
                        iterator() { return streams.iterator(); }
                @Override public boolean add(ActorRuntime.ActorOutputStream<?> value) {
                    streams.add(value);
                    throw injected;
                }
                @Override public boolean remove(Object value) {
                    boolean removed = streams.remove(value);
                    if (removeFault.get()) throw cleanupFault;
                    return removed;
                }
            };
            try {
                streamField.set(runtime, faulty);
                Throwable caught = assertThrows(OutOfMemoryError.class,
                        () -> runtime.<Integer>spawnShared(
                                () -> (message, context) -> { }));
                assertSame(injected, caught,
                        "preserve the original post-insertion failure");
                assertEquals(0, slots.get(),
                        "a failed ActorCell constructor must release its reserved slot");
                assertTrue(streams.isEmpty(),
                        "no orphan output stream may survive a failed constructor");

                // Cleanup may also raise a fatal Error after removing the
                // registration. Keep the original allocator failure as the
                // primary reported cause; suppress cleanup, refund the slot,
                // and leave the registry empty for healthy future admissions.
                removeFault.set(true);
                OutOfMemoryError again = assertThrows(OutOfMemoryError.class,
                        () -> runtime.<Integer>spawnShared(
                                () -> (message, context) -> { }));
                assertSame(injected, again);
                assertEquals(1, again.getSuppressed().length);
                assertSame(cleanupFault, again.getSuppressed()[0]);
                assertEquals(0, slots.get());
                assertTrue(streams.isEmpty());
            } finally {
                streamField.set(runtime, streams);
            }

            // The failure was pre-physical-admission, not a permanent VM seal:
            // a healthy subsequent spawn can still register and terminate.
            var healthy = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            healthy.getReady().get(5, TimeUnit.SECONDS);
            assertEquals(1, slots.get());
            healthy.stop();
            assertTrue(healthy.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, slots.get());
        }
    }
}
