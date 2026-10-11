package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Mailbox admission, processed barriers, and graceful close are distinct milestones. */
@Timeout(20)
final class ActorSendContractTest {
    @Test
    void actorRefIsOpaqueGenerationBoundAndSendAfterStopIsTyped() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger count = new AtomicInteger();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) ->
                    count.addAndGet(message));
            assertTrue(ref.generation() > 0);
            assertEquals(ref.id(), ref.address().actorId());
            assertEquals(ref.generation(), ref.address().generation());
            assertEquals("local-process", ref.address().transport());
            ref.send(1);
            ref.send(2);
            ref.drain().get(5, TimeUnit.SECONDS);
            assertEquals(3, count.get());
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
            ActorRuntime.ActorTerminatedException failure = assertThrows(
                    ActorRuntime.ActorTerminatedException.class, () -> ref.send(4));
            assertEquals(ActorRuntime.SendError.Reason.TERMINATED, failure.reason());
        }
    }

    @Test
    void gracefulStopSealsAdmissionsButWaitsForExistingMail() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger processed = new AtomicInteger();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("test actor timed out");
                processed.incrementAndGet();
            });
            ref.send(42);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> barrier = ref.drain();
            assertFalse(barrier.isDone());
            OresFuture<Void> done = ref.gracefulStop();
            ActorRuntime.ActorSealedException rejected = assertThrows(
                    ActorRuntime.ActorSealedException.class, () -> ref.send(43));
            assertEquals(ActorRuntime.SendError.Reason.SEALED, rejected.reason());
            assertFalse(done.isDone());
            release.countDown();
            barrier.get(5, TimeUnit.SECONDS);
            done.get(5, TimeUnit.SECONDS);
            assertEquals(1, processed.get());
            assertFalse(ref.isAlive());
        } finally {
            release.countDown();
        }
    }

    @Test
    void abruptStopFailsOutstandingDrainRatherThanFabricatingProcessing() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            });
            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            ref.send(2);
            OresFuture<Void> barrier = ref.drain();
            // stop cannot wait synchronously while the test deliberately holds
            // the guest turn. Unblock the carrier from another host thread.
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                release.countDown();
            });
            releaser.start();
            ref.stop();
            releaser.join();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> barrier.get(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void crossRuntimeRefDoesNotRouteAsSharedAddress() {
        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            var ref = left.<Integer>spawnPrivate(() -> (message, context) -> { });
            ActorRuntime.ActorInvalidReferenceException failure = assertThrows(
                    ActorRuntime.ActorInvalidReferenceException.class,
                    () -> right.send(ref, 7));
            assertEquals(ActorRuntime.SendError.Reason.INVALID_REFERENCE, failure.reason());
            assertNotEquals(ref.address().runtimeId(), 
                    right.<Integer>spawnPrivate(() -> (message, context) -> { })
                            .address().runtimeId());
        }
    }
}
