package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ActorStateReadWriteLoansTest {
    @Test void concurrentReadersAndWaitingWriterHaveStrictFifoAndNoOverlap() {
        try (var loans = new ActorStateReadWriteLoans(17, 4)) {
            Object readerA = new Object(), readerB = new Object();
            Object writerId = new Object(), readerC = new Object();

            var first = loans.acquireReadAsync(readerA, 17).join();
            var second = loans.acquireReadAsync(readerB, 17).join();
            assertEquals(2, loans.activeReaderCount());
            assertFalse(loans.writerActive());
            assertTrue(first.heldBy(readerA, 17));
            assertFalse(first.heldBy(readerB, 17));

            var queuedWriter = loans.acquireWriteAsync(writerId, 17);
            var lateReader = loans.acquireReadAsync(readerC, 17);
            assertFalse(queuedWriter.isDone());
            assertFalse(lateReader.isDone(),
                    "new readers may not pass an already queued writer");

            first.close();
            assertFalse(queuedWriter.isDone(), "remaining reader still owns a read loan");
            second.close();

            var write = queuedWriter.join();
            assertTrue(loans.writerActive());
            assertEquals(0, loans.activeReaderCount());
            assertEquals(1, loans.activeCount());
            assertTrue(write.heldBy(writerId, 17));
            assertFalse(lateReader.isDone());
            write.close();

            var last = lateReader.join();
            assertTrue(last.heldBy(readerC, 17));
            assertTrue(last.epoch() > write.epoch());
            last.close();
            assertEquals(0, loans.activeCount());
            assertEquals(0, loans.pendingCount());
        }
    }

    @Test void writerExcludesReaderAndCancelledWaiterDoesNotLeakIdentity() {
        try (var loans = new ActorStateReadWriteLoans(8, 2)) {
            Object owner = new Object(), cancelledOwner = new Object();
            Object nextOwner = new Object(), fourth = new Object();
            var writer = loans.acquireWriteAsync(owner, 8).join();
            assertTrue(loans.acquireReadAsync(owner, 8).isCompletedExceptionally(),
                    "reentry by the same logical handler must fail");
            var cancelled = loans.acquireReadAsync(cancelledOwner, 8);
            var next = loans.acquireWriteAsync(nextOwner, 8);
            assertTrue(loans.acquireReadAsync(fourth, 8).isCompletedExceptionally(),
                    "bounded loan queue is mandatory");

            assertTrue(cancelled.cancel(false));
            assertEquals(1, loans.pendingCount());
            var replacement = loans.acquireReadAsync(cancelledOwner, 8);
            assertFalse(replacement.isDone(), "cancelled owner's identity was released");

            writer.close();
            var second = next.join();
            assertTrue(second.heldBy(nextOwner, 8));
            assertFalse(replacement.isDone());
            second.close();

            var read = replacement.join();
            assertTrue(read.heldBy(cancelledOwner, 8));
            read.close();
            assertEquals(0, loans.activeCount());
            assertEquals(0, loans.pendingCount());
        }
    }

    @Test void staleGenerationAndRetirementFenceEveryActiveLoan() {
        var loans = new ActorStateReadWriteLoans(33, 2);
        Object owner = new Object(), waiting = new Object();
        assertTrue(loans.acquireReadAsync(owner, 32).isCompletedExceptionally());
        var active = loans.acquireReadAsync(owner, 33).join();
        var queued = loans.acquireWriteAsync(waiting, 33);
        loans.close();
        assertTrue(queued.isCompletedExceptionally());
        assertTrue(loans.acquireWriteAsync(new Object(), 33).isCompletedExceptionally());
        assertFalse(active.heldBy(owner, 33));
        assertEquals(1, loans.activeCount(), "retirement waits for physical loan close");
        active.close();
        active.close();
        loans.close();
        assertEquals(0, loans.activeCount());
        assertEquals(0, loans.pendingCount());

        try (var nextGeneration = new ActorStateReadWriteLoans(34, 1)) {
            assertTrue(nextGeneration.acquireReadAsync(owner, 33).isCompletedExceptionally());
            var fresh = nextGeneration.acquireReadAsync(owner, 34).join();
            assertTrue(fresh.heldBy(owner, 34));
            fresh.close();
        }
    }

    @Test void readerGrantCallbacksRunOutsideMonitor() throws Exception {
        try (var loans = new ActorStateReadWriteLoans(9, 2)) {
            var write = loans.acquireWriteAsync(new Object(), 9).join();
            var waitingRead = loans.acquireReadAsync(new Object(), 9);
            CountDownLatch completed = new CountDownLatch(1);

            waitingRead.whenCompleteRuntime((granted, failure) -> {
                assertNull(failure);
                var entered = new CountDownLatch(1);
                Thread.ofPlatform().start(() -> {
                    loans.activeCount();
                    entered.countDown();
                });
                try {
                    assertTrue(entered.await(2, TimeUnit.SECONDS),
                            "no callback or Future completion under state-loan monitor");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally {
                    granted.close();
                    completed.countDown();
                }
            });
            write.close();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(0, loans.activeCount());
        }
    }

    @Test void retirementDuringPublicationNeverReleasesUnpublishedGrantToGuest() throws Exception {
        var loans = new ActorStateReadWriteLoans(5, 3);
        var writer = loans.acquireWriteAsync(new Object(), 5).join();
        var first = loans.acquireReadAsync(new Object(), 5);
        var second = loans.acquireReadAsync(new Object(), 5);
        CountDownLatch firstCallback = new CountDownLatch(1);
        CountDownLatch continuePublishing = new CountDownLatch(1);

        first.whenCompleteRuntime((loan, failure) -> {
            assertNull(failure);
            loan.close();
            firstCallback.countDown();
            try {
                assertTrue(continuePublishing.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        Thread publisher = Thread.ofPlatform().start(writer::close);
        try {
            assertTrue(firstCallback.await(5, TimeUnit.SECONDS));
            assertEquals(1, loans.activeCount(), "second read grant is reserved but unpublished");
            assertFalse(second.isDone());
            loans.close();
            continuePublishing.countDown();
            publisher.join(5_000);
            assertFalse(publisher.isAlive());
            assertTrue(second.isCompletedExceptionally(),
                    "retirement must beat publication of stale reserved grant");
            assertEquals(0, loans.activeCount());
        } finally {
            continuePublishing.countDown();
            loans.close();
            publisher.join(5_000);
        }
    }

    @Test void hostThreadsNeverHoldWriterAndReaderAtOnce() throws Exception {
        try (var loans = new ActorStateReadWriteLoans(12, 128)) {
            AtomicInteger readers = new AtomicInteger();
            AtomicInteger writers = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            var start = new CountDownLatch(1);
            try {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 80; i++) {
                    boolean write = i % 4 == 0;
                    futures.add(pool.submit(() -> {
                        try {
                            start.await();
                            Object identity = new Object();
                            if (write) {
                                try (var loan = loans.acquireWriteAsync(identity, 12).join()) {
                                    writers.incrementAndGet();
                                    if (readers.get() != 0 || writers.get() != 1)
                                        conflicts.incrementAndGet();
                                    Thread.yield();
                                    writers.decrementAndGet();
                                }
                            } else {
                                try (var loan = loans.acquireReadAsync(identity, 12).join()) {
                                    readers.incrementAndGet();
                                    if (writers.get() != 0) conflicts.incrementAndGet();
                                    Thread.yield();
                                    readers.decrementAndGet();
                                }
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }));
                }
                start.countDown();
                for (var future : futures) future.get(15, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            assertEquals(0, conflicts.get());
            assertEquals(0, loans.activeCount());
            assertEquals(0, loans.pendingCount());
        }
    }

    @Test void epochExhaustionDoesNotWrapAndCannotGrantStaleAuthority() throws Exception {
        try (var loans = new ActorStateReadWriteLoans(4, 1)) {
            var field = ActorStateReadWriteLoans.class.getDeclaredField("nextEpoch");
            field.setAccessible(true);
            field.setLong(loans, Long.MAX_VALUE - 1);

            var last = loans.acquireReadAsync(new Object(), 4).join();
            assertEquals(Long.MAX_VALUE, last.epoch());
            assertTrue(loans.acquireReadAsync(new Object(), 4).isCompletedExceptionally());
            var queued = loans.acquireWriteAsync(new Object(), 4);
            last.close();
            assertTrue(queued.isCompletedExceptionally());
            assertEquals(0, loans.activeCount());
            assertEquals(0, loans.pendingCount());
        }
    }
}
