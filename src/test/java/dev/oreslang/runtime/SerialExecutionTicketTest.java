package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.oreslang.runtime.SerialActorAdmission.Kind.MESSAGE;

/** Trust-boundary tests for scheduler-issued per-source-task execution tickets. */
final class SerialExecutionTicketTest {
    @Test void stopCleanupTicketRequiresFreshSchedulerPermitAndOriginalIncarnation() {
        Object epoch = new Object();
        AtomicBoolean actorAlive = new AtomicBoolean(true);
        AtomicBoolean cleanupPermit = new AtomicBoolean(false);
        SerialActorAdmission gate = new SerialActorAdmission(2);
        var lease = gate.submit(MESSAGE).join();
        var ticket = new SerialExecutionTicket(
                "actor", epoch, 7L, gate, lease, actorAlive::get);
        java.util.concurrent.atomic.AtomicInteger reservations =
                new java.util.concurrent.atomic.AtomicInteger();
        lease.suspend();
        actorAlive.set(false); // STOP revoked ordinary actor callbacks.
        assertFalse(ticket.mayEnqueue());
        assertEquals(SerialExecutionTicket.ControlReservation.REVOKED,
                ticket.reserveCleanupControlSlotFor(
                        "actor", epoch, cleanupPermit::get,
                        () -> { reservations.incrementAndGet(); return true; }));
        assertEquals(SerialExecutionTicket.ControlReservation.REVOKED,
                ticket.reserveCleanupControlSlotFor(
                        "actor", new Object(), () -> true,
                        () -> { reservations.incrementAndGet(); return true; }));
        assertEquals(0, reservations.get());
        cleanupPermit.set(true);
        assertEquals(SerialExecutionTicket.ControlReservation.RESERVED,
                ticket.reserveCleanupControlSlotFor(
                        "actor", epoch, cleanupPermit::get,
                        () -> { reservations.incrementAndGet(); return true; }));
        assertEquals(1, reservations.get());
        lease.resume();
        assertThrows(SecurityException.class, () ->
                ticket.requireCleanupRunnable("actor", epoch, lease, false));
        assertThrows(SecurityException.class, () ->
                ticket.requireCleanupRunnable("actor", new Object(), lease, true));
        ticket.requireCleanupRunnable("actor", epoch, lease, true);
        ticket.retire();
        assertEquals(SerialExecutionTicket.ControlReservation.REVOKED,
                ticket.reserveCleanupControlSlotFor(
                        "actor", epoch, () -> true,
                        () -> { reservations.incrementAndGet(); return true; }));
        assertThrows(SerialExecutionTicket.RetiredTaskException.class,
                () -> ticket.requireCleanupRunnable("actor", epoch, lease, true));
        lease.close();
        gate.close();
    }

    @Test void localTicketRevokedByStopIsBenignButForeignIncarnationRemainsFatal() {
        Object incarnation = new Object();
        AtomicBoolean alive = new AtomicBoolean(true);
        SerialActorAdmission gate = new SerialActorAdmission(2);
        var originalLease = gate.submit(MESSAGE).join();
        var ticket = new SerialExecutionTicket(
                "actor", incarnation, 29L, gate, originalLease, alive::get);
        ticket.requireAdmissible("actor", incarnation, originalLease);
        // Reproduce STOP after an already-queued continuation has passed its
        // carrier's mailbox gate, but before requireAdmissible executes.
        alive.set(false);
        assertThrows(SerialExecutionTicket.RetiredTaskException.class,
                () -> ticket.requireAdmissible("actor", incarnation, originalLease),
                "an authenticated local STOP race must be benign cancellation");
        assertThrows(IllegalStateException.class,
                () -> ticket.requireAdmissible("actor", new Object(), originalLease),
                "a genuinely foreign incarnation must remain a fatal violation");
        assertThrows(IllegalStateException.class,
                () -> ticket.requireAdmissible("foreign-actor", incarnation, originalLease));
        originalLease.close();
        gate.close();
    }

