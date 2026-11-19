package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Verifies actual actor-turn identity, not a caller-controlled sender label. */
final class TrustedNetworkIngressGuardTest {
    private static volatile AtomicReference<Throwable> observed;
    private static volatile CountDownLatch attempted;

    @Test
    void actualUntrustedActorTurnCannotUseUnmediatedNetwork() throws Exception {
        observed = new AtomicReference<>();
        attempted = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) -> {
                try {
                    TrustedNetworkIngressGuard.rejectUntrustedDirectNetwork("http.GET");
                } catch (Throwable denied) {
                    observed.set(denied);
                } finally {
                    attempted.countDown();
                }
            });
            actor.send("attempt");
            assertTrue(attempted.await(3, TimeUnit.SECONDS));
            assertTrue(observed.get() instanceof SecurityException);
        } finally {
            observed = null;
            attempted = null;
        }
    }

    @Test
    void trustedHostContextIsNotMistakenForUntrustedActor() {
        assertEquals(null, ActorRuntime.currentActorKind());
        TrustedNetworkIngressGuard.rejectUntrustedDirectNetwork("http.GET");
    }
}
