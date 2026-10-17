package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ActorDrainObserverScalingTest {
    @Test
    void manyMonotonicDrainObserversCompleteAndDisappear() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IsolatePolicy policy = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                64L * 1024 * 1024,
                4,
                Duration.ofSeconds(5),
                false);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    policy,
                    () -> (message, context) -> {
                        if (message.equals("hold")) {
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "drain observer release timed out");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                        }
                    });

            actor.send("hold");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            actor.send("two");
            actor.send("three");

            ArrayList<OresFuture<Void>> drains = new ArrayList<>();
            for (int i = 0; i < 256; i++) drains.add(actor.drain());

            ActorRuntime.ActorHealthSnapshot blocked =
                    runtime.actorHealth(actor).orElseThrow();
            assertEquals(256, blocked.pendingDrainWaiters());
            assertEquals(3L, blocked.outstandingAcceptedMessages());

            release.countDown();
            for (OresFuture<Void> drain : drains) {
                drain.get(5, TimeUnit.SECONDS);
            }

            ActorRuntime.ActorHealthSnapshot completed =
                    runtime.actorHealth(actor).orElseThrow();
            assertEquals(0, completed.pendingDrainWaiters());
            assertEquals(0L, completed.outstandingAcceptedMessages());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }
}