    @Test void stopCleanupCannotBorrowNewHandlerAfterOldLeaseFinishes() {
        Object epoch = new Object();
        SerialActorAdmission gate = new SerialActorAdmission(2);
        var first = gate.submit(MESSAGE).join();
        var ticket = new SerialExecutionTicket(
                "actor", epoch, 11L, gate, first, () -> true);
        first.close();
        var next = gate.submit(MESSAGE).join();
        assertEquals(SerialExecutionTicket.ControlReservation.REVOKED,
                ticket.reserveCleanupControlSlotFor(
                        "actor", epoch, () -> true,
                        () -> { fail("stale cleanup reserved CONTROL slot"); return true; }));
        assertThrows(SerialExecutionTicket.RetiredTaskException.class,
                () -> ticket.requireCleanupRunnable("actor", epoch, next, true));
        next.close();
        gate.close();
    }

    @Test void ticketRetainsExactHandlerThroughSuspendButNotAnotherReceive() {
        Object incarnation = new Object();
        SerialActorAdmission gate = new SerialActorAdmission(3);
        var first = gate.submit(MESSAGE).join();
        SerialExecutionTicket ticket = new SerialExecutionTicket(
                "actor", incarnation, 1L, gate, first, () -> true);

        assertEquals(1L, ticket.taskId());
        assertTrue(ticket.mayEnqueue());
        ticket.requireAdmissible("actor", incarnation, first);
        ticket.requireRunnable("actor", incarnation, first);

        first.suspend();
        assertTrue(ticket.mayEnqueue(), "logical reservation survives cooperate/await");
        ticket.requireAdmissible("actor", incarnation, first);
        assertThrows(IllegalStateException.class,
                () -> ticket.requireRunnable("actor", incarnation, first));
        first.resume();
        ticket.requireRunnable("actor", incarnation, first);
        assertThrows(IllegalStateException.class,
                () -> ticket.requireAdmissible("another-actor", incarnation, first));
        assertThrows(IllegalStateException.class,
                () -> ticket.requireAdmissible("actor", new Object(), first));

        first.close();
        var second = gate.submit(MESSAGE).join();
        assertFalse(ticket.mayEnqueue(), "retired receive cannot resume under next receive");
        assertThrows(IllegalStateException.class,
                () -> ticket.requireAdmissible("actor", incarnation, second));
        assertThrows(IllegalStateException.class,
                () -> ticket.requireRunnable("actor", incarnation, second));
        second.close();
        gate.close();
    }

    @Test void taskIdAndIncarnationCannotBeBorrowedAcrossActorsOrRestarts() {
        SerialActorAdmission firstGate = new SerialActorAdmission(2);
        SerialActorAdmission otherGate = new SerialActorAdmission(2);
        var firstLease = firstGate.submit(MESSAGE).join();
        var secondLease = otherGate.submit(MESSAGE).join();
        Object firstIncarnation = new Object();
        Object secondIncarnation = new Object();
        var first = new SerialExecutionTicket(
                "same-actor-id", firstIncarnation, 1L, firstGate, firstLease, () -> true);
        var second = new SerialExecutionTicket(
                "same-actor-id", secondIncarnation, 1L, otherGate, secondLease, () -> true);
        assertEquals(firstLease.generation(), secondLease.generation());
        assertEquals(first.taskId(), second.taskId());
        assertThrows(IllegalStateException.class, () ->
                first.requireAdmissible("same-actor-id", secondIncarnation, secondLease));
        assertThrows(IllegalStateException.class, () ->
                second.requireAdmissible("same-actor-id", firstIncarnation, firstLease));
        assertThrows(IllegalArgumentException.class,
                () -> new SerialExecutionTicket(
                        "actor", firstIncarnation, 2L, otherGate, firstLease, () -> true));
        assertThrows(IllegalArgumentException.class,
                () -> new SerialExecutionTicket(
                        "actor", firstIncarnation, 0L, firstGate, firstLease, () -> true));
        firstGate.close();
        otherGate.close();
        assertFalse(first.mayEnqueue());
        assertFalse(second.mayEnqueue());
    }

