package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class ActorAffinitySoftSpillTest {
    @Test
    void idleSoftLaneAcceptsOverflowWhilePreferredLaneIsBusy() throws Exception {
        ActorAffinityDispatcher dispatcher = new ActorAffinityDispatcher(
                2, 4, new int[0], index -> Executors.newSingleThreadExecutor());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch migrated = new CountDownLatch(1);
        AtomicInteger completedOnPreferred = new AtomicInteger();
        try {
            dispatcher.executeOnLane(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test preferred lane timed out");
                    }
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // Threshold=4/2=2 queued jobs; the first two wait behind the
            // blocked worker, and the third is sent to the idle other lane.
            dispatcher.executeOnLane(0, completedOnPreferred::incrementAndGet);
            dispatcher.executeOnLane(0, completedOnPreferred::incrementAndGet);
            dispatcher.executeOnLane(0, migrated::countDown);
            assertTrue(migrated.await(5, TimeUnit.SECONDS),
                    "soft overflow should execute while preferred CPU lane is blocked");
            assertEquals(1, dispatcher.softSpills());
            assertEquals(0, completedOnPreferred.get(),
                    "preferred queue remains serialized behind its active turn");
        } finally {
            release.countDown();
            dispatcher.shutdown();
            assertTrue(dispatcher.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(2, completedOnPreferred.get());
    }

    @Test
    void invalidLaneCannotLeakAdmissionCapacity() throws Exception {
        ActorAffinityDispatcher dispatcher = new ActorAffinityDispatcher(
                1, 1, new int[0], index -> Executors.newSingleThreadExecutor());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> dispatcher.executeOnLane(2, () -> {}));
            CountDownLatch ran = new CountDownLatch(1);
            dispatcher.executeOnLane(0, ran::countDown);
            assertTrue(ran.await(5, TimeUnit.SECONDS));
        } finally {
            dispatcher.shutdown();
            assertTrue(dispatcher.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
