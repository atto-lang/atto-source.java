package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CriticalLogicalDeadlockTest {
    @Test void recursiveSchedulerTaskAcquisitionFailsBeforeQueueing() {
        CriticalGate gate = new CriticalGate();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> task = scheduler.start(resume -> {
                var outer = gate.newTicket();
                try {
                    assertTrue(outer.enterAsync().isDone());
                    var inner = gate.newTicket();
                    IllegalStateException error = assertThrows(
                            IllegalStateException.class, inner::enterAsync);
                    assertTrue(error.getMessage().contains("recursive logical"));
                    assertEquals(0, gate.queuedCount());
                } finally {
                    outer.close();
                }
                return OresScheduler.done(null);
            });
            task.join();
        }
        assertFalse(gate.held());
        assertEquals(0, CriticalGate.waitingLogicalDomains());
    }

    @Test void twoTaskTwoGateDependencyCycleIsRejectedWithoutBlockingCarriers()
            throws Exception {
        CriticalGate gateA = new CriticalGate(), gateB = new CriticalGate();
        CountDownLatch bothHolding = new CountDownLatch(2);
        OresFuture<Void> proceedA = new OresFuture<>();
        OresFuture<Void> proceedB = new OresFuture<>();
        AtomicInteger stageA = new AtomicInteger(), stageB = new AtomicInteger();
        AtomicReference<CriticalGate.Ticket> ownedA = new AtomicReference<>();
        AtomicReference<CriticalGate.Ticket> ownedB = new AtomicReference<>();
        AtomicReference<CriticalGate.Ticket> waitingA = new AtomicReference<>();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Void> taskA = scheduler.start(resume -> {
                int stage = stageA.getAndIncrement();
                if (stage == 0) {
                    var owned = gateA.newTicket();
                    ownedA.set(owned);
                    assertTrue(owned.enterAsync().isDone());
                    bothHolding.countDown();
                    return OresScheduler.await(proceedA);
                }
                if (stage == 1) {
                    var pending = gateB.newTicket();
                    waitingA.set(pending);
                    OresFuture<Void> admission = pending.enterAsync();
                    assertFalse(admission.isDone());
                    return OresScheduler.await(admission);
                }
                if (resume.failure() != null) throw new AssertionError(resume.failure());
                assertTrue(waitingA.get().isHeld());
                waitingA.get().close();
                ownedA.get().close();
                return OresScheduler.done(null);
            });
            OresFuture<Void> taskB = scheduler.start(resume -> {
                if (stageB.getAndIncrement() == 0) {
                    var owned = gateB.newTicket();
                    ownedB.set(owned);
                    assertTrue(owned.enterAsync().isDone());
                    bothHolding.countDown();
                    return OresScheduler.await(proceedB);
                }
                var rejected = gateA.newTicket();
                var ex = assertThrows(IllegalStateException.class, rejected::enterAsync);
                assertTrue(ex.getMessage().contains("deadlock cycle"));
                assertEquals(0, gateA.queuedCount());
                ownedB.get().close();
                return OresScheduler.done(null);
            });

            assertTrue(bothHolding.await(5, TimeUnit.SECONDS));
            proceedA.completeFromRuntime(null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (gateB.queuedCount() == 0 && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(1, gateB.queuedCount());
            proceedB.completeFromRuntime(null);
            taskB.join();
            taskA.join();
        } finally {
            if (waitingA.get() != null) waitingA.get().close();
            if (ownedA.get() != null) ownedA.get().close();
            if (ownedB.get() != null) ownedB.get().close();
        }
        assertFalse(gateA.held());
        assertFalse(gateB.held());
        assertEquals(0, CriticalGate.waitingLogicalDomains());
    }

    @Test void cancellationRetractsGlobalWaitDependency() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.newTicket();
        owner.enterAsync();
        var waiter = gate.newTicket();
        var pending = waiter.enterAsync();
        assertEquals(1, CriticalGate.waitingLogicalDomains());
        assertTrue(pending.cancel(false));
        assertEquals(0, CriticalGate.waitingLogicalDomains());
        owner.close();
        assertFalse(gate.held());
    }
}