    @Test void retiredTaskCannotBorrowItsStillLiveReceiveAdmission() {
        Object incarnation = new Object();
        SerialActorAdmission gate = new SerialActorAdmission(3);
        var handler = gate.submit(MESSAGE).join();
        var canceledTask = new SerialExecutionTicket(
                "actor", incarnation, 10L, gate, handler, () -> true);
        var unrelatedLiveTask = new SerialExecutionTicket(
                "actor", incarnation, 11L, gate, handler, () -> true);

        canceledTask.requireRunnable("actor", incarnation, handler);
        canceledTask.retire();
        canceledTask.retire(); // terminal notification/cancellation may race
        assertFalse(canceledTask.mayEnqueue());
        assertThrows(SerialExecutionTicket.RetiredTaskException.class,
                () -> canceledTask.requireAdmissible("actor", incarnation, handler));
        assertThrows(IllegalStateException.class,
                () -> canceledTask.requireRunnable("actor", incarnation, handler));

        // Retiring one child must not revoke the whole handler or siblings.
        unrelatedLiveTask.requireRunnable("actor", incarnation, handler);
        handler.suspend();
        assertTrue(unrelatedLiveTask.mayEnqueue());
        assertFalse(canceledTask.mayEnqueue());
        handler.resume();
        unrelatedLiveTask.requireRunnable("actor", incarnation, handler);
        handler.close();
        gate.close();
    }

    @Test void cancellationAndUnboundActorTicketFailClosed() {
        Object incarnation = new Object();
        AtomicBoolean alive = new AtomicBoolean(true);
        SerialActorAdmission gate = new SerialActorAdmission(2);
        var unbound = new SerialExecutionTicket(
                "actor", incarnation, 7L, gate, null, alive::get);
        assertTrue(unbound.mayEnqueue());
        unbound.requireRunnable("actor", incarnation, null);
        var handler = gate.submit(MESSAGE).join();
        assertFalse(unbound.mayEnqueue());
        assertThrows(IllegalStateException.class,
                () -> unbound.requireRunnable("actor", incarnation, handler));
        handler.close();
        unbound.requireRunnable("actor", incarnation, null);
        alive.set(false);
        assertFalse(unbound.mayEnqueue());
        assertThrows(IllegalStateException.class,
                () -> unbound.requireRunnable("actor", incarnation, null));
        gate.close();
        assertFalse(unbound.mayEnqueue());
    }

    @Test void lateQueuedCompletionAfterReceiveCloseIsBenignAcrossNextTurn() {
        Object incarnation = new Object();
        SerialActorAdmission gate = new SerialActorAdmission(3);
        var first = gate.submit(MESSAGE).join();
        var old = new SerialExecutionTicket(
                "actor", incarnation, 1L, gate, first, () -> true);
        first.suspend();
        // The producer already queued a callback under the old authority.
        assertTrue(old.mayEnqueue());
        // But completion of the parent receive may be observed before the
        // source task's terminal notification calls ticket.retire().
        first.close();
        var next = gate.submit(MESSAGE).join();
        assertFalse(old.isRetired(), "exercise the lease-finished/ticket-live gap");
        assertFalse(old.mayEnqueue());
        assertThrows(SerialExecutionTicket.RetiredTaskException.class,
                () -> old.requireAdmissible("actor", incarnation, next),
                "queued old-lease callback must be silently discarded by the carrier");
        assertThrows(SerialExecutionTicket.RetiredTaskException.class,
                () -> old.requireRunnable("actor", incarnation, next));
        var current = new SerialExecutionTicket(
                "actor", incarnation, 2L, gate, next, () -> true);
        current.requireRunnable("actor", incarnation, next);
        next.close();
        gate.close();
    }

