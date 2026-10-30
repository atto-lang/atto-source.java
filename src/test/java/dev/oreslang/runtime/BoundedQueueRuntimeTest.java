package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BoundedQueueRuntimeTest {

    private static <T> void assertAdmitted(
            BoundedQueueRuntime.PushResult<T> result) {
        assertEquals(BoundedQueueRuntime.PushStatus.ADMITTED, result.status());
        assertNull(result.rejectedValue());
    }

    private static <T> void assertRejected(
            BoundedQueueRuntime.PushStatus status,
            T exactValue,
            BoundedQueueRuntime.PushResult<T> result) {
        assertEquals(status, result.status());
        assertSame(
                exactValue,
                result.rejectedValue(),
                "failed push must return exact payload ownership");
    }

    @Test
    void immediateFullIsNonRetainingAndReturnsExactPayload() {
        BoundedQueueRuntime<Object> queue = new BoundedQueueRuntime<>(1);

        Object firstValue = new Object();
        BoundedQueueRuntime.Ticket first = queue.newTicket();
        assertAdmitted(queue.tryPush(firstValue, first));
        assertEquals(0, queue.pendingRegistrations());

        Object fullValue = new Object();
        BoundedQueueRuntime.Ticket full = queue.newTicket();
        assertRejected(
                BoundedQueueRuntime.PushStatus.FULL,
                fullValue,
                queue.tryPush(fullValue, full));
        assertEquals(0, queue.pendingRegistrations());
        assertFalse(full.cancel());

        Object reuseValue = new Object();
        assertRejected(
                BoundedQueueRuntime.PushStatus.TICKET_USED,
                reuseValue,
                queue.tryPush(reuseValue, full));

        BoundedQueueRuntime.PopResult<Object> popped = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, popped.status());
        assertSame(firstValue, popped.value());
        assertEquals(0, queue.size());
    }

    @Test
    void livePendingWritersPreserveRegistrationOrder() {
        BoundedQueueRuntime<Integer> queue = new BoundedQueueRuntime<>(1);
        assertAdmitted(queue.tryPush(0, queue.newTicket()));

        BoundedQueueRuntime.Ticket firstTicket = queue.newTicket();
        BoundedQueueRuntime.Ticket secondTicket = queue.newTicket();
        OresFuture<BoundedQueueRuntime.PushResult<Integer>> first =
                queue.pushAsync(1, firstTicket);
        OresFuture<BoundedQueueRuntime.PushResult<Integer>> second =
                queue.pushAsync(2, secondTicket);

        assertEquals(2, queue.pendingRegistrations());

        BoundedQueueRuntime.PopResult<Integer> seed = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, seed.status());
        assertEquals(0, seed.value());

        assertAdmitted(first.join());
        assertFalse(second.isDone());
        assertEquals(1, queue.pendingRegistrations());

        BoundedQueueRuntime.PopResult<Integer> firstValue = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, firstValue.status());
        assertEquals(1, firstValue.value());

        assertAdmitted(second.join());
        BoundedQueueRuntime.PopResult<Integer> secondValue = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, secondValue.status());
        assertEquals(2, secondValue.value());
        assertEquals(0, queue.pendingRegistrations());
    }

    @Test
    void cancellationBeforeUseIsStickyAndReturnsPayload() {
        BoundedQueueRuntime<Object> queue = new BoundedQueueRuntime<>(1);
        BoundedQueueRuntime.Ticket ticket = queue.newTicket();
        Object value = new Object();

        assertTrue(ticket.cancel());
        assertTrue(ticket.isCancelled());
        assertRejected(
                BoundedQueueRuntime.PushStatus.CANCELLED,
                value,
                queue.tryPush(value, ticket));
        assertEquals(0, queue.pendingRegistrations());
        assertEquals(
                BoundedQueueRuntime.PopStatus.EMPTY,
                queue.tryPop().status());
        assertFalse(ticket.cancel());
    }

    @Test
    void ticketSequenceAndGenerationAreMonotonicWithinQueue() {
        BoundedQueueRuntime<Integer> queue = new BoundedQueueRuntime<>(2);
        BoundedQueueRuntime.Ticket first = queue.newTicket();
        BoundedQueueRuntime.Ticket second = queue.newTicket();

        assertEquals(queue.generation(), first.generation());
        assertEquals(first.generation(), second.generation());
        assertEquals(first.sequence() + 1, second.sequence());

        long generation = queue.generation();
        assertTrue(queue.closeQueue());
        assertEquals(generation + 1, queue.generation());
    }

    @Test
    void cancellingOlderPendingWriterLetsLaterWriterAdvanceWithoutGhostDelivery() {
        BoundedQueueRuntime<Object> queue = new BoundedQueueRuntime<>(1);
        Object seedValue = new Object();
        assertAdmitted(queue.tryPush(seedValue, queue.newTicket()));

        Object olderValue = new Object();
        Object laterValue = new Object();
        BoundedQueueRuntime.Ticket older = queue.newTicket();
        BoundedQueueRuntime.Ticket later = queue.newTicket();
        OresFuture<BoundedQueueRuntime.PushResult<Object>> olderPush =
                queue.pushAsync(olderValue, older);
        OresFuture<BoundedQueueRuntime.PushResult<Object>> laterPush =
                queue.pushAsync(laterValue, later);

        assertEquals(2, queue.pendingRegistrations());
        assertTrue(queue.cancel(older));
        assertEquals(1, queue.pendingRegistrations());

        BoundedQueueRuntime.PopResult<Object> seed = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, seed.status());
        assertSame(seedValue, seed.value());

        assertRejected(
                BoundedQueueRuntime.PushStatus.CANCELLED,
                olderValue,
                olderPush.join());
        assertAdmitted(laterPush.join());

        BoundedQueueRuntime.PopResult<Object> delivered = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, delivered.status());
        assertSame(laterValue, delivered.value());
        assertEquals(
                BoundedQueueRuntime.PopStatus.EMPTY,
                queue.tryPop().status());
        assertEquals(0, queue.pendingRegistrations());
    }

    @Test
    void cancellationAfterCommitCannotRevokeAdmittedValue() {
        BoundedQueueRuntime<Integer> queue = new BoundedQueueRuntime<>(1);
        BoundedQueueRuntime.Ticket ticket = queue.newTicket();

        OresFuture<BoundedQueueRuntime.PushResult<Integer>> push =
                queue.pushAsync(7, ticket);

        assertAdmitted(push.join());
        assertFalse(queue.cancel(ticket));

        BoundedQueueRuntime.PopResult<Integer> delivered = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, delivered.status());
        assertEquals(7, delivered.value());
    }

    @Test
    void closeSettlesPendingWriterReturnsPayloadAndPreservesCommittedDrain() {
        BoundedQueueRuntime<Object> queue = new BoundedQueueRuntime<>(1);
        Object committed = new Object();
        Object pendingValue = new Object();
        assertAdmitted(queue.tryPush(committed, queue.newTicket()));

        BoundedQueueRuntime.Ticket pendingTicket = queue.newTicket();
        OresFuture<BoundedQueueRuntime.PushResult<Object>> pending =
                queue.pushAsync(pendingValue, pendingTicket);
        assertEquals(1, queue.pendingRegistrations());

        assertTrue(queue.closeQueue());
        assertFalse(queue.closeQueue());

        assertRejected(
                BoundedQueueRuntime.PushStatus.CLOSED,
                pendingValue,
                pending.join());
        assertEquals(0, queue.pendingRegistrations());

        BoundedQueueRuntime.PopResult<Object> buffered = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, buffered.status());
        assertSame(committed, buffered.value());
        assertEquals(
                BoundedQueueRuntime.PopStatus.CLOSED_DRAINED,
                queue.tryPop().status());
        assertEquals(
                BoundedQueueRuntime.PopStatus.CLOSED_DRAINED,
                queue.popAsync().join().status());
    }

    @Test
    void closeInvalidatesUnclaimedGenerationTicketAndReturnsPayload() {
        BoundedQueueRuntime<Object> queue = new BoundedQueueRuntime<>(1);
        BoundedQueueRuntime.Ticket stale = queue.newTicket();
        Object value = new Object();
        long generation = queue.generation();

        assertTrue(queue.closeQueue());
        assertEquals(generation + 1, queue.generation());
        assertRejected(
                BoundedQueueRuntime.PushStatus.STALE_TICKET,
                value,
                queue.tryPush(value, stale));
        assertTrue(stale.isSettled());

        assertThrows(
                ChannelRuntime.ChannelClosedException.class,
                queue::newTicket);
    }

    @Test
    void wrongQueuePushReturnsExactPayloadWhileDirectCancelStillRejectsForeignTicket() {
        BoundedQueueRuntime<Object> first = new BoundedQueueRuntime<>(1);
        BoundedQueueRuntime<Object> second = new BoundedQueueRuntime<>(1);
        BoundedQueueRuntime.Ticket ticket = first.newTicket();
        Object value = new Object();

        assertRejected(
                BoundedQueueRuntime.PushStatus.WRONG_QUEUE,
                value,
                second.tryPush(value, ticket));
        assertEquals(
                BoundedQueueRuntime.PopStatus.EMPTY,
                second.tryPop().status());

        // The foreign attempt must not consume or settle the ticket. Its owner
        // may still use the exact same ticket for one legitimate push.
        assertAdmitted(first.tryPush(value, ticket));
        BoundedQueueRuntime.PopResult<Object> recovered = first.tryPop();
        assertSame(value, recovered.value());

        BoundedQueueRuntime.Ticket foreignCancel = first.newTicket();
        assertThrows(
                IllegalArgumentException.class,
                () -> second.cancel(foreignCancel));
    }

    @Test
    void pendingWriterBudgetFailsClosedAndReturnsRejectedPayload() {
        BoundedQueueRuntime<Object> queue = new BoundedQueueRuntime<>(1, 1);
        Object seedValue = new Object();
        Object firstValue = new Object();
        Object rejectedValue = new Object();
        assertAdmitted(queue.tryPush(seedValue, queue.newTicket()));

        BoundedQueueRuntime.Ticket admittedWaiter = queue.newTicket();
        OresFuture<BoundedQueueRuntime.PushResult<Object>> firstPending =
                queue.pushAsync(firstValue, admittedWaiter);
        assertEquals(1, queue.pendingRegistrations());

        BoundedQueueRuntime.Ticket rejectedWaiter = queue.newTicket();
        OresFuture<BoundedQueueRuntime.PushResult<Object>> secondPending =
                queue.pushAsync(rejectedValue, rejectedWaiter);

        assertRejected(
                BoundedQueueRuntime.PushStatus.RESOURCE_LIMIT,
                rejectedValue,
                secondPending.join());
        assertEquals(1, queue.pendingRegistrations());
        assertTrue(rejectedWaiter.isSettled());

        assertTrue(queue.cancel(admittedWaiter));
        assertRejected(
                BoundedQueueRuntime.PushStatus.CANCELLED,
                firstValue,
                firstPending.join());
        assertEquals(0, queue.pendingRegistrations());

        BoundedQueueRuntime.PopResult<Object> seed = queue.tryPop();
        assertEquals(BoundedQueueRuntime.PopStatus.VALUE, seed.status());
        assertSame(seedValue, seed.value());
        assertEquals(
                BoundedQueueRuntime.PopStatus.EMPTY,
                queue.tryPop().status());
    }

    @Test
    void pushFutureCannotBypassTicketCancellationAuthority() {
        BoundedQueueRuntime<Object> queue = new BoundedQueueRuntime<>(1);
        Object seedValue = new Object();
        Object pendingValue = new Object();
        assertAdmitted(queue.tryPush(seedValue, queue.newTicket()));

        BoundedQueueRuntime.Ticket ticket = queue.newTicket();
        OresFuture<BoundedQueueRuntime.PushResult<Object>> push =
                queue.pushAsync(pendingValue, ticket);
        assertEquals(1, queue.pendingRegistrations());

        assertFalse(push.cancel(false));
        assertFalse(ticket.isCancelled());
        assertEquals(1, queue.pendingRegistrations());

        assertTrue(queue.cancel(ticket));
        assertTrue(ticket.isCancelled());
        assertRejected(
                BoundedQueueRuntime.PushStatus.CANCELLED,
                pendingValue,
                push.join());
        assertEquals(0, queue.pendingRegistrations());

        BoundedQueueRuntime.PopResult<Object> seed = queue.tryPop();
        assertSame(seedValue, seed.value());
        assertEquals(
                BoundedQueueRuntime.PopStatus.EMPTY,
                queue.tryPop().status());
    }
}
