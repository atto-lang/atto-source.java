package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Security regression for accidentally resolving an ActorRef by ActorId alone.
 *
 * This deliberately constructs a same-runtime, same-id, same-generation ref
 * through HOST TEST reflection. Guest Atto cannot mint ActorRef values.
 * The runtime must check actual ActorCell.ref identity before performing
 * a lifecycle operation, accepting data or disclosing actor hierarchy.
 */
@Timeout(20)
final class ActorRefIncarnationFenceTest {
    @SuppressWarnings("unchecked")
    private static <T> ActorRuntime.ActorRef<T> shadow(
            ActorRuntime runtime,
            ActorRuntime.ActorRef<T> live) throws ReflectiveOperationException {
        Constructor<?> constructor = ActorRuntime.ActorRef.class.getDeclaredConstructor(
                ActorRuntime.class, ActorRuntime.ActorId.class,
                ActorRuntime.ActorKind.class, long.class);
        constructor.setAccessible(true);
        return (ActorRuntime.ActorRef<T>) constructor.newInstance(
                runtime, live.id(), live.kind(), live.generation());
    }

    @Test
    void sameIdAndGenerationAreNotEnoughToSendOrControlAnotherActor() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 64))) {
            ActorRuntime.ActorRef<Integer> live = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        assertEquals(17, message);
                        delivered.countDown();
                    });
            live.ready().get(3, TimeUnit.SECONDS);
            ActorRuntime.ActorRef<Integer> shadow = shadow(runtime, live);
            assertNotSame(live, shadow);
            assertEquals(live.id(), shadow.id());
            assertEquals(live.generation(), shadow.generation());
            assertFalse(shadow.isAlive());

            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> shadow.send(99));
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> shadow.sendAsync(99));
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> runtime.stop(shadow));
            assertFalse(shadow.cancel());
            assertTrue(shadow.parentId().isEmpty());
            assertTrue(shadow.childIds().isEmpty());
            assertTrue(shadow.awaitTermination(0, TimeUnit.NANOSECONDS));
            assertTrue(runtime.placementFor(shadow).isEmpty());
            assertFalse(runtime.mailboxUsesChannelTransport(shadow));
            assertTrue(runtime.codeImageFor(shadow).isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> runtime.heapStatusFor(shadow));

            CompletionException readiness = assertThrows(
                    CompletionException.class, () -> shadow.ready().join());
            assertInstanceOf(ActorRuntime.ActorTerminatedException.class,
                    readiness.getCause());
            CompletionException drain = assertThrows(
                    CompletionException.class, () -> shadow.drain().join());
            assertInstanceOf(ActorRuntime.ActorTerminatedException.class,
                    drain.getCause());
            CompletionException gracefulStop = assertThrows(
                    CompletionException.class, () -> shadow.gracefulStop().join());
            assertInstanceOf(ActorRuntime.ActorTerminatedException.class,
                    gracefulStop.getCause());

            // Rejected shadow operations cannot affect or consume a live turn.
            assertTrue(live.isAlive());
            live.send(17);
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            runtime.stop(live);
        }
    }
}
