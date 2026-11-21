package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authenticated guest state permits may not be minted by a supervisor,
 * detached callback, or arbitrary host actor. The source guest positive
 * route is exercised in ActorCriticalStateModeTest and conformance fixtures.
 */
@Timeout(30)
final class ActorStateRuntimePermitAuthorityTest {

    @Test
    void supervisorCannotForgeActorStatePermit() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            assertThrows(SecurityException.class,
                    () -> runtime.acquireCurrentActorStatePermit(false));
            assertThrows(SecurityException.class,
                    () -> runtime.acquireCurrentActorStatePermit(true));
        }
    }

    @Test
    void ordinaryHostActorCannotForgeSourceOwnedSealedStatePermit()
            throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicBoolean rejectedRead = new AtomicBoolean();
        AtomicBoolean rejectedWrite = new AtomicBoolean();
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                try {
                    assertThrows(SecurityException.class,
                            () -> context.runtime().acquireCurrentActorStatePermit(false));
                    rejectedRead.set(true);
                    assertThrows(SecurityException.class,
                            () -> context.runtime().acquireCurrentActorStatePermit(true));
                    rejectedWrite.set(true);
                } finally {
                    attempted.countDown();
                }
            });
            actor.send(7);
            assertTrue(attempted.await(10, TimeUnit.SECONDS));
            assertTrue(rejectedRead.get());
            assertTrue(rejectedWrite.get());
            actor.stop();
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
