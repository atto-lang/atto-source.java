package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pointer<T> is a local unique-owner capability. Runtime admission must deny
 * host-fabricated handles, even if guest source checking was bypassed.
 */
final class ActorPointerTransportTest {
    @Test
    void freezeRejectsPointerAndNestedPointerCapabilityGraphs() {
        var pointer = new OresPointer(new Object());
        var direct = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(pointer));
        assertTrue(direct.getMessage().contains("Pointer<T>"), direct.getMessage());

        for (Object payload : List.of(
                List.of("safe", pointer),
                Map.of("pointer", pointer),
                new Object[] {"safe", pointer})) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(payload));
            assertTrue(error.getMessage().contains("Pointer<T>"), error.getMessage());
        }

        assertEquals(List.of("safe", 7), ActorRuntime.freeze(List.of("safe", 7)));
    }

    @Test
    void hostCannotSmugglePointerIntoPrivateOrSharedActorMailboxes() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var privateActor = runtime.<Object>spawnPrivate(
                    ignored -> (message, context) -> context.self().stop());
            var sharedActor = runtime.<Object>spawnShared(
                    ignored -> (message, context) -> context.self().stop());
            var pointer = new OresPointer(new Object());

            for (var recipient : List.of(privateActor, sharedActor)) {
                for (Object value : List.of(pointer,
                        List.of("safe", pointer),
                        Map.of("pointer", pointer))) {
                    var error = assertThrows(IllegalArgumentException.class,
                            () -> recipient.send(value));
                    assertTrue(error.getMessage().contains("Pointer<T>"), error.getMessage());
                }
                // Every failed preflight must release its mailbox slot.
                recipient.send("ok");
                assertTrue(recipient.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(recipient.failure().isEmpty());
            }
        }
    }

    @Test
    void sharedMutexPayloadCannotCarryHostFabricatedPointer() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var sharedActor = runtime.<Object>spawnShared(
                    ignored -> (message, context) -> context.self().stop());
            var shared = OresMutex.shared(Map.of("p", new OresPointer(new Object())));
            var error = assertThrows(IllegalArgumentException.class,
                    () -> sharedActor.send(shared));
            assertTrue(error.getMessage().contains("Pointer<T>"), error.getMessage());
            sharedActor.send("ok");
            assertTrue(sharedActor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
