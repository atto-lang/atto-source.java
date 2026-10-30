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
}
