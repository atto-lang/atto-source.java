package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    void publicDrainQuotaCannotStarveRuntimeTrackedCompletion()
            throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger processed = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                if (message == 1) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("first message release timed out");
                }
                processed.incrementAndGet();
            });

            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

            var publicDrains = new ArrayList<OresFuture<Void>>();
            for (int i = 0; i < 1_024; i++) {
                OresFuture<Void> drain = ref.drain();
                assertFalse(drain.isDone());
                publicDrains.add(drain);
            }
            ExecutionException exhausted = assertThrows(
                    ExecutionException.class,
                    () -> ref.drain().get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorResourceExhaustedException.class,
                    exhausted.getCause());

            // Internal per-message proof has an independent actor-mailbox
            // budget and therefore cannot be starved by public observers.
            OresFuture<Void> tracked = runtime.sendWithCompletion(ref, 2);
            assertFalse(tracked.isDone());

            releaseFirst.countDown();
            for (OresFuture<Void> drain : publicDrains)
                drain.get(5, TimeUnit.SECONDS);
            tracked.get(5, TimeUnit.SECONDS);
            assertEquals(2, processed.get());

            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
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

            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            Thread stopper = new Thread(() -> {
                try {
                    ref.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            });
            stopper.start();

            ExecutionException queuedFailure = assertThrows(
                    ExecutionException.class,
                    () -> queued.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorTerminatedException.class,
                    queuedFailure.getCause());

            ExecutionException activeFailure = assertThrows(
                    ExecutionException.class,
                    () -> first.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorTerminatedException.class,
                    activeFailure.getCause());

            // Let the already-running trusted turn unwind before the host
            // stop synchronization deadline expires.
            releaseFirst.countDown();
            stopper.join(5_000);
            assertFalse(stopper.isAlive(), "host stop did not finish");
            assertNull(stopFailure.get());
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }
}
