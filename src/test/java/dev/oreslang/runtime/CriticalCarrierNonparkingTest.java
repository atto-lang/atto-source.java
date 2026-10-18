package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scarce root/CONTROL-style carriers must run unrelated work while critical
 * admission waits. A logical gate is not a parked JVM/OS thread.
 */
final class CriticalCarrierNonparkingTest {
    @Test
    void allThreeContendersSuspendWithoutConsumingTheirCarriers() throws Exception {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket owner = gate.newTicket();
        assertTrue(owner.enterAsync().isDone());
        CountDownLatch threeSuspended = new CountDownLatch(3);
        List<CriticalGate.Ticket> contenders = new ArrayList<>();
        List<OresFuture<Void>> suspendedTasks = new ArrayList<>();
        AtomicInteger resumed = new AtomicInteger();

        try (OresScheduler scheduler = new OresScheduler(3)) {
            for (int i = 0; i < 3; i++) {
                CriticalGate.Ticket ticket = gate.newTicket();
                contenders.add(ticket);
                AtomicInteger stage = new AtomicInteger();
                suspendedTasks.add(scheduler.start(result -> {
                    if (stage.getAndIncrement() == 0) {
                        OresFuture<Void> admitted = ticket.enterAsync();
                        assertFalse(admitted.isDone(), "contended critical admission must suspend");
                        threeSuspended.countDown();
                        return OresScheduler.await(admitted);
                    }
                    if (result.failure() != null) {
                        throw new AssertionError("critical admission unexpectedly failed", result.failure());
                    }
                    assertTrue(ticket.isHeld(), "the scheduler must authenticate gate ownership");
                    resumed.incrementAndGet();
                    ticket.close();
                    return OresScheduler.done(null);
                }));
            }
            assertTrue(threeSuspended.await(5, TimeUnit.SECONDS));
            assertEquals(3, gate.queuedCount());

            // All three workers have executed a task that is now WAITING.
            // If they blocked physical threads, this work could not run.
            OresFuture<Integer> control = scheduler.start(result -> {
                assertTrue(result.initial());
                return OresScheduler.done(77);
            });
            assertEquals(77, control.get(5, TimeUnit.SECONDS));
            assertEquals(0, resumed.get(), "the original owner still holds the gate");

            owner.close();
            for (OresFuture<Void> task : suspendedTasks) {
                task.get(5, TimeUnit.SECONDS);
            }
            assertEquals(3, resumed.get());
            assertEquals(0, gate.queuedCount());
            assertFalse(gate.held());
            assertEquals(0, CriticalGate.waitingLogicalDomains());
        } finally {
            owner.close();
            for (CriticalGate.Ticket ticket : contenders) ticket.close();
        }
    }

    @Test
    void pendingFutureHostWaitsFailClosedOnAllThreeSchedulerCarriers() throws Exception {
        OresFuture<Integer> neverCompleted = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(3)) {
            List<OresFuture<Void>> checks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                checks.add(scheduler.start(result -> {
                    assertTrue(result.initial());
                    IllegalStateException join = assertThrows(
                            IllegalStateException.class, neverCompleted::join);
                    assertTrue(join.getMessage().contains("scheduler/CONTROL carrier"));
                    assertThrows(IllegalStateException.class, neverCompleted::get);
                    assertThrows(IllegalStateException.class,
                            () -> neverCompleted.get(5, TimeUnit.SECONDS));
                    // Polling is nonparking and is deliberately still allowed.
                    assertThrows(TimeoutException.class,
                            () -> neverCompleted.get(0, TimeUnit.NANOSECONDS));
                    return OresScheduler.done(null);
                }));
            }
            for (OresFuture<Void> check : checks) check.get(5, TimeUnit.SECONDS);
            assertEquals(101, scheduler.start(result -> OresScheduler.done(101))
                    .get(5, TimeUnit.SECONDS));
        }
    }
}
