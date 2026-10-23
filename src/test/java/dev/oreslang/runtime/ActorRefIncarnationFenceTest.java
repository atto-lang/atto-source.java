package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorRefIncarnationFenceTest {

    @SuppressWarnings("unchecked")
    private static <M> ActorRuntime.ActorRef<M> forgedAlias(
            ActorRuntime runtime,
            ActorRuntime.ActorRef<M> source,
            long generation) throws Exception {
        Constructor<?> ctor = ActorRuntime.ActorRef.class.getDeclaredConstructor(
                ActorRuntime.class,
                ActorRuntime.ActorId.class,
                ActorRuntime.ActorKind.class,
                long.class);
        ctor.setAccessible(true);
        return (ActorRuntime.ActorRef<M>) ctor.newInstance(
                runtime, source.id(), source.kind(), generation);
    }

    @Test
    void actorRefAuthorityRequiresExactIncarnationCapability() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> live = runtime.spawnShared(
                    () -> (message, context) -> delivered.incrementAndGet());
            live.getReady().get(3, TimeUnit.SECONDS);

            ActorRuntime.ActorRef<String> forgedSame =
                    forgedAlias(runtime, live, live.generation());
            ActorRuntime.ActorRef<String> forgedNext =
                    forgedAlias(runtime, live, live.generation() + 1);

            assertFalse(forgedSame.isAlive());
            assertFalse(forgedNext.isAlive());
            assertTrue(runtime.snapshot(forgedSame).isEmpty());
            assertTrue(runtime.placementFor(forgedSame).isEmpty());

            long invalidBefore =
                    runtime.runtimeStats().invalidReferenceSendRejections();
            assertThrows(
                    ActorRuntime.ActorInvalidReferenceException.class,
                    () -> forgedSame.send("stale"));
            assertThrows(
                    ActorRuntime.ActorInvalidReferenceException.class,
                    () -> forgedNext.send("future-generation"));
            assertEquals(
                    invalidBefore + 2,
                    runtime.runtimeStats().invalidReferenceSendRejections());

            // Lifecycle calls through an alias must never reach the live actor.
            forgedSame.stop();
            assertFalse(forgedSame.cancel());
            assertTrue(live.isAlive());

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.join(live, join);
            assertTrue(group.contains(live));
            assertFalse(group.contains(forgedSame));
            assertThrows(
                    ActorRuntime.ActorInvalidReferenceException.class,
                    () -> group.join(forgedSame, join));

            live.send("live");
            live.drain().get(3, TimeUnit.SECONDS);
            assertEquals(1, delivered.get());
            group.close();
        }
    }

    @Test
    void continuationTargetRetainsGenerationIdentity() throws Exception {
        AtomicReference<ActorRuntime.ContinuationTarget> captured =
                new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(
                    () -> (message, context) ->
                            captured.set(runtime.captureCurrentContinuationTarget()));
            actor.send("capture");
            actor.drain().get(3, TimeUnit.SECONDS);

            ActorRuntime.ContinuationTarget target = captured.get();
            assertNotNull(target);
            assertEquals(actor.id(), target.actorId());
            assertEquals(actor.generation(), target.generation());
        }
    }
}
