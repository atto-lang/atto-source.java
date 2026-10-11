package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A Future cancellation is NOT proof that a physical guest carrier has exited. */
@Timeout(15)
final class OresSchedulerQuiescenceTest {
    @Test
    void cancellationDoesNotRetireRunningTaskUntilCarrierUnwinds() throws Exception {
        CountDownLatch insideGuest = new CountDownLatch(1);
        CountDownLatch exitGuest = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger retires = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> result = scheduler.start(resume -> {
                insideGuest.countDown();
                assertTrue(exitGuest.await(3, TimeUnit.SECONDS));
                return OresScheduler.done(42);
            }, () -> {
                retires.incrementAndGet();
                retired.countDown();
            });
            try {
                assertTrue(insideGuest.await(3, TimeUnit.SECONDS));
                assertTrue(result.cancel(false));
                assertTrue(result.isCancelled(), "Future is already settled");
                assertEquals(0, retires.get(),
                        "guest resources cannot be retired while a carrier remains live");
            } finally {
                exitGuest.countDown();
            }
            assertTrue(retired.await(3, TimeUnit.SECONDS));
            assertEquals(1, retires.get());
        }
    }

    @Test
    void cancellationBeforeGuestAdmissionSkipsGuestAndRetiresOnlyOnce()
            throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstReleased = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger retireCount = new AtomicInteger();
        AtomicBoolean secondGuestEntered = new AtomicBoolean();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> first = scheduler.start(resume -> {
                firstEntered.countDown();
                assertTrue(firstReleased.await(3, TimeUnit.SECONDS));
                return OresScheduler.done(7);
            });
            try {
                assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
                OresFuture<Integer> second = scheduler.start(resume -> {
                    secondGuestEntered.set(true);
                    return OresScheduler.done(99);
                }, () -> {
                    retireCount.incrementAndGet();
                    retired.countDown();
                });
                assertTrue(second.cancel(false));
                assertTrue(retired.await(3, TimeUnit.SECONDS));
                assertEquals(1, retireCount.get());
            } finally {
                firstReleased.countDown();
            }
            assertEquals(7, first.get(3, TimeUnit.SECONDS));
            assertFalse(secondGuestEntered.get(),
                    "cancelled queued turn must not run guest code later");
            assertEquals(1, retireCount.get());
        }
    }

    @Test
    void successfulTurnRetiresExactlyOnceAfterCompletion() throws Exception {
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> future = scheduler.start(
                    resume -> OresScheduler.done(13),
                    () -> {
                        count.incrementAndGet();
                        retired.countDown();
                    });
            assertEquals(13, future.get(3, TimeUnit.SECONDS));
            assertTrue(retired.await(3, TimeUnit.SECONDS));
            assertEquals(1, count.get());
        }
    }
}
