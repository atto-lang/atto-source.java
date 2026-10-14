package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end actor-owned STOP continuation tests, not merely scheduler tests. */
@Timeout(20)
final class ActorStopLexicalCleanupTest {
    private static void awaitRegistration(OresFuture<?> awaited) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (awaited.pendingRuntimeWaiterCount() == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(1, awaited.pendingRuntimeWaiterCount(),
                "test must cancel only after the source await registration");
    }

    @Test
    void stopRunsAwaitedCleanupOnActorLaneBeforePublishingDone() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 2, 64, 16))) {
            OresFuture<Integer> blockedProducer = new OresFuture<>();
            OresFuture<Integer> cleanupGate = new OresFuture<>();
            CountDownLatch originalFrame = new CountDownLatch(1);
            CountDownLatch cleanupStarted = new CountDownLatch(1);
            AtomicInteger turns = new AtomicInteger();
            AtomicReference<OresFuture<Integer>> source = new AtomicReference<>();
            AtomicReference<Thread> firstCarrier = new AtomicReference<>();
            AtomicReference<Thread> cleanupCarrier = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        source.set(context.runtime().startActorTask(
                                (OresScheduler.CleanupAwareTask<Integer>) resume -> {
                                    int step = turns.getAndIncrement();
                                    assertNotNull(OresScheduler.current(),
                                            "cleanup runs on the source task scheduler");
                                    if (step == 0) {
                                        firstCarrier.set(Thread.currentThread());
                                        originalFrame.countDown();
                                        return OresScheduler.await(blockedProducer);
                                    }
                                    if (step == 1) {
                                        assertInstanceOf(
                                                java.util.concurrent.CancellationException.class,
                                                resume.failure());
                                        assertTrue(OresScheduler.currentTaskInCleanup());
                                        cleanupCarrier.set(Thread.currentThread());
                                        cleanupStarted.countDown();
                                        return OresScheduler.await(cleanupGate);
                                    }
                                    assertEquals(2, step);
                                    assertTrue(OresScheduler.currentTaskInCleanup());
                                    assertEquals(37, resume.value());
                                    return OresScheduler.done(123);
                                }));
                    });
            actor.send("start");
            assertTrue(originalFrame.await(5, TimeUnit.SECONDS));
            awaitRegistration(blockedProducer);
            assertTrue(actor.cancel());
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS),
                    "cancelled source frame must unwind on its actor lane");
            assertFalse(actor.done().isDone(),
                    "get_done must not settle while the final await is pending");
            assertFalse(source.get().isDone(),
                    "Future cancellation remains deferred until lexical cleanup exits");
            assertEquals(0, blockedProducer.pendingRuntimeWaiterCount());
            blockedProducer.completeFromRuntime(99);
            assertEquals(2, turns.get(), "late producer may not replay the actor receive");
            cleanupGate.completeFromRuntime(37);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(3, turns.get(), "every cleanup continuation executes once");
            assertTrue(source.get().isCancelled());
            assertTrue(actor.done().isDone());
            assertNotNull(firstCarrier.get());
            assertNotNull(cleanupCarrier.get());
        }
    }

    @Test
    void stoppedActorNeverStartsPreviouslyQueuedCleanupAwareTask() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 2, 64, 16))) {
            AtomicInteger entered = new AtomicInteger();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(() -> (msg, ctx) -> {
                for (int i = 0; i < 128; ++i) {
                    ctx.runtime().startActorTask(
                            (OresScheduler.CleanupAwareTask<Integer>) resume -> {
                                entered.incrementAndGet();
                                return OresScheduler.done(1);
                            });
                }
                ctx.self().stop();
            });
            actor.send(1);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, entered.get(),
                    "an unentered source frame has no cleanup right after STOP");
            assertTrue(actor.failure().isEmpty());
        }
    }


    @Test
    void cleanupDeadlineRevokesAbandonedAwaitWithoutOffLaneGuestReplay()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 2, 64, 16))) {
            OresFuture<Integer> producer = new OresFuture<>();
            OresFuture<Integer> neverFinishes = new OresFuture<>();
            CountDownLatch sourceEntered = new CountDownLatch(1);
            CountDownLatch cleanupStarted = new CountDownLatch(1);
            AtomicInteger turns = new AtomicInteger();
            AtomicReference<OresFuture<Integer>> task = new AtomicReference<>();
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (msg, ctx) -> {
                task.set(ctx.runtime().startActorTask(
                        (OresScheduler.CleanupAwareTask<Integer>) resume -> {
                            int pc = turns.getAndIncrement();
                            if (pc == 0) {
                                sourceEntered.countDown();
                                return OresScheduler.await(producer);
                            }
                            if (pc == 1) {
                                assertTrue(OresScheduler.currentTaskInCleanup());
                                cleanupStarted.countDown();
                                return OresScheduler.await(neverFinishes);
                            }
                            fail("a forcibly revoked cleanup await may not run guest code");
                            return OresScheduler.done(0);
                        }));
            });
            actor.send("start");
            assertTrue(sourceEntered.await(5, TimeUnit.SECONDS));
            awaitRegistration(producer);
            assertTrue(actor.cancel());
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS));
            assertFalse(actor.done().isDone());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "finite STOP cleanup deadline must eventually release the actor");
            assertTrue(actor.done().isDone());
            assertTrue(task.get().isDone());
            assertEquals(0, neverFinishes.pendingRuntimeWaiterCount(),
                    "deadline revocation detaches the abandoned cleanup waiter");
            producer.completeFromRuntime(10);
            neverFinishes.completeFromRuntime(20);
            assertEquals(2, turns.get(),
                    "neither abandoned Future may resurrect stopped actor code");
        }
    }

    @Test
    void wrappedFatalCleanupFailureOverridesEarlierActorCancellation()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 2, 64, 16))) {
            OresFuture<Integer> suspended = new OresFuture<>();
            CountDownLatch sourceStarted = new CountDownLatch(1);
            LinkageError fatal = new LinkageError("fatal guest cleanup");
            AtomicInteger turns = new AtomicInteger();
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (msg, ctx) -> {
                        ctx.runtime().startActorTask(
                                (OresScheduler.CleanupAwareTask<Integer>) resume -> {
                                    if (turns.getAndIncrement() == 0) {
                                        sourceStarted.countDown();
                                        return OresScheduler.await(suspended);
                                    }
                                    assertTrue(OresScheduler.currentTaskInCleanup());
                                    throw new java.util.concurrent.CompletionException(fatal);
                                });
                    });
            actor.send("start");
            assertTrue(sourceStarted.await(5, TimeUnit.SECONDS));
            awaitRegistration(suspended);
            assertTrue(actor.cancel());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertSame(fatal, actor.failure().orElse(null),
                    "fatal cleanup must outrank preexisting structured cancellation");
            assertEquals(2, turns.get());
            suspended.completeFromRuntime(1);
            assertEquals(2, turns.get(), "late completion cannot replay fatal cleanup");
        }
    }
}
