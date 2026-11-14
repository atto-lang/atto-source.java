package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real JVM ActorRuntime host-regression: two independent actor instances,
 * one ordinary host failure, no compiler-backed AttoLang guest claim.
 */
final class ActorSiblingFailureHostTest {
    @Test
    void oneFailedPrivateActorDoesNotRetireHealthySiblingOrReuseItsMailbox() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(2, 2, 16);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var firstReply = new CountDownLatch(1);
            var secondReply = new CountDownLatch(1);
            var witnessReplies = new AtomicInteger();

            var witness = runtime.<Integer>spawnPrivate(
                    () -> (message, context) -> {
                        context.privateMemory().orElseThrow();
                        witnessReplies.addAndGet(message);
                        if (message == 1) firstReply.countDown();
                        if (message == 2) secondReply.countDown();
                    });
            var faulting = runtime.<Integer>spawnPrivate(
                    () -> (message, context) -> {
                        context.privateMemory().orElseThrow();
                        throw new IllegalStateException("actor-local-host-failure");
                    });

            witness.send(1);
            assertTrue(firstReply.await(2, TimeUnit.SECONDS));
            faulting.send(99);
            assertTrue(faulting.awaitTermination(2, TimeUnit.SECONDS),
                    "faulting actor did not terminate");
            assertFalse(faulting.isAlive());
            assertInstanceOf(IllegalStateException.class,
                    faulting.failure().orElseThrow());
            assertEquals("actor-local-host-failure",
                    faulting.failure().orElseThrow().getMessage());
            assertThrows(ActorRuntime.ActorTerminatedException.class,
                    () -> faulting.send(100));

            witness.send(2);
            assertTrue(secondReply.await(2, TimeUnit.SECONDS),
                    "separate witness stopped progressing after sibling failure");
            assertTrue(witness.isAlive());
            assertEquals(3, witnessReplies.get(),
                    "witness mailbox deliveries were lost or replayed");
            witness.stop();
            assertTrue(witness.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
