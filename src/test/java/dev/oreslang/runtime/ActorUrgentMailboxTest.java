package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorUrgentMailboxTest {
    private static IsolatePolicy mailboxPolicy(int messages) {
        var baseline = IsolatePolicy.developer();
        return new IsolatePolicy(
                baseline.capabilities(),
                baseline.maxHeapBytes(),
                messages,
                Duration.ofMinutes(1),
                false);
    }

    @Test
    void normalSaturationCannotConsumeUrgentCreditsAndTelemetryIsExact() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var processed = new CountDownLatch(18);
        var delivered = java.util.Collections.synchronizedList(new ArrayList<String>());

        try (var runtime = new ActorRuntime(
                mailboxPolicy(2),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 64))) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("hold".equals(message)) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held turn was not released");
                    }
                } else {
                    delivered.add(message);
                    processed.countDown();
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);

            try {
                actor.send("hold");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                actor.send("normal-1");
                actor.send("normal-2");
                assertThrows(ActorMailboxFullException.class, () -> actor.send("normal-overflow"));

                for (int i = 0; i < 16; i++) actor.sendUrgent("urgent-" + i);
                assertThrows(
                        ActorMailboxFullException.class,
                        () -> actor.sendUrgent("urgent-overflow"));

                var snapshot = runtime.mailboxSnapshot(actor).orElseThrow();
                assertEquals(2, snapshot.normalQueued());
                assertEquals(16, snapshot.urgentQueued());
                assertEquals(0, snapshot.controlQueued());
                assertEquals(2, snapshot.normalHighWater());
                assertEquals(16, snapshot.urgentHighWater());
                assertEquals(1, snapshot.normalRejected());
                assertEquals(1, snapshot.urgentRejected());
                assertEquals(2, snapshot.normalCapacity());
                assertEquals(16, snapshot.urgentCapacity());
                assertEquals(1_024, snapshot.controlCapacity());

                release.countDown();
                assertTrue(processed.await(5, TimeUnit.SECONDS));
                assertEquals(18, delivered.size());
                assertEquals(
                        List.of("urgent-0", "urgent-1", "urgent-2", "urgent-3",
                                "urgent-4", "urgent-5", "urgent-6", "urgent-7"),
                        delivered.subList(0, 8));
                assertEquals("normal-1", delivered.get(8));
                assertEquals(
                        List.of("urgent-8", "urgent-9", "urgent-10", "urgent-11",
                                "urgent-12", "urgent-13", "urgent-14", "urgent-15"),
                        delivered.subList(9, 17));
                assertEquals("normal-2", delivered.get(17));
                assertTrue(actor.failure().isEmpty(), () -> actor.failure().toString());
            } finally {
                release.countDown();
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void guestActorCannotSpoofSupervisorUrgentPriority() throws Exception {
        var rejected = new AtomicBoolean();
        var done = new CountDownLatch(1);
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    context.self().sendUrgent("spoofed");
                } catch (SecurityException expected) {
                    rejected.set(true);
                } finally {
                    done.countDown();
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("probe");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(rejected.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void priorityOvertakeCannotSatisfyDrainBeforeEarlierNormalMail() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var urgentHandled = new CountDownLatch(1);
        var normalEntered = new CountDownLatch(1);
        var releaseNormal = new CountDownLatch(1);

        try (var runtime = new ActorRuntime(
                mailboxPolicy(2),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 64))) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                switch (message) {
                    case "first" -> {
                        firstEntered.countDown();
                        if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("first handler not released");
                        }
                    }
                    case "urgent" -> urgentHandled.countDown();
                    case "normal" -> {
                        normalEntered.countDown();
                        if (!releaseNormal.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("normal handler not released");
                        }
                    }
                    default -> throw new AssertionError("unexpected message: " + message);
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            try {
                actor.send("first");
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                actor.send("normal");
                actor.sendUrgent("urgent");
                var barrier = actor.drain();

                releaseFirst.countDown();
                assertTrue(urgentHandled.await(5, TimeUnit.SECONDS));
                assertTrue(normalEntered.await(5, TimeUnit.SECONDS));
                assertFalse(
                        barrier.isDone(),
                        "urgent completion must not skip the earlier NORMAL sequence");

                releaseNormal.countDown();
                barrier.get(5, TimeUnit.SECONDS);
                assertTrue(actor.failure().isEmpty(), () -> actor.failure().toString());
            } finally {
                releaseFirst.countDown();
                releaseNormal.countDown();
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void urgentMailCannotOvertakeOutstandingCooperatingTask() throws Exception {
        int cooperates = 96;
        var started = new CountDownLatch(1);
        var taskDone = new CountDownLatch(1);
        var urgentDone = new CountDownLatch(1);
        var steps = new AtomicInteger();
        var overtaken = new AtomicBoolean();

        try (var runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 4, 64, 32))) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("task".equals(message)) {
                    context.runtime().startActorTask(resume -> {
                        int step = steps.getAndIncrement();
                        if (step == 0) started.countDown();
                        if (step < cooperates) return OresScheduler.cooperate();
                        taskDone.countDown();
                        return OresScheduler.done(null);
                    });
                } else if ("urgent".equals(message)) {
                    if (taskDone.getCount() != 0) overtaken.set(true);
                    urgentDone.countDown();
                    context.self().stop();
                } else {
                    throw new AssertionError("unexpected actor message");
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            try {
                actor.send("task");
                assertTrue(started.await(5, TimeUnit.SECONDS));
                actor.sendUrgent("urgent");
                assertTrue(urgentDone.await(5, TimeUnit.SECONDS));
                assertFalse(
                        overtaken.get(),
                        "URGENT user mail must stay below runtime continuation work");
                assertEquals(cooperates + 1, steps.get());
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                actor.cancel();
            }
        }
    }

    @Test
    void stopRevokesQueuedNormalAndUrgentMail() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var processedAfterStop = new AtomicInteger();

        try (var runtime = new ActorRuntime(
                mailboxPolicy(2),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 64))) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("hold".equals(message)) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held actor turn was not released");
                    }
                    context.self().stop();
                } else {
                    processedAfterStop.incrementAndGet();
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("hold");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            actor.send("normal");
            for (int i = 0; i < 16; i++) actor.sendUrgent("urgent-" + i);
            release.countDown();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, processedAfterStop.get());
            assertTrue(actor.failure().isEmpty(), () -> actor.failure().toString());
        }
    }
}
