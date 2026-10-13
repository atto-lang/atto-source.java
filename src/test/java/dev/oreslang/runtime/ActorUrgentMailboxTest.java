package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorUrgentMailboxTest {
    @Test
    void normalSaturationCannotBlockReservedUrgentAdmissionAndBurstFairness() throws Exception {
        var baseline = IsolatePolicy.developer();
        var policy = new IsolatePolicy(
                baseline.capabilities(), baseline.maxHeapBytes(), 2,
                Duration.ofMinutes(1), false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var processed = new CountDownLatch(18);
        var delivered = java.util.Collections.synchronizedList(new ArrayList<String>());
        try (var runtime = new ActorRuntime(policy,
                new ActorRuntime.DispatcherConfig(2, 2, 1, 64))) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("hold".equals(message)) {
                    entered.countDown();
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("host test failed to release held turn");
                    }
                } else {
                    delivered.add(message);
                    processed.countDown();
                }
            });
            actor.ready().get(3, TimeUnit.SECONDS);
            try {
                actor.send("hold");
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                actor.send("normal-1");
                actor.send("normal-2");
                assertThrows(IllegalStateException.class, () -> actor.send("normal-overflow"));

                for (int i = 0; i < 16; i++) actor.sendUrgent("urgent-" + i);
                assertThrows(IllegalStateException.class, () -> actor.sendUrgent("urgent-overflow"));
                release.countDown();
                assertTrue(processed.await(5, TimeUnit.SECONDS));
                assertEquals(18, delivered.size());

                assertEquals(List.of("urgent-0", "urgent-1", "urgent-2",
                        "urgent-3", "urgent-4", "urgent-5", "urgent-6", "urgent-7"),
                        delivered.subList(0, 8));
                assertEquals("normal-1", delivered.get(8));
                assertEquals(List.of("urgent-8", "urgent-9", "urgent-10",
                        "urgent-11", "urgent-12", "urgent-13", "urgent-14", "urgent-15"),
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
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send("probe");
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertTrue(rejected.get(), "actor code must not forge URGENT admission");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void stopDrainsBothUserQueuesAndReleasesAllUrgentCredits() throws Exception {
        var baseline = IsolatePolicy.developer();
        var policy = new IsolatePolicy(
                baseline.capabilities(), baseline.maxHeapBytes(), 2,
                Duration.ofMinutes(1), false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var processedAfterStop = new AtomicInteger();
        try (var runtime = new ActorRuntime(policy,
                new ActorRuntime.DispatcherConfig(2, 2, 1, 64))) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("hold".equals(message)) {
                    entered.countDown();
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("held actor turn was not released");
                    }
                    // Self STOP is out-of-band and nonblocking on the actor
                    // carrier. A host stop() intentionally blocks until
                    // finalization, so it cannot be called while the same host
                    // thread still owns this release latch.
                    context.self().stop();
                } else {
                    processedAfterStop.incrementAndGet();
                }
            });
            actor.ready().get(3, TimeUnit.SECONDS);
            try {
                actor.send("hold");
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                actor.send("normal");
                for (int i = 0; i < 16; i++) actor.sendUrgent("urgent-" + i);
            } finally {
                release.countDown();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertEquals(0, processedAfterStop.get(),
                        "STOP must revoke all queued normal and urgent user mail");
                assertTrue(actor.failure().isEmpty(), () -> actor.failure().toString());
            }
        }
    }
    @Test
    void priorityOvertakeCannotSatisfyDrainBeforeEarlierNormalMail() throws Exception {
        var baseline = IsolatePolicy.developer();
        var policy = new IsolatePolicy(
                baseline.capabilities(), baseline.maxHeapBytes(), 2,
                Duration.ofMinutes(1), false);
        var entered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var urgentHandled = new CountDownLatch(1);
        var normalEntered = new CountDownLatch(1);
        var releaseNormal = new CountDownLatch(1);

        try (var runtime = new ActorRuntime(policy,
                new ActorRuntime.DispatcherConfig(2, 2, 1, 64))) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                switch (message) {
                    case "first" -> {
                        entered.countDown();
                        if (!releaseFirst.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("first handler not released");
                    }
                    case "urgent" -> urgentHandled.countDown();
                    case "normal" -> {
                        normalEntered.countDown();
                        if (!releaseNormal.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("normal handler not released");
                    }
                    default -> throw new AssertionError("unexpected message");
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            try {
                actor.send("first");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                actor.send("normal");
                actor.sendUrgent("urgent");
                var allAdmitted = actor.drain();
                releaseFirst.countDown();
                assertTrue(urgentHandled.await(5, TimeUnit.SECONDS));
                assertTrue(normalEntered.await(5, TimeUnit.SECONDS));
                assertFalse(allAdmitted.isDone(),
                        "out-of-order urgent completion must not advance contiguous drain watermark");
                releaseNormal.countDown();
                allAdmitted.get(5, TimeUnit.SECONDS);
                assertTrue(actor.failure().isEmpty(), () -> actor.failure().toString());
            } finally {
                releaseFirst.countDown();
                releaseNormal.countDown();
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

}
