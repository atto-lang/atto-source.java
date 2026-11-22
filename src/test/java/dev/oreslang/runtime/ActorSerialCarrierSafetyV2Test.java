package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Physical JVM host-only evidence: a single serial actor must never run
 * overlapping callbacks even when two dispatcher carriers are available.
 * This is not compiled Atto guest or hard-realtime/AOT evidence.
 */
@Timeout(35)
final class ActorSerialCarrierSafetyV2Test {
    private static final int MESSAGES = 48;

    @Test
    void singleSharedActorRemainsSerialWithTwoCarriersAndRejectsPostRetireSends()
            throws Exception {
        CountDownLatch completed = new CountDownLatch(MESSAGES);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger highestInFlight = new AtomicInteger();
        AtomicInteger arrivals = new AtomicInteger();
        AtomicLongArray sequences = new AtomicLongArray(MESSAGES);
        AtomicBoolean invalid = new AtomicBoolean(false);

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 64),
                ActorRuntime.TurnExecutor.direct())) {

            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> {
                int active = inFlight.incrementAndGet();
                highestInFlight.accumulateAndGet(active, Math::max);
                try {
                    int index = arrivals.getAndIncrement();
                    if (index >= MESSAGES || message != index) {
                        invalid.set(true);
                    } else {
                        var mail = context.currentMail().orElseThrow();
                        if (mail.sequence() != index || mail.value() != index) {
                            invalid.set(true);
                        }
                        sequences.set(index, mail.sequence());
                    }
                    // Yield the CPU while the other carrier is available.
                    Thread.sleep(1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    invalid.set(true);
                } finally {
                    inFlight.decrementAndGet();
                    completed.countDown();
                }
            });

            for (int i = 0; i < MESSAGES; ++i) {
                actor.send(i);
            }
            assertTrue(completed.await(15, TimeUnit.SECONDS), "all accepted DATA must complete");
            actor.stop();
            assertTrue(actor.awaitTermination(8, TimeUnit.SECONDS), "actor must physically retire");
            assertTrue(actor.failure().isEmpty(), "host actor must not fail");
            assertThrows(RuntimeException.class, () -> actor.send(123456),
                    "post-retirement DATA admission must be rejected");
        }

        assertEquals(MESSAGES, arrivals.get(), "all DATA messages must enter exactly once");
        assertEquals(1, highestInFlight.get(), "one actor must not overlap receiver frames");
        assertEquals(0, inFlight.get(), "no active receiver may survive retirement");
        assertFalse(invalid.get(), "FIFO mail sequence and delivery must be exact");
        for (int i = 0; i < MESSAGES; ++i) {
            assertEquals(i, sequences.get(i), "mailbox delivery must be in FIFO order");
        }
    }
}
