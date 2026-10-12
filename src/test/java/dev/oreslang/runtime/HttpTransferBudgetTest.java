package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Transfer-budget ownership, separate from HTTP connection admission.
 * A lease is one-use and must never release capacity owned by another transfer.
 */
final class HttpTransferBudgetTest {
    @Test void exhaustedTicketsFailClosedUntilClaimOrTombstoneConsumption() {
        var budget = new NativeHttp.TransferBudget(3);
        assertEquals(3, budget.limit());
        assertEquals(0, budget.outstanding());
        var reservations = new ArrayList<NativeHttp.TransferBudget.Reservation>();
        for (int i = 0; i < 3; i++)
            reservations.add(assertLease(budget.tryReserve()));
        assertEquals(3, budget.outstanding());
        for (int i = 0; i < 50_000; i++) {
            assertNull(budget.tryReserve(),
                    "expired responses cannot recycle queued actor-mail tickets");
            assertEquals(3, budget.outstanding());
        }
        assertEquals(50_000, budget.rejected());
        reservations.getFirst().release();
        assertEquals(2, budget.outstanding());
        var replacement = assertLease(budget.tryReserve());
        assertEquals(3, budget.outstanding());
        for (int i = 1; i < reservations.size(); i++)
            reservations.get(i).release();
        replacement.release();
        assertEquals(0, budget.outstanding());
        assertEquals(1_024, new NativeHttp.TransferBudget(65_536).limit());
        assertThrows(IllegalArgumentException.class, () -> new NativeHttp.TransferBudget(0));
    }

    @Test void staleLeaseCannotReleaseAnotherLiveTransfer() {
        var budget = new NativeHttp.TransferBudget(2);
        var old = assertLease(budget.tryReserve());
        var survivor = assertLease(budget.tryReserve());
        old.release();
        var replacement = assertLease(budget.tryReserve());
        assertEquals(2, budget.outstanding());
        for (int attempt = 0; attempt < 2_000; attempt++) {
            assertThrows(IllegalStateException.class, old::release,
                    "stale one-use release must not free a different transfer");
            assertEquals(2, budget.outstanding());
            assertNull(budget.tryReserve(), "stale release must not admit new work");
        }
        survivor.release();
        assertEquals(1, budget.outstanding());
        replacement.release();
        assertEquals(0, budget.outstanding());
    }

    @Test void racingDoubleReleasesCannotFreeOtherActiveLease() throws Exception {
        var budget = new NativeHttp.TransferBudget(2);
        var old = assertLease(budget.tryReserve());
        var survivor = assertLease(budget.tryReserve());
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 128; i++) {
                attempts.add(tasks.submit(() -> {
                    start.await();
                    try {
                        old.release();
                        return true;
                    } catch (IllegalStateException replay) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (var attempt : attempts)
                if (attempt.get(10, TimeUnit.SECONDS)) succeeded++;
            assertEquals(1, succeeded, "a lease authorizes exactly one release");
        }
        assertEquals(1, budget.outstanding());
        var replacement = assertLease(budget.tryReserve());
        assertNull(budget.tryReserve());
        survivor.release();
        assertEquals(1, budget.outstanding());
        replacement.release();
        assertEquals(0, budget.outstanding());
    }

    @Test void concurrentReservationsNeverExceedLimitAndCanBeReused() throws Exception {
        final int clients = 128;
        final int limit = 8;
        var budget = new NativeHttp.TransferBudget(limit);
        for (int round = 0; round < 6; round++) {
            var start = new CountDownLatch(1);
            var settled = new CountDownLatch(clients);
            var release = new CountDownLatch(1);
            var won = new AtomicInteger();
            var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
                var submitted = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < clients; i++) {
                    submitted.add(tasks.submit(() -> {
                        try {
                            if (!start.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("test start timed out");
                            var reservation = budget.tryReserve();
                            if (reservation != null) won.incrementAndGet();
                            settled.countDown();
                            if (reservation != null) {
                                if (!release.await(10, TimeUnit.SECONDS))
                                    throw new AssertionError("test release timed out");
                                reservation.release();
                            }
                        } catch (Throwable problem) {
                            errors.add(problem);
                            settled.countDown();
                        }
                    }));
                }
                start.countDown();
                assertTrue(settled.await(15, TimeUnit.SECONDS), "every contender tried");
                assertEquals(limit, won.get(), "exactly the budget limit wins");
                assertEquals(limit, budget.outstanding());
                release.countDown();
                for (var submittedTask : submitted)
                    submittedTask.get(15, TimeUnit.SECONDS);
                assertTrue(errors.isEmpty(), () -> errors.toString());
            }
            assertEquals(0, budget.outstanding(), "all reservations reclaimed after round");
        }
    }

    private static NativeHttp.TransferBudget.Reservation assertLease(
            NativeHttp.TransferBudget.Reservation lease) {
        return assertNotNull(lease);
    }

}
