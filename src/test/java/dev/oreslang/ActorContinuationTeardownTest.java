package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresScheduler;
import dev.oreslang.runtime.OresFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorContinuationTeardownTest {
    @Test
    void cancelledAwaitRunsCleanupOnOwningActorContinuationLane() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<OresFuture.Callback<Integer>> producerCallback =
                    new AtomicReference<>();
            OresFuture<Integer> pendingProducer =
                    OresFuture.fromCallback(producerCallback::set);
            AtomicReference<OresFuture<Void>> taskFuture = new AtomicReference<>();
            AtomicInteger cleanupRuns = new AtomicInteger();
            AtomicInteger actorMessages = new AtomicInteger();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch taskPublished = new CountDownLatch(1);
            CountDownLatch cleanupCompleted = new CountDownLatch(1);
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if (message.equals("start")) {
                    actorMessages.incrementAndGet();
                    OresFuture<Void> task = context.runtime().startActorTask(
                            new OresScheduler.CleanupAwareTask<Void>() {
                                private int pc;

                                @Override
                                public OresScheduler.Step<Void> resume(
                                        OresScheduler.Resume resume) {
                                    assertTrue(ActorRuntime.inActorExecution(),
                                            "source cleanup must run under actor turn authority");
                                    if (pc++ == 0) {
                                        started.countDown();
                                        return OresScheduler.await(pendingProducer);
                                    }
                                    assertInstanceOf(
                                            java.util.concurrent.CancellationException.class,
                                            resume.failure());
                                    cleanupRuns.incrementAndGet();
                                    cleanupCompleted.countDown();
                                    return OresScheduler.done(null);
                                }
                            });
                    taskFuture.set(context.runtime().ownCurrentActorFuture(task));
                    taskPublished.countDown();
                    return;
                }
                actorMessages.incrementAndGet();
                assertEquals(1, cleanupRuns.get(),
                        "next user mail cannot precede completed source cleanup");
                context.self().stop();
            });

            actor.send("start");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(taskPublished.await(5, TimeUnit.SECONDS));
            OresFuture<Void> task = taskFuture.get();
            assertNotNull(task);
            assertTrue(task.cancel(false));
            assertTrue(cleanupCompleted.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> taskFuture.get().get(5, TimeUnit.SECONDS));
            // Late producer settlement must not revive the completed task.
            producerCallback.get().resolve(1);
            actor.send("finish");
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
            assertEquals(2, actorMessages.get());
            assertEquals(1, cleanupRuns.get());
        }
    }

    @Test
    void actorStopWaitsForOwnedAsyncLexicalCleanupBeforeDone() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<OresFuture.Callback<Integer>> producer = new AtomicReference<>();
            AtomicReference<OresFuture.Callback<Integer>> cleanup = new AtomicReference<>();
            OresFuture<Integer> waiting = OresFuture.fromCallback(producer::set);
            OresFuture<Integer> cleanupGate = OresFuture.fromCallback(cleanup::set);
            AtomicInteger cleanupRuns = new AtomicInteger();
            AtomicInteger userMessages = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch taskPublished = new CountDownLatch(1);
            CountDownLatch cleanupStarted = new CountDownLatch(1);
            CountDownLatch cleanupFinished = new CountDownLatch(1);
            AtomicReference<OresFuture<Void>> taskFuture = new AtomicReference<>();

            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                userMessages.incrementAndGet();
                OresFuture<Void> task = context.runtime().startActorTask(
                        new OresScheduler.CleanupAwareTask<Void>() {
                            private int state;

                            @Override public OresScheduler.Step<Void> resume(
                                    OresScheduler.Resume resume) {
                                assertTrue(ActorRuntime.inActorExecution(),
                                        "post-stop cleanup must use the owning actor lane");
                                if (state++ == 0) {
                                    entered.countDown();
                                    return OresScheduler.await(waiting);
                                }
                                if (state == 2) {
                                    assertInstanceOf(
                                            java.util.concurrent.CancellationException.class,
                                            resume.failure());
                                    cleanupRuns.incrementAndGet();
                                    cleanupStarted.countDown();
                                    return OresScheduler.await(cleanupGate);
                                }
                                assertNull(resume.failure(),
                                        "cleanup await must not receive cancellation twice");
                                cleanupFinished.countDown();
                                return OresScheduler.done(null);
                            }
                        });
                taskFuture.set(task);
                taskPublished.countDown();
            });

            actor.send("start");
            assertTrue(taskPublished.await(5, TimeUnit.SECONDS));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS),
                    "source must unwind despite user mailbox being sealed");
            assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                    "get_done must not retire state while lexical cleanup awaits");
            producer.get().resolve(17);
            assertEquals(1, cleanupRuns.get(),
                    "late abandoned producer completion must not replay source work");
            cleanup.get().resolve(33);
            assertTrue(cleanupFinished.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
            assertEquals(1, cleanupRuns.get());
            assertEquals(1, userMessages.get());
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> taskFuture.get().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void gracefulStopCancelsQueuedSourceTasksWithoutRunningThem() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            int tasks = 256;
            CountDownLatch entered = new CountDownLatch(1);
            AtomicInteger taskBodies = new AtomicInteger();

            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                for (int i = 0; i < tasks; i++) {
                    context.runtime().startActorTask(resume -> {
                        taskBodies.incrementAndGet();
                        return OresScheduler.done(null);
                    });
                }
                context.self().stop();
            });

            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
            assertEquals(0, taskBodies.get(),
                    "source continuations queued behind a stopped actor must not run");
        }
    }
}
