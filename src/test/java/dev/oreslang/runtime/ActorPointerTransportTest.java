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
    private enum HiddenOwner {
        VALUE;
        final Object hidden = new OresPointer(new Object());
    }

    private static final class HiddenInteger extends java.math.BigInteger {
        final Object hidden = new OresPointer(new Object());
        HiddenInteger() { super("7"); }
    }

    @Test
    void nominalScalarSupertypesDoNotProveTransitiveImmutability() throws Exception {
        for (Object value : List.of(HiddenOwner.VALUE, new HiddenInteger())) {
            assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(value));
            assertThrows(IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(Map.of("nested", List.of(value))));
            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
                var actor = runtime.<Object>spawnPrivate(
                        ignored -> (message, context) -> context.self().stop());
                assertThrows(IllegalArgumentException.class, () -> actor.send(value));
                actor.send("ok");
                assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(actor.failure().isEmpty());
            }
        }
        assertEquals(java.math.BigInteger.TEN, ActorRuntime.freeze(java.math.BigInteger.TEN));
        assertEquals(java.math.BigDecimal.TEN, ActorRuntime.freeze(java.math.BigDecimal.TEN));
    }

    @Test
    void localChannelPreservesPointeeIdentityButDoesNotGrantAnotherDomainAccess() throws Exception {
        Object pointee = new Object();
        var pointer = new OresPointer(pointee);
        var channel = new ChannelRuntime.Channel<OresPointer>(1);
        assertTrue(channel.tryWrite(pointer));
        var received = channel.tryRead().orElseThrow();
        assertSame(pointer, received);
        assertSame(pointee, received.target());
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread other = new Thread(() -> {
            try { received.target(); }
            catch (Throwable error) { failure.set(error); }
        });
        other.start();
        other.join(2000);
        assertFalse(other.isAlive());
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertSame(pointee, pointer.target());
    }

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
