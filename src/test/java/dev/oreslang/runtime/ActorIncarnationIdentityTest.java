package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A reused ActorId or a host-forged ref must never gain a newer incarnation's
 * scheduling or lifecycle authority, even if the generation field matches.
 *
 * This intentionally uses reflection only in the host test: guest source is
 * never granted a constructor for ActorRef.
 */
@Timeout(15)
final class ActorIncarnationIdentityTest {
    @SuppressWarnings("unchecked")
    private static ActorRuntime.ActorRef<String> detachedRef(
            ActorRuntime runtime, ActorRuntime.ActorRef<String> original, long generation)
            throws Exception {
        Constructor<?> ctor = ActorRuntime.ActorRef.class.getDeclaredConstructor(
                ActorRuntime.class,
                ActorRuntime.ActorId.class,
                ActorRuntime.ActorKind.class,
                long.class);
        assertTrue(ctor.trySetAccessible(),
                "the JUnit runtime must access this package-private proof fixture");
        return (ActorRuntime.ActorRef<String>)
                ctor.newInstance(runtime, original.id(), original.kind(), generation);
    }

    @Test
    void forgedOrStaleIncarnationCannotSendCancelStopOrInspectLiveActor()
            throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> live =
                    runtime.spawnShared(() -> (message, context) -> received.countDown());

            ActorRuntime.ActorRef<String> oldGeneration =
                    detachedRef(runtime, live, live.generation() - 1);
            ActorRuntime.ActorRef<String> forgedSameGeneration =
                    detachedRef(runtime, live, live.generation());

            for (ActorRuntime.ActorRef<String> invalid :
                    java.util.List.of(oldGeneration, forgedSameGeneration)) {
                assertEquals(live.id(), invalid.id());
                assertFalse(runtime.isAlive(invalid));
                assertTrue(runtime.placementFor(invalid).isEmpty());
                assertFalse(runtime.mailboxUsesChannelTransport(invalid));
                assertThrows(ActorRuntime.ActorTerminatedException.class,
                        () -> invalid.send("stale"));
                assertFalse(runtime.cancel(invalid));
                runtime.stop(invalid);
                assertTrue(runtime.isAlive(live),
                        "invalid ref must never stop another actor incarnation");
                assertTrue(invalid.parentId().isEmpty());
                assertTrue(invalid.childIds().isEmpty());
            }

            live.send("fresh");
            assertTrue(received.await(3, TimeUnit.SECONDS),
                    "real ref must retain the only effective send authority");
            live.stop();
            assertTrue(live.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
