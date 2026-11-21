package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CriticalPhysicalRetirementTest {
    @Test
    void cancellationCannotReleaseCriticalUntilGuestTurnPhysicallyExits() throws Exception {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket holder = gate.newTicket();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch letTurnExit = new CountDownLatch(1);
        CountDownLatch physicalExit = new CountDownLatch(1);
        AtomicBoolean stillRunning = new AtomicBoolean();
        AtomicBoolean prematureRelease = new AtomicBoolean();

        // The explicit latch simulates guest code that has entered a physical
        // turn and cannot yet unwind. Never use latch waits in actual guest code.
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Void> task = scheduler.startWithPhysicalRetirement(resume -> {
                assertTrue(holder.enterAsync().isDone());
                stillRunning.set(true);
                entered.countDown();
                try {
                    if (!letTurnExit.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test failed to release admitted turn");
                    }
                } finally {
                    stillRunning.set(false);
                }
                return OresScheduler.done(null);
            }, () -> {
                prematureRelease.set(stillRunning.get());
                holder.close();
                physicalExit.countDown();
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(holder.isHeld());
                assertTrue(task.cancel(false));
                assertTrue(task.isCancelled(), "public result may cancel immediately");
                assertTrue(holder.isHeld(), "critical ticket must stay held until physical exit");
                assertEquals(1L, physicalExit.getCount());
            } finally {
                letTurnExit.countDown();
            }
            assertTrue(physicalExit.await(5, TimeUnit.SECONDS));
            assertFalse(prematureRelease.get());
            assertFalse(holder.isHeld());
            assertFalse(gate.held());
        }
    }

    @Test
    void successfulResultIsNotPublishedUntilPhysicalCleanupFinishes() throws Exception {
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch letCleanupFinish = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.startWithPhysicalRetirement(
                    resume -> OresScheduler.done(33),
                    () -> {
                        cleanupEntered.countDown();
                        try {
                            // Instrumented test-only latch. Production cleanup
                            // must be nonparking and must not execute guest code.
                            if (!letCleanupFinish.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("cleanup barrier timeout");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    });
            try {
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
                assertFalse(task.isDone(),
                        "result must not publish while cleanup callback runs");
            } finally {
                letCleanupFinish.countDown();
            }
            assertEquals(33, task.get(5, TimeUnit.SECONDS).intValue());
        }
    }

    @Test
    void cancellationOfSuspendedOwnerReleasesExactlyOnceWithoutCarrierPark() throws Exception {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket owner = gate.newTicket();
        OresFuture<Void> never = new OresFuture<>();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch physicallyRetired = new CountDownLatch(1);
        AtomicInteger releases = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> task = scheduler.startWithPhysicalRetirement(resume -> {
                assertTrue(owner.enterAsync().isDone());
                waiting.countDown();
                return OresScheduler.await(never);
            }, () -> {
                releases.incrementAndGet();
                owner.close();
                physicallyRetired.countDown();
            });
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            assertEquals(19, scheduler.start(resume -> OresScheduler.done(19))
                    .get(5, TimeUnit.SECONDS).intValue());
            assertTrue(task.cancel(false));
            assertTrue(physicallyRetired.await(5, TimeUnit.SECONDS));
            assertEquals(1, releases.get());
            assertFalse(gate.held());
            assertEquals(0, gate.queuedCount());
        } finally {
            owner.close();
        }
    }
}
