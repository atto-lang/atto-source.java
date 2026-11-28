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
    void monotonicCommitRejectsExpiredAdmissionEvenWhenTimerIsBlocked() throws Exception {
        // An overloaded timer must not allow a source actor to publish
        // after its deadline. Revocation must run on another thread.
        var field = GuestAdmissionDeadline.class.getDeclaredField("TIMER");
        field.setAccessible(true);
        var timer = (java.util.concurrent.ScheduledThreadPoolExecutor) field.get(null);
        CountDownLatch occupyingTimer = new CountDownLatch(1);
        CountDownLatch releaseTimer = new CountDownLatch(1);
        var blocker = timer.submit(() -> {
            occupyingTimer.countDown();
            try {
                if (!releaseTimer.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("timer test barrier not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(occupyingTimer.await(5, TimeUnit.SECONDS));
            CountDownLatch revoked = new CountDownLatch(1);
            AtomicInteger count = new AtomicInteger();
            try (var deadline = new GuestAdmissionDeadline(Duration.ofMillis(15), () -> {
                count.incrementAndGet();
                revoked.countDown();
            })) {
                Thread.sleep(70);
                assertFalse(deadline.commitIfLive(),
                        "late commit must fail even before the queued timer runs");
                assertTrue(revoked.await(5, TimeUnit.SECONDS),
                        "the caller must trigger revocation independently of TIMER");
            }
            releaseTimer.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            Thread.sleep(30);
            assertEquals(1, count.get(), "the queued timeout cannot revoke twice");
        } finally {
            releaseTimer.countDown();
            blocker.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void nanosecondExpiryDoesNotRaceUninitializedAlarmReference() throws Exception {
        int trials = 100;
        CountDownLatch revoked = new CountDownLatch(trials);
        for (int i = 0; i < trials; i++) {
            try (var deadline = new GuestAdmissionDeadline(Duration.ofNanos(1),
                    revoked::countDown)) {
                assertFalse(deadline.commitIfLive());
            }
        }
        assertTrue(revoked.await(10, TimeUnit.SECONDS),
                "all expirations must start one revoker even before construction returns");
    }

    @Test
    void rejectsTimeoutBeyondBoundWithoutOverflow() {
        assertThrows(IllegalArgumentException.class, () ->
                new GuestAdmissionDeadline(Duration.ofDays(365_000), () -> {}));
        assertThrows(IllegalArgumentException.class, () ->
                new GuestAdmissionDeadline(Duration.ofMinutes(5).plusNanos(1), () -> {}));
        try (var deadline = new GuestAdmissionDeadline(Duration.ofMinutes(5), () -> {})) {
            assertTrue(deadline.commitIfLive());
        }
    }

    @Test
    void invalidDeadlineFailsBeforeCreatingHostTimerWork() {
        assertThrows(IllegalArgumentException.class, () ->
                new GuestAdmissionDeadline(Duration.ZERO, () -> {}));
        assertThrows(NullPointerException.class, () ->
                new GuestAdmissionDeadline(Duration.ofSeconds(1), null));
    }
}
