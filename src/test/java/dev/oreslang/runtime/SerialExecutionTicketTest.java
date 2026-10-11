package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.oreslang.runtime.SerialActorAdmission.Kind.MESSAGE;

/** Trust-boundary tests for scheduler-issued per-source-task execution tickets. */
final class SerialExecutionTicketTest {
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
}