    @Test void liveForeignHandlerRemainsFatalWhileStaleFinishedHandlerIsBenign() {
        Object incarnation = new Object();
        SerialActorAdmission firstGate = new SerialActorAdmission(2);
        SerialActorAdmission otherGate = new SerialActorAdmission(2);
        var first = firstGate.submit(MESSAGE).join();
        var second = otherGate.submit(MESSAGE).join();
        var ticket = new SerialExecutionTicket(
                "actor", incarnation, 11L, firstGate, first, () -> true);
        assertThrows(IllegalStateException.class,
                () -> ticket.requireAdmissible("actor", incarnation, second));
        try {
            ticket.requireAdmissible("actor", incarnation, second);
            fail("live foreign receive was accepted");
        } catch (SerialExecutionTicket.RetiredTaskException benign) {
            fail("foreign live receive may not masquerade as benign cancellation");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("different serial receive"));
        }
        first.close();
        assertThrows(SerialExecutionTicket.RetiredTaskException.class,
                () -> ticket.requireAdmissible("actor", incarnation, null));
        assertThrows(IllegalStateException.class,
                () -> ticket.requireAdmissible("actor", new Object(), null),
                "actor restart remains an invalid authority before retire check");
        second.close();
        firstGate.close();
        otherGate.close();
    }

    @Test void controlReservationLinearizesWithRetirementAndRejectsForeignOwners()
            throws Exception {
        Object incarnation = new Object();
        SerialActorAdmission gate = new SerialActorAdmission(2);
        var lease = gate.submit(MESSAGE).join();
        var ticket = new SerialExecutionTicket(
                "actor", incarnation, 19L, gate, lease, () -> true);
        java.util.concurrent.atomic.AtomicInteger charged = new java.util.concurrent.atomic.AtomicInteger();

        assertEquals(SerialExecutionTicket.ControlReservation.REVOKED,
                ticket.reserveControlSlotFor("foreign", incarnation, () -> {
                    charged.incrementAndGet();
                    return true;
                }), "foreign ticket must never invoke the accounting reservation");
        assertEquals(SerialExecutionTicket.ControlReservation.REVOKED,
                ticket.reserveControlSlotFor("actor", new Object(), () -> {
                    charged.incrementAndGet();
                    return true;
                }), "old incarnation must never invoke the accounting reservation");
        assertEquals(0, charged.get());
        assertEquals(SerialExecutionTicket.ControlReservation.FULL,
                ticket.reserveControlSlotFor("actor", incarnation, () -> false),
                "a genuinely live task may report a full CONTROL lane");

        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var reservation = workers.submit(() -> ticket.reserveControlSlotFor(
                    "actor", incarnation, () -> {
                        entered.countDown();
                        try {
                            if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new AssertionError("reservation barrier timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        charged.incrementAndGet();
                        return true;
                    }));
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                var retireStarted = new java.util.concurrent.CountDownLatch(1);
                var retirement = workers.submit(() -> {
                    retireStarted.countDown();
                    ticket.retire();
                });
                assertTrue(retireStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> retirement.get(100, java.util.concurrent.TimeUnit.MILLISECONDS),
                        "retirement cannot linearize halfway through an accepted reservation");
                release.countDown();
                assertEquals(SerialExecutionTicket.ControlReservation.RESERVED,
                        reservation.get(2, java.util.concurrent.TimeUnit.SECONDS));
                retirement.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }

        assertEquals(1, charged.get());
        assertEquals(SerialExecutionTicket.ControlReservation.REVOKED,
                ticket.reserveControlSlotFor("actor", incarnation, () -> {
                    charged.incrementAndGet();
                    return true;
                }), "a retired task cannot charge CONTROL or trigger overflow");
        assertEquals(1, charged.get());
        lease.close();
        gate.close();
    }

    @Test void sourceTicketRejectsForeignIncarnationBeforeControlQueueReservation() {
        Object original = new Object();
        Object restarted = new Object();
        SerialActorAdmission gate = new SerialActorAdmission(2);
        var first = gate.submit(MESSAGE).join();
        var originalTicket = new SerialExecutionTicket(
                "same-actor-id", original, 1L, gate, first, () -> true);

        assertTrue(originalTicket.issuedFor("same-actor-id", original));
        assertFalse(originalTicket.issuedFor("same-actor-id", restarted),
                "same value actor ID and recycled task number do not establish authority");
        assertFalse(originalTicket.issuedFor("other-actor", original),
                "foreign actor must be rejected before reserving CONTROL capacity");
        assertFalse(originalTicket.issuedFor(null, original));
        assertFalse(originalTicket.issuedFor("same-actor-id", null));

        // The pre-queue check is identity-only: actual execution admission
        // still has to prove the originating lease is live and runnable.
        first.suspend();
        assertTrue(originalTicket.issuedFor("same-actor-id", original));
        assertThrows(IllegalStateException.class,
                () -> originalTicket.requireRunnable("same-actor-id", original, first));
        first.close();
        assertTrue(originalTicket.issuedFor("same-actor-id", original),
                "historical origin is immutable, not an accidental liveness oracle");
        assertFalse(originalTicket.mayEnqueue());
        assertThrows(SerialExecutionTicket.RetiredTaskException.class,
                () -> originalTicket.requireAdmissible("same-actor-id", original, null));
        gate.close();
    }
}
