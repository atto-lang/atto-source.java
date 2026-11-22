package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests the real ActorCell -> mailbox-backed OresScheduler -> completion path.
 * The Task is a host-authored stand-in for compiler lowering; it does NOT
 * establish parsing or compilation of a suspended Oreslang actor handler.
 */
@Timeout(20)
final class ActorPendingContinuationIntegrationTest {
    @Test
    void suspendedActorReceiveRetainsLogicalTurnWithoutParkingCarrier() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            OresFuture<Integer> pending = new OresFuture<>();
            AtomicReference<OresFuture<Integer>> task = new AtomicReference<>();
            CountDownLatch awaitEntered = new CountDownLatch(1);
            CountDownLatch laterMessage = new CountDownLatch(1);
            CountDownLatch unrelatedActor = new CountDownLatch(1);
            AtomicReference<Thread> publisher = new AtomicReference<>();
            AtomicReference<Thread> resumedOn = new AtomicReference<>();
            AtomicInteger resumes = new AtomicInteger();
            AtomicInteger userMessages = new AtomicInteger();

            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> {
                userMessages.incrementAndGet();
                if (message == 1) {
                    OresFuture<Integer> continuation = context.runtime().startActorTask(resume -> {
                        int pc = resumes.getAndIncrement();
                        assertTrue(ActorRuntime.inActorExecution());
                        assertEquals(context.self().id(),
                                ActorRuntime.currentActorId().orElseThrow());
                        if (pc == 0) {
                            assertTrue(resume.initial());
                            awaitEntered.countDown();
                            return OresScheduler.await(pending);
                        }
                        assertEquals(1, pc, "a READY claim must be single-winner");
                        assertEquals(41, resume.value());
                        assertNull(resume.failure());
                        resumedOn.set(Thread.currentThread());
                        assertNotSame(publisher.get(), Thread.currentThread(),
                                "producer must not execute actor guest continuation");
                        return OresScheduler.done(42);
                    });
                    task.set(continuation);
                    // The message handler's logical reservation remains held
                    // while its source Task yields the *physical* carrier.
                    context.runtime().suspendCurrentUserMailboxUntil(continuation);
                } else if (message == 2) {
                    assertEquals(2, userMessages.get());
                    assertEquals(2, resumes.get(),
                            "second user mail must wait for handler's continuation");
                    laterMessage.countDown();
                } else {
                    fail("unexpected actor message");
                }
            });
            var peer = runtime.<Integer>spawnShared(() -> (message, context) -> {
                assertEquals(7, message);
                unrelatedActor.countDown();
            });
            try {
                actor.send(1);
                assertTrue(awaitEntered.await(5, TimeUnit.SECONDS));
                actor.send(2);
                peer.send(7);
                assertTrue(unrelatedActor.await(5, TimeUnit.SECONDS),
                        "unrelated actor must progress while a receive awaits");
                assertEquals(1L, laterMessage.getCount(),
                        "second user mail cannot enter during suspended receive");
                assertEquals(1, userMessages.get());

                Thread completionThread = Thread.ofPlatform().start(() -> {
                    publisher.set(Thread.currentThread());
                    pending.completeFromRuntime(41);
                });
                completionThread.join();
                assertEquals(42, task.get().get(5, TimeUnit.SECONDS));
                assertTrue(laterMessage.await(5, TimeUnit.SECONDS));
                assertEquals(2, resumes.get());
                assertNotNull(resumedOn.get());
            } finally {
                actor.stop();
                peer.stop();
            }
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(peer.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }
}
