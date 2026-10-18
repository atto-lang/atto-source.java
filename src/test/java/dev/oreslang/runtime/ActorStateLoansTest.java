package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ActorStateLoansTest {
    @Test void exclusiveLoanPersistsAcrossLogicalSuspensionAndPreventsDoubleWithdrawal() {
        try (var loans = new ActorStateLoans(2)) {
            Object firstHandler = new Object();
            Object secondHandler = new Object();
            AtomicInteger balance = new AtomicInteger(100);
            var first = loans.acquireAsync(firstHandler).join();
            var secondFuture = loans.acquireAsync(secondHandler);
            assertTrue(first.heldBy(firstHandler));
            assertFalse(first.heldBy(secondHandler));
            assertFalse(secondFuture.isDone());

            int firstSnapshot = balance.get();
            // A cooperative yield/await must NOT release the logical state loan.
            Thread.yield();
            assertFalse(secondFuture.isDone());
            if (firstSnapshot >= 80) balance.set(firstSnapshot - 80);
            first.close();

            var second = secondFuture.join();
            assertTrue(second.heldBy(secondHandler));
            if (balance.get() >= 30) balance.addAndGet(-30);
            assertEquals(20, balance.get(), "both withdrawals must never succeed");
            assertFalse(first.heldBy(firstHandler));
            assertTrue(second.epoch() > first.epoch());
            second.close();
            assertEquals(0, loans.activeCount());
        }
    }

    @Test void fifoCancellationAndReentrantAcquireCannotDeadlockOrLeakSlots() {
        try (var loans = new ActorStateLoans(2)) {
            Object a = new Object(), b = new Object(), c = new Object(), d = new Object();
            var held = loans.acquireAsync(a).join();
            assertTrue(loans.acquireAsync(a).isCompletedExceptionally(), "re-entry is not recursive");
            var cancelled = loans.acquireAsync(b);
            var next = loans.acquireAsync(c);
            assertTrue(loans.acquireAsync(d).isCompletedExceptionally(), "queue is bounded");
            assertTrue(cancelled.cancel(false));
            assertEquals(1, loans.pendingCount());
            held.close();
            var granted = next.join();
            assertTrue(granted.heldBy(c));
            assertTrue(loans.acquireAsync(c).isCompletedExceptionally(),
                    "same handler cannot queue while already owning a loan");
            granted.close();
            assertEquals(0, loans.pendingCount());
            assertEquals(0, loans.activeCount());
            var recovered = loans.acquireAsync(d).join();
            recovered.close();
        }
    }

    @Test void shutdownFencesActiveLoanAndFailsQueuedWaitersWithoutForcedUnlock() {
        var loans = new ActorStateLoans(1);
        Object first = new Object(), second = new Object();
        var held = loans.acquireAsync(first).join();
        var queued = loans.acquireAsync(second);
        loans.close();
        assertTrue(queued.isCompletedExceptionally());
        assertTrue(loans.acquireAsync(new Object()).isCompletedExceptionally());
        assertFalse(held.heldBy(first), "closed generation cannot authorize state access");
        assertEquals(1, loans.activeCount(), "handler still owes cleanup of its loan");
        held.close();
        held.close();
        assertEquals(0, loans.activeCount());
        loans.close();
    }

    @Test
    void finalEpochRemainsValidButFailsEveryLaterQueuedHandler() throws Exception {
        try (var loans = new ActorStateLoans(4)) {
            Object activeOwner = new Object();
            Object lastOwner = new Object();
            Object strandedOne = new Object();
            Object strandedTwo = new Object();

            var active = loans.acquireAsync(activeOwner).join();
            var last = loans.acquireAsync(lastOwner);
            var one = loans.acquireAsync(strandedOne);
            var two = loans.acquireAsync(strandedTwo);

            var nextEpoch = ActorStateLoans.class.getDeclaredField("nextEpoch");
            nextEpoch.setAccessible(true);
            nextEpoch.setLong(loans, Long.MAX_VALUE - 1);

            active.close();

            var finalLoan = last.join();
            assertEquals(Long.MAX_VALUE, finalLoan.epoch());
            assertTrue(finalLoan.heldBy(lastOwner),
                    "the final representable epoch must remain valid for its current handler");
            assertTrue(loans.epochExhausted());
            assertTrue(one.isCompletedExceptionally());
            assertTrue(two.isCompletedExceptionally());
            assertEquals(0, loans.pendingCount());

            assertTrue(loans.acquireAsync(new Object()).isCompletedExceptionally(),
                    "no handler may queue behind exhausted monotonic identity space");
            assertTrue(finalLoan.heldBy(lastOwner),
                    "admission exhaustion must not revoke the already-running final handler");
            finalLoan.close();
            assertEquals(0, loans.activeCount());
        }
    }

    @Test void completionCallbacksArePublishedOutsideTheLoanMonitor() throws Exception {
        try (var loans = new ActorStateLoans(1)) {
            var first = loans.acquireAsync(new Object()).join();
            var waiting = loans.acquireAsync(new Object());
            var completed = new CountDownLatch(1);
            waiting.whenCompleteRuntime((granted, failure) -> {
                assertNull(failure);
                var entered = new CountDownLatch(1);
                Thread.ofPlatform().start(() -> {
                    loans.activeCount();
                    entered.countDown();
                });
                try {
                    assertTrue(entered.await(2, TimeUnit.SECONDS),
                            "completion callback cannot run under state loan monitor");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                } finally {
                    granted.close();
                    completed.countDown();
                }
            });
            first.close();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(0, loans.activeCount());
        }
    }

    @Test void concurrentHostHandoffsNeverOverlapExclusiveState() throws Exception {
        try (var loans = new ActorStateLoans(128)) {
            AtomicInteger working = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
            var start = new CountDownLatch(1);
            try {
                var jobs = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 100; i++) {
                    jobs.add(executor.submit(() -> {
                        try {
                            start.await();
                            try (var lease = loans.acquireAsync(new Object()).join()) {
                                int current = working.incrementAndGet();
                                peak.accumulateAndGet(current, Math::max);
                                Thread.yield();
                                working.decrementAndGet();
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }));
                }
                start.countDown();
                for (var job : jobs) job.get(15, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
            assertEquals(1, peak.get());
            assertEquals(0, working.get());
            assertEquals(0, loans.pendingCount());
            assertEquals(0, loans.activeCount());
        }
    }
}
