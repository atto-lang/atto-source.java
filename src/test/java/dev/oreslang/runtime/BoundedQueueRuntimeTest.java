package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BoundedQueueRuntimeTest {

    @Test
    void immediateFullIsNonRetainingAndTicketIsOneShot() {
        BoundedQueueRuntime<Integer> queue = new BoundedQueueRuntime<>(1);

        BoundedQueueRuntime.Ticket first = queue.newTicket();
        assertEquals(
                BoundedQueueRuntime.PushStatus.ADMITTED,
                queue.tryPush(1, first));
        assertEquals(0, queue.pendingRegistrations());

        BoundedQueueRuntime.Ticket full = queue.newTicket();
        assertEquals(
                BoundedQueueRuntime.PushStatus.FULL,
                queue.tryPush(2, full));
        assertEquals(0, queue.pendingRegistrations());
        assertFalse(full.cancel());

        assertEquals(
                BoundedQueueRuntime.PushStatus.TICKET_USED,
                queue.tryPush(3, full));
        assertEquals(
                BoundedQueueRuntime.PopStatus.VALUE,
                queue.tryPop().status());
        assertEquals(0, queue.size());
    }

    @Test
    void cancellingOlderPendingWriterLetsLaterWriterAdvanceWithoutGhostDelivery() {
        BoundedQueueRuntime<Integer> queue = new BoundedQueueRuntime<>(1);
        assertEquals(
                BoundedQueueRuntime.PushStatus.ADMITTED,
                queue.tryPush(0, queue.newTicket()));

        BoundedQueueRuntime.Ticket older = queue.newTicket();
        BoundedQueueRuntime.Ticket later = queue.newTicket();
        OresFuture<BoundedQueueRuntime.PushStatus> olderPush =
                queue.pushAsync(1, older);
        OresFuture<BoundedQueueRuntime.PushStatus> laterPush =
                queue.pushAsync(2, later);

        assertEquals(2, queue.pendingRegistrations());
        assertTrue(queue.cancel(older));
        assertEquals(1, queue.pendingRegistrations());

        BoundedQueueRuntime.PopResult<Integer> seed = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, seed.status());
        assertEquals(0, seed.value());

        assertEquals(
                BoundedQueueRuntime.PushStatus.CANCELLED,
                olderPush.join());
        assertEquals(
                BoundedQueueRuntime.PushStatus.ADMITTED,
                laterPush.join());

        BoundedQueueRuntime.PopResult<Integer> delivered = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, delivered.status());
        assertEquals(2, delivered.value());
        assertEquals(
                BoundedQueueRuntime.PopStatus.EMPTY,
                queue.tryPop().status());
        assertEquals(0, queue.pendingRegistrations());
    }

    @Test
    void cancellationAfterCommitCannotRevokeAdmittedValue() {
        BoundedQueueRuntime<Integer> queue = new BoundedQueueRuntime<>(1);
        BoundedQueueRuntime.Ticket ticket = queue.newTicket();

        OresFuture<BoundedQueueRuntime.PushStatus> push =
                queue.pushAsync(7, ticket);

        assertEquals(
                BoundedQueueRuntime.PushStatus.ADMITTED,
                push.join());
        assertFalse(queue.cancel(ticket));

        BoundedQueueRuntime.PopResult<Integer> delivered = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, delivered.status());
        assertEquals(7, delivered.value());
    }

    @Test
    void closeSettlesPendingWriterAndPreservesCommittedDrain() {
        BoundedQueueRuntime<Integer> queue = new BoundedQueueRuntime<>(1);
        assertEquals(
                BoundedQueueRuntime.PushStatus.ADMITTED,
                queue.tryPush(0, queue.newTicket()));

        BoundedQueueRuntime.Ticket pendingTicket = queue.newTicket();
        OresFuture<BoundedQueueRuntime.PushStatus> pending =
                queue.pushAsync(1, pendingTicket);
        assertEquals(1, queue.pendingRegistrations());

        assertTrue(queue.closeQueue());
        assertFalse(queue.closeQueue());

        assertEquals(
                BoundedQueueRuntime.PushStatus.CLOSED,
                pending.join());
        assertEquals(0, queue.pendingRegistrations());

        BoundedQueueRuntime.PopResult<Integer> buffered = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, buffered.status());
        assertEquals(0, buffered.value());
        assertEquals(
                BoundedQueueRuntime.PopStatus.CLOSED_DRAINED,
                queue.tryPop().status());
        assertEquals(
                BoundedQueueRuntime.PopStatus.CLOSED_DRAINED,
                queue.popAsync().join().status());
    }

    @Test
    void closeInvalidatesUnclaimedGenerationTicket() {
        BoundedQueueRuntime<Integer> queue = new BoundedQueueRuntime<>(1);
        BoundedQueueRuntime.Ticket stale = queue.newTicket();
        long generation = queue.generation();

        assertTrue(queue.closeQueue());
        assertEquals(generation + 1, queue.generation());
        assertEquals(
                BoundedQueueRuntime.PushStatus.STALE_TICKET,
                queue.tryPush(1, stale));
        assertTrue(stale.isSettled());

        assertThrows(
                ChannelRuntime.ChannelClosedException.class,
                queue::newTicket);
    }

    @Test
    void wrongQueueTicketIsRejected() {
        BoundedQueueRuntime<Integer> first = new BoundedQueueRuntime<>(1);
        BoundedQueueRuntime<Integer> second = new BoundedQueueRuntime<>(1);
        BoundedQueueRuntime.Ticket ticket = first.newTicket();

        assertThrows(
                IllegalArgumentException.class,
                () -> second.tryPush(1, ticket));
        assertThrows(
                IllegalArgumentException.class,
                () -> second.cancel(ticket));
    }

    @Test
    void pendingWriterBudgetFailsClosedWithoutDisturbingOlderWaiter() {
        BoundedQueueRuntime<Integer> queue = new BoundedQueueRuntime<>(1, 1);
        assertEquals(
                BoundedQueueRuntime.PushStatus.ADMITTED,
                queue.tryPush(0, queue.newTicket()));

        BoundedQueueRuntime.Ticket admittedWaiter = queue.newTicket();
        OresFuture<BoundedQueueRuntime.PushStatus> firstPending =
                queue.pushAsync(1, admittedWaiter);
        assertEquals(1, queue.pendingRegistrations());

        BoundedQueueRuntime.Ticket rejectedWaiter = queue.newTicket();
        OresFuture<BoundedQueueRuntime.PushStatus> secondPending =
                queue.pushAsync(2, rejectedWaiter);

        assertEquals(
                BoundedQueueRuntime.PushStatus.RESOURCE_LIMIT,
                secondPending.join());
        assertEquals(1, queue.pendingRegistrations());
        assertTrue(rejectedWaiter.isSettled());

        assertTrue(queue.cancel(admittedWaiter));
        assertEquals(
                BoundedQueueRuntime.PushStatus.CANCELLED,
                firstPending.join());
        assertEquals(0, queue.pendingRegistrations());

        BoundedQueueRuntime.PopResult<Integer> seed = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, seed.status());
        assertEquals(0, seed.value());
        assertEquals(
                BoundedQueueRuntime.PopStatus.EMPTY,
                queue.tryPop().status());
    }

    @Test
    void pushFutureCannotBypassTicketCancellationAuthority() {
        BoundedQueueRuntime<Integer> queue = new BoundedQueueRuntime<>(1);
        assertEquals(
                BoundedQueueRuntime.PushStatus.ADMITTED,
                queue.tryPush(0, queue.newTicket()));

        BoundedQueueRuntime.Ticket ticket = queue.newTicket();
        OresFuture<BoundedQueueRuntime.PushStatus> push =
                queue.pushAsync(1, ticket);
        assertEquals(1, queue.pendingRegistrations());

        assertFalse(push.cancel(false));
        assertFalse(ticket.isCancelled());
        assertEquals(1, queue.pendingRegistrations());

        assertTrue(queue.cancel(ticket));
        assertTrue(ticket.isCancelled());
        assertEquals(
                BoundedQueueRuntime.PushStatus.CANCELLED,
                push.join());
        assertEquals(0, queue.pendingRegistrations());

        BoundedQueueRuntime.PopResult<Integer> seed = queue.tryPop();
        assertEquals(0, seed.value());
        assertEquals(
                BoundedQueueRuntime.PopStatus.EMPTY,
                queue.tryPop().status());
    }
}
