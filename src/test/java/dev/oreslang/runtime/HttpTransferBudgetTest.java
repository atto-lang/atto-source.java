package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Budget counts unclaimed transfer tickets, NOT active HTTP connections:
 * deadlines can release a connection while leaving mailbox tombstones.
 */
final class HttpTransferBudgetTest {
    @Test void exhaustedTicketsFailClosedUntilClaimOrTombstoneConsumption() {
        var budget = new NativeHttp.TransferBudget(3);
        assertEquals(3, budget.limit());
        assertEquals(0, budget.outstanding());
        for (int i = 0; i < 3; i++) assertTrue(budget.tryReserve());
        assertEquals(3, budget.outstanding());
        for (int i = 0; i < 50_000; i++) {
            assertFalse(budget.tryReserve(),
                    "expired HTTP responses must not recycle pending actor-mail tickets");
            assertEquals(3, budget.outstanding());
        }
        assertEquals(50_000, budget.rejected());
        budget.release(); // precisely one transfer consumed or revoked
        assertEquals(2, budget.outstanding());
        assertTrue(budget.tryReserve());
        assertEquals(3, budget.outstanding());
        for (int i = 0; i < 3; i++) budget.release();
        assertEquals(0, budget.outstanding());
        assertEquals(1_024, new NativeHttp.TransferBudget(65_536).limit());
        assertThrows(IllegalArgumentException.class, () -> new NativeHttp.TransferBudget(0));
    }

    @Test void duplicateReleaseNeverInflatesBudgetCapacity() {
        var budget = new NativeHttp.TransferBudget(2);
        assertThrows(IllegalStateException.class, budget::release,
                "a foreign or replayed release must not create permits");
        assertEquals(0, budget.outstanding());
        assertTrue(budget.tryReserve());
        assertTrue(budget.tryReserve());
        assertFalse(budget.tryReserve());
        budget.release();
        budget.release();
        for (int replay = 0; replay < 2_000; replay++) {
            assertThrows(IllegalStateException.class, budget::release);
            assertEquals(0, budget.outstanding());
        }
        assertTrue(budget.tryReserve());
        assertTrue(budget.tryReserve());
        assertFalse(budget.tryReserve(), "stale release must not inflate capacity");
        budget.release();
        budget.release();
    }

    @Test void racingDoubleReleasesCannotOverAdmit() throws Exception {
        var budget = new NativeHttp.TransferBudget(1);
        assertTrue(budget.tryReserve());
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 128; i++) {
                attempts.add(tasks.submit(() -> {
                    start.await();
                    try {
                        budget.release();
                        return true;
                    } catch (IllegalStateException invalid) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) succeeded++;
            assertEquals(1, succeeded, "one reservation authorizes one release");
        }
        assertEquals(0, budget.outstanding());
        assertTrue(budget.tryReserve());
        assertFalse(budget.tryReserve());
        budget.release();
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
                            boolean reserved = budget.tryReserve();
                            if (reserved) won.incrementAndGet();
                            settled.countDown();
                            if (reserved) {
                                if (!release.await(10, TimeUnit.SECONDS))
                                    throw new AssertionError("test release timed out");
                                budget.release();
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
                for (var submittedTask : submitted) submittedTask.get(15, TimeUnit.SECONDS);
                assertTrue(errors.isEmpty(), () -> errors.toString());
            }
            assertEquals(0, budget.outstanding(), "all reservations reclaimed after round");
        }
    }
}
