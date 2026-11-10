package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host/runtime boundary tests. These do not substitute for compiled .atto
 * actor guest execution or cross-backend evidence.
 */
final class ActorMailboxRefSecurityTest {
    @Test
    void messageViewCanSendWithoutExportingLifecycleOrControlAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<String> observed = new AtomicReference<>();
            ActorRuntime.ActorRef<String> full = runtime.spawn(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
                context.self().stop();
            });

            ActorRuntime.ActorMailboxRef<String> messages = full.messageRef();
            assertEquals(full.id(), messages.id());
            assertEquals(full.address(), messages.address());
            assertTrue(messages.isAlive());
            for (String forbidden : new String[] {
                    "stop", "cancel", "kill", "forceCancel", "gracefulStop",
                    "controlHandle", "ref", "messageRef"
            }) {
                assertFalse(Arrays.stream(messages.getClass().getMethods())
                        .anyMatch(method -> method.getName().equals(forbidden)),
                        () -> "message-only reference leaks " + forbidden);
            }
            messages.send("ping");
            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertEquals("ping", observed.get());
        }
    }

    @Test
    void evenPrivilegedHostFabricationCannotReuseAnActiveActorIdentity() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> real =
                    runtime.spawn(() -> (message, context) -> context.self().stop());

            // Intentional hostile host test: bypass Java visibility to construct
            // a reference with the SAME runtime, actor ID, kind, and generation.
            // Guest AttoLang does not have access to this reflective path.
            Constructor<?> constructor = Arrays.stream(
                            real.getClass().getDeclaredConstructors())
                    .filter(candidate -> candidate.getParameterCount() == 4)
                    .findFirst()
                    .orElseThrow();
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorRef<String> spoofed = (ActorRuntime.ActorRef<String>)
                    constructor.newInstance(
                            runtime, real.id(), real.kind(), real.generation());

            assertFalse(spoofed.isAlive());
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> spoofed.send("forged"));
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> spoofed.messageRef().send("forged"));
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    spoofed::drain);
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    spoofed::gracefulStop);
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    spoofed::stop);
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    spoofed::cancel);
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> runtime.controlHandle(spoofed));

            // Rejection does not stop or corrupt the real actor.
            assertTrue(real.isAlive());
            real.send("legitimate");
        }
    }
}
