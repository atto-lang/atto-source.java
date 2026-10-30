package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Race between logical close, last physical loan, and completion publication. */
@Timeout(30)
final class ActorStateRwRetirementPublishRaceTest {
    private static ExecutionContext actor(long execution) {
        return ExecutionContext.actorBound(
                7, execution, execution + 10_000L, 1L,
                9, 3, 11, 0, 0);
    }

    @Test
    void closingLastGrantedLoanInsidePublisherStillSignalsPhysicalRetirement()
            throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger retireWakeups = new AtomicInteger();
        AtomicReference<Throwable> publisherFailure = new AtomicReference<>();

        try (ActorStateRwLoans loans =
                new ActorStateRwLoans(3, retireWakeups::incrementAndGet)) {
            var initialRead = loans.acquireReadAsync(actor(1))
                    .get(5, TimeUnit.SECONDS);
            var pendingWriter = loans.acquireWriteAsync(actor(2));
            assertFalse(pendingWriter.isDone());

            pendingWriter.whenCompleteRuntime((loan, failure) -> {
                try {
                    assertNull(failure);
                    assertNotNull(loan);
                    callbackEntered.countDown();
                    assertTrue(releaseCallback.await(10, TimeUnit.SECONDS),
                            "test producer callback must be released");
                    loan.close();
                } catch (Throwable error) {
                    publisherFailure.set(error);
                    if (loan != null) loan.close();
                }
            });

            Thread publisher = Thread.ofPlatform()
                    .name("state-loan-publisher-retirement-race")
                    .start(() -> {
                        try { initialRead.close(); }
                        catch (Throwable failure) { publisherFailure.set(failure); }
                    });
            try {
                assertTrue(callbackEntered.await(10, TimeUnit.SECONDS),
                        "writer callback was not in active publisher");
                assertEquals(1, loans.activeWriterCount());
                loans.close(); // logical revoke; writer still physically held
                assertFalse(loans.physicallyRetired());
                assertEquals(0, retireWakeups.get());
            } finally {
                releaseCallback.countDown();
                publisher.join(10_000);
            }

            assertFalse(publisher.isAlive(), "publisher remained blocked");
            assertNull(publisherFailure.get(),
                    () -> "publisher/callback failed: " + publisherFailure.get());
            assertTrue(loans.physicallyRetired(),
                    "publisher exit must finish physical state-loan retirement");
            assertEquals(1, retireWakeups.get(),
                    "exactly one actor finalization notification on last publisher exit");
            assertEquals(0, loans.activeReaderCount());
            assertEquals(0, loans.activeWriterCount());
            assertEquals(0, loans.pendingCount());
        }
    }
    @Test
    void pendingWaiterCloseCallbacksMustDrainBeforePhysicalRetirement()
            throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch allowCallbackReturn = new CountDownLatch(1);
        AtomicInteger retireWakeups = new AtomicInteger();
        AtomicReference<Throwable> closerFailure = new AtomicReference<>();

        try (ActorStateRwLoans loans =
                new ActorStateRwLoans(3, retireWakeups::incrementAndGet)) {
            var activeWriter = loans.acquireWriteAsync(actor(10))
                    .get(5, TimeUnit.SECONDS);
            var rejectedReader = loans.acquireReadAsync(actor(11));
            assertFalse(rejectedReader.isDone());
            rejectedReader.whenCompleteRuntime((loan, failure) -> {
                try {
                    assertNull(loan);
                    assertNotNull(failure);
                    callbackEntered.countDown();
                    assertTrue(allowCallbackReturn.await(10, TimeUnit.SECONDS));
                } catch (Throwable error) {
                    closerFailure.set(error);
                }
            });

            Thread closer = Thread.ofPlatform()
                    .name("state-rw-close-waiter-publication")
                    .start(() -> {
                        try { loans.close(); }
                        catch (Throwable failure) { closerFailure.set(failure); }
                    });
            try {
                assertTrue(callbackEntered.await(10, TimeUnit.SECONDS),
                        "closing waiter subscriber did not enter");
                activeWriter.close();
                // The loan is gone, but close() is physically in the middle
                // of a rejected waiter callback. This is NOT quiescence.
                assertEquals(0, loans.activeWriterCount());
                assertEquals(0, loans.pendingCount());
                assertFalse(loans.physicallyRetired(),
                        "pending Future callback must pin physical actor retirement");
                assertEquals(0, retireWakeups.get(),
                        "actor memory cannot finalize before subscriber exit");
            } finally {
                allowCallbackReturn.countDown();
                closer.join(10_000);
            }

            assertFalse(closer.isAlive(), "close callback did not unwind");
            assertNull(closerFailure.get(),
                    () -> "state close callback failed: " + closerFailure.get());
            assertTrue(loans.physicallyRetired());
            assertEquals(1, retireWakeups.get(),
                    "the physically last close notification delivers one wakeup");
        }
    }

}
