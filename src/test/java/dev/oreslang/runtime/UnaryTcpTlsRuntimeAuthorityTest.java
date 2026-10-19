package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class UnaryTcpTlsRuntimeAuthorityTest {
    @Test
    void actorTurnCannotReachTlsBindDependenciesOrSocketCreation() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    UnaryTcpTlsRuntime.bind(
                            null, null, "127.0.0.1", 0, 1,
                            null, null, null);
                    failure.set(new AssertionError(
                            "actor turn reached unary TLS bind path"));
                } catch (Throwable terminal) {
                    failure.set(terminal);
                } finally {
                    observed.countDown();
                }
            });

            actor.send("bind");
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            SecurityException denied = assertInstanceOf(
                    SecurityException.class, failure.get());
            assertTrue(denied.getMessage().contains("supervisor"));
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }
}
