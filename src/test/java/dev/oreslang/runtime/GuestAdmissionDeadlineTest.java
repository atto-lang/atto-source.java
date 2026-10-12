package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Watchdogs must not require GraalVM or an actual guest image to test. */
final class GuestAdmissionDeadlineTest {
    @Test
    void slowAdmissionRevokesOnceAndCannotBeCommitted() throws Exception {
        CountDownLatch revoked = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        try (var deadline = new GuestAdmissionDeadline(Duration.ofMillis(20), () -> {
            count.incrementAndGet();
            revoked.countDown();
        })) {
            assertTrue(revoked.await(5, TimeUnit.SECONDS),
                    "watchdog must interrupt a noncompleting source admission");
            assertFalse(deadline.commitIfLive(),
                    "expired context cannot be published as a live actor");
            assertEquals(1, count.get());
        }
    }

    @Test
    void completedAdmissionNeverRevokesLiveActorContext() throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (var deadline = new GuestAdmissionDeadline(
                Duration.ofMillis(50), count::incrementAndGet)) {
            assertTrue(deadline.commitIfLive());
        }
        Thread.sleep(150);
        assertEquals(0, count.get(), "deadline must be disarmed at admission");
    }

    @Test
    void failedAdmissionDisarmsTimerAfterImmediateCleanup() throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (var deadline = new GuestAdmissionDeadline(
                Duration.ofMillis(50), count::incrementAndGet)) {
            // Simulates source compiler rejecting hostile input promptly.
        }
        Thread.sleep(150);
        assertEquals(0, count.get(), "explicit failure cleanup owns teardown");
    }

    @Test
    void invalidDeadlineFailsBeforeCreatingHostTimerWork() {
        assertThrows(IllegalArgumentException.class, () ->
                new GuestAdmissionDeadline(Duration.ZERO, () -> {}));
        assertThrows(NullPointerException.class, () ->
                new GuestAdmissionDeadline(Duration.ofSeconds(1), null));
    }
}
