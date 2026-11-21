package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exclusive and RW arbiters must provide the same physical quiescence
 * contract. Logical close cannot erase an in-flight terminal callback.
 */
@Timeout(30)
final class ActorStateExclusiveRetirementPublishRaceTest {
    @Test
    void publisherExitWakesActorAfterExclusiveLoanClosesInsideSubscriber()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger wakeups = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ActorStateLoans loans = new ActorStateLoans(3, wakeups::incrementAndGet)) {
            var first = loans.acquireAsync(new Object()).get(5, TimeUnit.SECONDS);
            var pending = loans.acquireAsync(new Object());
            assertFalse(pending.isDone());
            pending.whenCompleteRuntime((loan, error) -> {
                try {
                    assertNull(error);
                    assertNotNull(loan);
                    entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    loan.close();
                } catch (Throwable issue) {
                    failure.set(issue);
                    if (loan != null) loan.close();
                }
            });
            Thread publisher = Thread.ofPlatform()
                    .start(() -> {
                        try { first.close(); }
                        catch (Throwable issue) { failure.set(issue); }
                    });
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(1, loans.activeCount());
                loans.close();
                assertFalse(loans.physicallyRetired());
                assertEquals(0, wakeups.get());
            } finally {
                release.countDown();
                publisher.join(10_000);
            }
            assertFalse(publisher.isAlive());
            assertNull(failure.get(), () -> "publisher failed: " + failure.get());
            assertTrue(loans.physicallyRetired());
            assertEquals(1, wakeups.get());
        }
    }

    @Test
    void rejectedPendingSubscriberPinsExclusiveRetirementUntilReturn()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger wakeups = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ActorStateLoans loans = new ActorStateLoans(3, wakeups::incrementAndGet)) {
            var active = loans.acquireAsync(new Object()).get(5, TimeUnit.SECONDS);
            var waiting = loans.acquireAsync(new Object());
            waiting.whenCompleteRuntime((loan, error) -> {
                try {
                    assertNull(loan);
                    assertNotNull(error);
                    entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (Throwable issue) {
                    failure.set(issue);
                }
            });
            Thread closer = Thread.ofPlatform()
                    .start(() -> {
                        try { loans.close(); }
                        catch (Throwable issue) { failure.set(issue); }
                    });
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                active.close();
                assertEquals(0, loans.activeCount());
                assertEquals(0, loans.pendingCount());
                assertFalse(loans.physicallyRetired());
                assertEquals(0, wakeups.get());
            } finally {
                release.countDown();
                closer.join(10_000);
            }
            assertFalse(closer.isAlive());
            assertNull(failure.get(), () -> "closer failed: " + failure.get());
            assertTrue(loans.physicallyRetired());
            assertEquals(1, wakeups.get());
        }
    }
}
