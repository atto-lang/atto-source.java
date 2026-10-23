package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime-only exact-message completion receipts used by logical hot-reload
 * routing. Public ActorRef send/drain semantics remain unchanged.
 */
@Timeout(20)
final class ActorTrackedMessageCompletionTest {
    @Test
    void trackedCompletionSettlesOnlyAfterItsExactAcceptedMessageProcesses()
            throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger processed = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                if (message == 1) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("first message release timed out");
                } else if (message == 2) {
                    secondEntered.countDown();
                    if (!releaseSecond.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("second message release timed out");
                }
                processed.incrementAndGet();
            });

            OresFuture<Void> first = runtime.sendWithCompletion(ref, 1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> second = runtime.sendWithCompletion(ref, 2);

            assertFalse(first.isDone());
            assertFalse(second.isDone());

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(1, processed.get());
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            assertFalse(second.isDone(),
                    "later accepted mail cannot inherit an earlier completion receipt");

            releaseSecond.countDown();
            second.get(5, TimeUnit.SECONDS);
            assertEquals(2, processed.get());

            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
    }

    @Test
    void actorStopFailsTrackedQueuedMessageInsteadOfFabricatingCompletion()
            throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                if (message == 1) {
                    firstEntered.countDown();
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
            });

            OresFuture<Void> first = runtime.sendWithCompletion(ref, 1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> queued = runtime.sendWithCompletion(ref, 2);
            assertFalse(queued.isDone());

            ref.stop();

            ExecutionException queuedFailure = assertThrows(
                    ExecutionException.class,
                    () -> queued.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorTerminatedException.class,
                    queuedFailure.getCause());
            assertTrue(first.isDone(),
                    "STOP must settle every exact-message observer fail-closed");

            releaseFirst.countDown();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }
}
