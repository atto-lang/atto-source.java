package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class CriticalRwGateTest {
    @Test
    void concurrentReadersExcludeWritersAndPreserveWriterQueueOrder() {
        CriticalRwGate gate = new CriticalRwGate();
        var first = gate.newTicket(CriticalRwGate.Mode.READ);
        var second = gate.newTicket(CriticalRwGate.Mode.READ);
        var writer = gate.newTicket(CriticalRwGate.Mode.WRITE);
        var lateReader = gate.newTicket(CriticalRwGate.Mode.READ);

        assertTrue(first.enterAsync().isDone());
        assertTrue(second.enterAsync().isDone());
        assertFalse(writer.enterAsync().isDone());
        assertFalse(lateReader.enterAsync().isDone());
        assertEquals(2, gate.activeReaderCount());
        assertEquals(2, gate.queuedCount());

        first.close();
        assertFalse(writer.isHeld(), "writer cannot overlap the remaining reader");
        second.close();
        assertTrue(writer.isHeld(), "writer at head must win before newer reader");
        assertFalse(lateReader.isHeld());
        assertEquals(0, gate.activeReaderCount());
        writer.close();
        assertTrue(lateReader.isHeld());
        assertEquals(1, gate.activeReaderCount());
        lateReader.close();
        assertEquals(0, gate.queuedCount());
        assertFalse(gate.hasWriter());
    }

    @Test
    void consecutiveHeadReadersAreAdmittedAsACohort() {
        CriticalRwGate gate = new CriticalRwGate();
        var initial = gate.newTicket(CriticalRwGate.Mode.WRITE);
        var readA = gate.newTicket(CriticalRwGate.Mode.READ);
        var readB = gate.newTicket(CriticalRwGate.Mode.READ);
        var nextWrite = gate.newTicket(CriticalRwGate.Mode.WRITE);
        initial.enterAsync();
        readA.enterAsync();
        readB.enterAsync();
        nextWrite.enterAsync();

        initial.close();
        assertTrue(readA.isHeld());
        assertTrue(readB.isHeld());
        assertFalse(nextWrite.isHeld());
        readA.close();
        assertFalse(nextWrite.isHeld());
        readB.close();
        assertTrue(nextWrite.isHeld());
        nextWrite.close();
    }

    @Test
    void cancellingWaiterRetractsItsSlotAndCannotResurrectAfterHandoff() {
        CriticalRwGate gate = new CriticalRwGate();
        var current = gate.newTicket(CriticalRwGate.Mode.WRITE);
        var cancelled = gate.newTicket(CriticalRwGate.Mode.READ);
        var next = gate.newTicket(CriticalRwGate.Mode.WRITE);
        current.enterAsync();
        OresFuture<Void> pending = cancelled.enterAsync();
        next.enterAsync();
        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());
        assertEquals(1, gate.queuedCount());
        current.close();
        assertFalse(cancelled.isHeld());
        assertTrue(next.isHeld());
        next.close();
    }

    @Test
    void cancellingGrantHandoffNeverLeaksReadersOrWriters() throws Exception {
        for (int n = 0; n < 200; n++) {
            CriticalRwGate gate = new CriticalRwGate();
            var owner = gate.newTicket(CriticalRwGate.Mode.WRITE);
            var candidate = gate.newTicket(CriticalRwGate.Mode.READ);
            var following = gate.newTicket(CriticalRwGate.Mode.WRITE);
            owner.enterAsync();
            OresFuture<Void> pending = candidate.enterAsync();
            following.enterAsync();
            Thread canceller = new Thread(() -> pending.cancel(false));
            canceller.start();
            owner.close();
            canceller.join();
            if (pending.isCancelled()) assertFalse(candidate.isHeld());
            candidate.close();
            if (following.isHeld()) following.close();
            assertEquals(0, gate.queuedCount());
            assertEquals(0, gate.activeReaderCount());
            assertFalse(gate.hasWriter());
            gate.close();
        }
    }

    @Test
    void queuedTasksYieldCarrierAndResumeOnSchedulerAfterGrant() throws Exception {
        CriticalRwGate gate = new CriticalRwGate();
        var owner = gate.newTicket(CriticalRwGate.Mode.WRITE);
        owner.enterAsync();
        var queued = new AtomicReference<CriticalRwGate.Ticket>();
        var wasQueued = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(resume -> {
                if (resume.initial()) {
                    var reader = gate.newTicket(CriticalRwGate.Mode.READ);
                    queued.set(reader);
                    OresFuture<Void> admitted = reader.enterAsync();
                    wasQueued.countDown();
                    return OresScheduler.await(admitted);
                }
                assertNull(resume.failure());
                var reader = queued.get();
                assertTrue(reader.isHeld(), "cannot execute after revoked admission");
                reader.close();
                return OresScheduler.done(42);
            });
            assertTrue(wasQueued.await(5, TimeUnit.SECONDS));
            assertEquals(1, gate.queuedCount());
            assertTrue(scheduler.startSync(() -> true).join(),
                    "carrier must service another task while reader waits");
            owner.close();
            assertEquals(42, task.join());
        }
        assertEquals(0, gate.queuedCount());
    }

    @Test
    void sameLogicalContinuationCannotUpgradeOrNestWhileUnverified() {
        CriticalRwGate gate = new CriticalRwGate();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> completed = scheduler.startSync(() -> {
                var reader = gate.newTicket(CriticalRwGate.Mode.READ);
                reader.enterAsync();
                var write = gate.newTicket(CriticalRwGate.Mode.WRITE);
                try {
                    var error = assertThrows(IllegalStateException.class, write::enterAsync);
                    assertTrue(error.getMessage().contains("read-to-write"));
                } finally {
                    reader.close();
                }
                return null;
            });
            completed.join();
        }
        assertEquals(0, gate.activeReaderCount());
    }

    @Test
    void failureCompletionAndGateCloseRevokeAllGrantsAndWaiters() {
        CriticalRwGate gate = new CriticalRwGate();
        var active = gate.newTicket(CriticalRwGate.Mode.READ);
        var queued = gate.newTicket(CriticalRwGate.Mode.WRITE);
        active.enterAsync();
        OresFuture<Void> pending = queued.enterAsync();
        gate.close();
        assertFalse(active.isHeld());
        assertFalse(queued.isHeld());
        assertTrue(pending.isDone());
        assertEquals(0, gate.queuedCount());
        assertEquals(0, gate.activeReaderCount());
        assertFalse(gate.hasWriter());
        assertThrows(IllegalStateException.class,
                () -> gate.newTicket(CriticalRwGate.Mode.READ));
        active.close();
        queued.close();
    }

    @Test
    void taskCompletionReleasesPermitEvenWhenTaskFails() {
        CriticalRwGate gate = new CriticalRwGate();
        var writer = gate.newTicket(CriticalRwGate.Mode.WRITE);
        writer.enterAsync();
        OresFuture<Void> end = new OresFuture<>();
        writer.releaseOnCompletion(end);
        end.failFromRuntime(new IllegalStateException("task failed"));
        assertFalse(writer.isHeld());
        assertFalse(gate.hasWriter());
    }

    @Test
    void independentGatesAndDoubleCloseAreSafe() {
        CriticalRwGate first = new CriticalRwGate();
        CriticalRwGate second = new CriticalRwGate();
        var a = first.newTicket(CriticalRwGate.Mode.WRITE);
        var b = second.newTicket(CriticalRwGate.Mode.READ);
        a.enterAsync();
        b.enterAsync();
        assertTrue(a.isHeld());
        assertTrue(b.isHeld());
        a.close();
        a.close();
        b.close();
        assertFalse(first.hasWriter());
        assertEquals(0, second.activeReaderCount());
        first.close();
        first.close();
        second.close();
    }
}
