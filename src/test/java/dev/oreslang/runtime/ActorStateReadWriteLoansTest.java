package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ActorStateReadWriteLoansTest {
    @Test void actorOwnedFloatStorageRequiresInitializedFieldsAndLiveWriterLoan() {
        try (var state = new ActorStateReadWriteLoans(
                29, 4, java.util.List.of("weight", "bias"))) {
            Object init = new Object(), inference = new Object();
            assertEquals(2, state.scalarFieldCount());
            try (var writer = state.acquireWriteAsync(init, 29).join()) {
                assertThrows(IllegalStateException.class,
                        () -> state.readFloat(writer, init, 29, "weight"),
                        "uninitialized state must not silently be zero");
                state.writeFloat(writer, init, 29, "weight", 2.0);
                state.writeFloat(writer, init, 29, "bias", 1.0);
                assertEquals(2.0, state.readFloat(writer, init, 29, "weight"));
                assertThrows(IllegalStateException.class,
                        () -> state.readFloat(writer, inference, 29, "weight"),
                        "nonowner cannot borrow an existing handler's loan");
            }

            try (var reader = state.acquireReadAsync(inference, 29).join()) {
                double weight = state.readFloat(reader, inference, 29, "weight");
                double bias = state.readFloat(reader, inference, 29, "bias");
                assertEquals(7.0, weight * 3.0 + bias);
                assertThrows(IllegalStateException.class,
                        () -> state.readFloat(reader, inference, 30, "weight"),
                        "generation mismatch must fence scalar loads");
                assertThrows(IllegalArgumentException.class,
                        () -> state.readFloat(reader, inference, 29, "secret"));
            }

            try (var read = state.acquireReadAsync(inference, 29).join()) {
                var pendingWrite = state.acquireWriteAsync(new Object(), 29);
                assertFalse(pendingWrite.isDone());
                assertEquals(2.0, state.readFloat(read, inference, 29, "weight"));
                assertTrue(pendingWrite.cancel(false));
            }
        }
    }

    @Test void stateLayoutIsBoundedPrivateAndNeverPublishesArrayAliases() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorStateReadWriteLoans(1, 1, java.util.List.of("x", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorStateReadWriteLoans(1, 1, java.util.List.of("")));
        assertThrows(NullPointerException.class,
                () -> new ActorStateReadWriteLoans(1, 1, null));
        assertThrows(IllegalArgumentException.class, () ->
                new ActorStateReadWriteLoans(1, 1,
                        java.util.Collections.nCopies(129, "field")));

        try (var first = new ActorStateReadWriteLoans(1, 1, java.util.List.of("w"));
             var second = new ActorStateReadWriteLoans(1, 1, java.util.List.of("w"))) {
            Object owner = new Object();
            var foreign = first.acquireWriteAsync(owner, 1).join();
            assertThrows(IllegalStateException.class,
                    () -> second.writeFloat(foreign, owner, 1, "w", 42),
                    "identical generations do not grant cross-actor authority");
            first.writeFloat(foreign, owner, 1, "w", 4);
            foreign.close();
            assertThrows(IllegalStateException.class,
                    () -> first.readFloat(foreign, owner, 1, "w"),
                    "closed writer must not retain its borrowing authority");
            try (var borrowed = second.acquireWriteAsync(owner, 1).join()) {
                assertThrows(IllegalStateException.class,
                        () -> second.readFloat(borrowed, owner, 1, "w"));
            }
        }
    }

    @Test void cancellationOfQueuedWriterUnblocksReadersWhileEarlierReaderLives() {
        try (var state = new ActorStateReadWriteLoans(3, 3,
                java.util.List.of("weight"))) {
            Object oldOwner = new Object(), writeOwner = new Object(), newOwner = new Object();
            var originalReader = state.acquireReadAsync(oldOwner, 3).join();
            var queuedWriter = state.acquireWriteAsync(writeOwner, 3);
            var queuedReader = state.acquireReadAsync(newOwner, 3);
            assertFalse(queuedReader.isDone());
            assertTrue(queuedWriter.cancel(false));
            assertTrue(queuedReader.isDone(),
                    "cancelled head writer must not strand safe readers until old read closes");
            var nextReader = queuedReader.join();
            assertEquals(2, state.activeReaderCount());
            originalReader.close();
            nextReader.close();
            assertEquals(0, state.activeCount());
        }
    }

    @Test void retirementFencesStorageAndWaitsForPhysicalLoanReleaseBeforeClear() {
        var state = new ActorStateReadWriteLoans(41, 2, java.util.List.of("weight"));
        Object handler = new Object();
        var writer = state.acquireWriteAsync(handler, 41).join();
        state.writeFloat(writer, handler, 41, "weight", 2.0);
        assertFalse(state.tryClearRetiredScalarStorage(), "must be retired first");
        state.close();
        assertFalse(state.tryClearRetiredScalarStorage(),
                "logical cancellation is not physical loan cleanup");
        assertThrows(IllegalStateException.class,
                () -> state.readFloat(writer, handler, 41, "weight"));
        assertThrows(IllegalStateException.class,
                () -> state.writeFloat(writer, handler, 41, "weight", 99.0));
        writer.close();
        assertTrue(state.tryClearRetiredScalarStorage());
        assertTrue(state.tryClearRetiredScalarStorage(), "reclaim must be idempotent");
        assertEquals(0, state.activeCount());
        assertTrue(state.acquireReadAsync(new Object(), 41).isCompletedExceptionally());
    }

    @Test void activeReaderCapacityIsBoundedAndPendingSlotsAreIndependent() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorStateReadWriteLoans(1, 65_537, java.util.List.of("w")));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorStateReadWriteLoans(1, 1, 0, java.util.List.of("w")));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorStateReadWriteLoans(1, 1, 65_537, java.util.List.of("w")));

        try (var state = new ActorStateReadWriteLoans(
                61, 2, 2, java.util.List.of("weight"))) {
            Object a = new Object(), b = new Object(), c = new Object();
            Object writerId = new Object(), overflow = new Object();
            assertEquals(2, state.activeReaderLimit());
            var first = state.acquireReadAsync(a, 61).join();
            var second = state.acquireReadAsync(b, 61).join();
            assertEquals(2, state.activeReaderCount());

            var third = state.acquireReadAsync(c, 61);
            var writer = state.acquireWriteAsync(writerId, 61);
            assertFalse(third.isDone(), "reader budget must defer an active loan");
            assertFalse(writer.isDone());
            assertEquals(2, state.pendingCount());
            assertTrue(state.acquireReadAsync(overflow, 61).isCompletedExceptionally(),
                    "bounded pending capacity still applies after reader saturation");

            first.close();
            var promoted = third.join();
            assertEquals(2, state.activeReaderCount());
            assertFalse(writer.isDone(),
                    "waiting writer must not overlap promoted read loan");
            assertTrue(promoted.heldBy(c, 61));
            second.close();
            assertFalse(writer.isDone());
            promoted.close();
            var write = writer.join();
            assertTrue(state.writerActive());
            assertEquals(0, state.activeReaderCount());
            write.close();
            assertEquals(0, state.activeCount());
            assertEquals(0, state.pendingCount());
        }
    }

    @Test void cappedReaderCancellationReleasesQueueIdentityAndPreservesWriterOrder() {
        try (var state = new ActorStateReadWriteLoans(62, 3, 1,
                java.util.List.of("weight"))) {
            Object firstId = new Object(), cancelledId = new Object();
            Object writerId = new Object(), lastId = new Object();
            var first = state.acquireReadAsync(firstId, 62).join();
            var cancelled = state.acquireReadAsync(cancelledId, 62);
            var writer = state.acquireWriteAsync(writerId, 62);
            var finalRead = state.acquireReadAsync(lastId, 62);
            assertEquals(3, state.pendingCount());
            assertTrue(cancelled.cancel(false));
            assertEquals(2, state.pendingCount());
            var restored = state.acquireReadAsync(cancelledId, 62);
            assertFalse(restored.isDone());
            first.close();
            var exclusive = writer.join();
            assertFalse(finalRead.isDone());
            assertFalse(restored.isDone());
            exclusive.close();
            var queuedReader = finalRead.join();
            assertFalse(restored.isDone(), "active reader capacity holds through FIFO");
            queuedReader.close();
            var last = restored.join();
            assertTrue(last.heldBy(cancelledId, 62));
            last.close();
            assertEquals(0, state.pendingCount());
            assertEquals(0, state.activeCount());
        }
    }

    @Test void concurrentReadWriteStressKeepsIncrementCountAndNoStaleReads() throws Exception {
        try (var state = new ActorStateReadWriteLoans(
                87, 64, 4, java.util.List.of("weight"))) {
            Object initializer = new Object();
            try (var loan = state.acquireWriteAsync(initializer, 87).join()) {
                state.writeFloat(loan, initializer, 87, "weight", 0.0);
            }

            int workers = 8;
            int turns = 100;
            var pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger written = new java.util.concurrent.atomic.AtomicInteger();
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            try {
                for (int worker = 0; worker < workers; worker++) {
                    final int workerIndex = worker;
                    jobs.add(pool.submit(() -> {
                        Object handler = new Object();
                        try {
                            start.await();
                            for (int turn = 0; turn < turns; turn++) {
                                if ((turn + workerIndex) % 3 == 0) {
                                    try (var loan = state.acquireWriteAsync(handler, 87).join()) {
                                        double before = state.readFloat(loan, handler, 87, "weight");
                                        state.writeFloat(loan, handler, 87, "weight", before + 1.0);
                                        written.incrementAndGet();
                                    }
                                } else {
                                    try (var loan = state.acquireReadAsync(handler, 87).join()) {
                                        double snapshot = state.readFloat(loan, handler, 87, "weight");
                                        if (snapshot < 0 || snapshot > workers * turns)
                                            throw new AssertionError("invalid concurrent state snapshot");
                                        if (state.activeReaderCount() > state.activeReaderLimit())
                                            throw new AssertionError("reader cap violated");
                                    }
                                }
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }));
                }
                start.countDown();
                for (var job : jobs) job.get(20, TimeUnit.SECONDS);
            } finally {
                start.countDown();
                pool.shutdownNow();
            }
            Object verifier = new Object();
            try (var loan = state.acquireReadAsync(verifier, 87).join()) {
                assertEquals((double) written.get(), state.readFloat(loan, verifier, 87, "weight"),
                        "no lost writes or torn float reads");
            }
            assertEquals(0, state.pendingCount());
            assertEquals(0, state.activeCount());
        }
    }

    @Test void repeatedCancelRetireRacesCannotResurrectOrLeakLoanAuthority() {
        java.util.SplittableRandom random = new java.util.SplittableRandom(20261110);
        for (int round = 1; round <= 96; round++) {
            var state = new ActorStateReadWriteLoans(round, 8, 2,
                    java.util.List.of("weight", "bias"));
            Object owner = new Object();
            var active = state.acquireWriteAsync(owner, round).join();
            state.writeFloat(active, owner, round, "weight", 2.0);
            var waiting = new java.util.ArrayList<dev.oreslang.runtime.OresFuture<?>>();
            for (int index = 0; index < 8; index++) {
                Object id = new Object();
                waiting.add(index % 2 == 0
                        ? state.acquireReadAsync(id, round)
                        : state.acquireWriteAsync(id, round));
            }
            // All waiters are queued while writer is physically held.
            assertEquals(8, state.pendingCount());
            for (var request : waiting) if (random.nextBoolean()) request.cancel(false);
            state.close();
            assertTrue(waiting.stream().allMatch(java.util.concurrent.Future::isDone));
            assertFalse(active.heldBy(owner, round));
            assertThrows(IllegalStateException.class,
                    () -> state.readFloat(active, owner, round, "weight"));
            assertFalse(state.tryClearRetiredScalarStorage());
            active.close();
            assertTrue(state.tryClearRetiredScalarStorage());
            assertEquals(0, state.activeCount());
            assertEquals(0, state.pendingCount());
        }
    }

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

    @Test void adjacentQueuedReadersBatchButDoNotCrossLaterWriter() {
        try (var loans = new ActorStateReadWriteLoans(7, 4)) {
            var initial = loans.acquireWriteAsync(new Object(), 7).join();
            Object a = new Object(), b = new Object();
            Object nextWriterId = new Object(), afterWriterId = new Object();
            var firstRead = loans.acquireReadAsync(a, 7);
            var secondRead = loans.acquireReadAsync(b, 7);
            var nextWrite = loans.acquireWriteAsync(nextWriterId, 7);
            var afterWrite = loans.acquireReadAsync(afterWriterId, 7);

            initial.close();
            var first = firstRead.join();
            var second = secondRead.join();
            assertEquals(2, loans.activeReaderCount(),
                    "consecutive head-of-queue readers may coexist");
            assertFalse(nextWrite.isDone(), "queued writer waits for all readers");
            assertFalse(afterWrite.isDone(), "reader behind writer cannot overtake");

            assertTrue(loans.acquireWriteAsync(a, 7).isCompletedExceptionally(),
                    "a reader cannot upgrade without first closing its loan");
            first.close();
            assertFalse(nextWrite.isDone());
            second.close();
            var writer = nextWrite.join();
            assertTrue(loans.writerActive());
            assertFalse(afterWrite.isDone());
            writer.close();
            var last = afterWrite.join();
            assertTrue(last.heldBy(afterWriterId, 7));
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
