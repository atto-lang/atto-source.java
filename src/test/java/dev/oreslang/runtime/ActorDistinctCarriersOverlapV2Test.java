package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests physical overlap of two DISTINCT host actors on separate carriers.
 *
 * Does NOT show that a single actor processes overlapping receiver turns.
 * Does NOT execute compiled .atto source or establish guest heap isolation.
 */
@Timeout(35)
final class ActorDistinctCarriersOverlapV2Test {
    @Test
    void distinctSharedActorsRunHandlersConcurrentlyAndRetire() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch bothCompleted = new CountDownLatch(2);
        AtomicBoolean invalid = new AtomicBoolean(false);
        AtomicInteger running = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean(false);

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 64),
                ActorRuntime.TurnExecutor.direct())) {

            var first = runtime.<String>spawnShared(() -> (message, context) -> {
                if (!"alpha".equals(message)) invalid.set(true);
                if (running.incrementAndGet() == 2) overlapped.set(true);
                bothEntered.countDown();
                try {
                    if (!bothEntered.await(8, TimeUnit.SECONDS)) invalid.set(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    invalid.set(true);
                } finally {
                    running.decrementAndGet();
                    bothCompleted.countDown();
                }
            });
            var second = runtime.<String>spawnShared(() -> (message, context) -> {
                if (!"beta".equals(message)) invalid.set(true);
                if (running.incrementAndGet() == 2) overlapped.set(true);
                bothEntered.countDown();
                try {
                    if (!bothEntered.await(8, TimeUnit.SECONDS)) invalid.set(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    invalid.set(true);
                } finally {
                    running.decrementAndGet();
                    bothCompleted.countDown();
                }
            });

            first.send("alpha");
            second.send("beta");
            assertTrue(bothCompleted.await(12, TimeUnit.SECONDS),
                    "both real host handlers must settle");
            assertTrue(overlapped.get(),
                    "two independent actor carriers must physically overlap");
            assertFalse(invalid.get(), "carrier handler failed admission or barrier check");
            assertEquals(0, running.get(), "all receiver frames must finish");

            first.stop();
            second.stop();
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS),
                    "first actor must retire");
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS),
                    "second actor must retire");
            assertTrue(first.failure().isEmpty(), "first actor failed");
            assertTrue(second.failure().isEmpty(), "second actor failed");
        }
    }
}
