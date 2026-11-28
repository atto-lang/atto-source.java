package dev.oreslang.runtime;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct tests for the live runtime ledger; integration tests separately
 * exercise the parent dispatcher and root fallback.
 */
@Timeout(15)
final class ActorSupervisorControlLanePortTest {
    private static ActorRuntime.ActorFailureNotice notice(
            ActorRuntime.ActorId child, long generation, long sequence) {
        return new ActorRuntime.ActorFailureNotice(
                child, generation, Optional.empty(),
                ActorRuntime.ActorKind.SHARED,
                ActorRuntime.ActorFailureKind.FAILURE,
                sequence, "test.Failure");
    }

    @Test
    void missingSecondRootReservationRejectsWholeTransferWithoutPartialMove() {
        var parent = new ActorSupervisorControlLane(2);
        var root = new ActorSupervisorControlLane(2);
        var a = ActorRuntime.ActorId.create();
        var b = ActorRuntime.ActorId.create();
        parent.reserve(a, 7);
        parent.reserve(b, 8);
        root.reserve(a, 7);
        assertTrue(parent.publish(notice(a, 7, 1)));
        assertTrue(parent.publish(notice(b, 8, 2)));

        assertThrows(IllegalStateException.class,
                () -> parent.transferPublishedTo(root));
        assertEquals(2, parent.snapshot().pending(),
                "preflight must not partially migrate already-published failures");
        assertEquals(0, parent.snapshot().transferred());
        assertEquals(0, root.snapshot().pending());
        assertEquals(0, root.snapshot().published());

        root.reserve(b, 8);
        parent.transferPublishedTo(root);
        assertEquals(0, parent.snapshot().reserved());
        assertEquals(0, parent.snapshot().pending());
        assertEquals(2, parent.snapshot().transferred());
        assertEquals(2, root.snapshot().pending());

        var first = root.poll().orElseThrow();
        assertEquals(a, first.notice().actor());
        root.ack(first);
        var second = root.poll().orElseThrow();
        assertEquals(b, second.notice().actor());
        root.ack(second);
        assertEquals(0, root.snapshot().reserved());
        assertEquals(2, root.snapshot().acknowledged());
    }

    @Test
    void staleOrOccupiedRootReservationNeverConsumesParentNotice() {
        var parent = new ActorSupervisorControlLane(1);
        var root = new ActorSupervisorControlLane(1);
        var child = ActorRuntime.ActorId.create();
        parent.reserve(child, 41);
        root.reserve(child, 40);
        assertTrue(parent.publish(notice(child, 41, 1)));

        assertThrows(IllegalStateException.class,
                () -> parent.transferPublishedTo(root));
        assertEquals(1, parent.snapshot().pending());
        assertEquals(0, parent.snapshot().transferred());
        assertEquals(0, root.snapshot().published());
        root.retireGracefully(child, 40);

        root.reserve(child, 41);
        assertTrue(root.publish(notice(child, 41, 99)));
        assertThrows(IllegalStateException.class,
                () -> parent.transferPublishedTo(root));
        assertEquals(1, parent.snapshot().pending());
        assertEquals(0, parent.snapshot().transferred());
        assertEquals(1, root.snapshot().published());
    }

    @Test
    void transferredInFlightNoticeCannotBeDroppedByParentAck() {
        var parent = new ActorSupervisorControlLane(2);
        var root = new ActorSupervisorControlLane(2);
        var a = ActorRuntime.ActorId.create();
        var b = ActorRuntime.ActorId.create();
        parent.reserve(a, 1);
        parent.reserve(b, 1);
        root.reserve(a, 1);
        root.reserve(b, 1);
        assertTrue(parent.publish(notice(a, 1, 10)));
        assertTrue(parent.publish(notice(b, 1, 11)));
        var oldOwnerLease = parent.poll().orElseThrow();
        assertEquals(a, oldOwnerLease.notice().actor());

        parent.transferPublishedTo(root);
        assertEquals(0, parent.snapshot().reserved());
        assertEquals(0, parent.snapshot().inFlight());
        assertEquals(2, parent.snapshot().transferred());
        assertThrows(IllegalStateException.class, () -> parent.ack(oldOwnerLease));

        var rootFirst = root.poll().orElseThrow();
        assertEquals(a, rootFirst.notice().actor());
        root.retry(rootFirst);
        var rootSecond = root.poll().orElseThrow();
        assertEquals(b, rootSecond.notice().actor());
        root.ack(rootSecond);
        var rootRetry = root.poll().orElseThrow();
        assertEquals(a, rootRetry.notice().actor());
        root.ack(rootRetry);
        assertEquals(0, root.snapshot().reserved());
        assertEquals(2, root.snapshot().acknowledged());
    }

    @Test
    void opposingTransfersDoNotDeadlockAndRetainEveryNotice() throws Exception {
        var a = new ActorSupervisorControlLane(2);
        var b = new ActorSupervisorControlLane(2);
        var childA = ActorRuntime.ActorId.create();
        var childB = ActorRuntime.ActorId.create();
        // Pre-reserve both IDs in both lanes to make both transfers initially
        // eligible, then race the two opposing handovers.
        a.reserve(childA, 1);
        a.reserve(childB, 1);
        b.reserve(childA, 1);
        b.reserve(childB, 1);
        assertTrue(a.publish(notice(childA, 1, 1)));
        assertTrue(b.publish(notice(childB, 1, 2)));

        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Runnable aToB = () -> {
            try {
                if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                a.transferPublishedTo(b);
            } catch (IllegalStateException expectedAfterCompetingTransfer) {
                // The other transfer may have moved the destination's prior
                // notice first; fail-closed preflight is allowed.
            } catch (Throwable failure) {
                unexpected.compareAndSet(null, failure);
            }
        };
        Runnable bToA = () -> {
            try {
                if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                b.transferPublishedTo(a);
            } catch (IllegalStateException expectedAfterCompetingTransfer) {
                // See the symmetric race explanation above.
            } catch (Throwable failure) {
                unexpected.compareAndSet(null, failure);
            }
        };
        Thread one = new Thread(aToB, "control-a-to-b");
        Thread two = new Thread(bToA, "control-b-to-a");
        // A lock-order regression must fail the test, not pin the JVM forever.
        one.setDaemon(true);
        two.setDaemon(true);
        one.start();
        two.start();
        go.countDown();
        one.join(5_000);
        two.join(5_000);
        assertFalse(one.isAlive(), "A -> B must not hang acquiring monitors");
        assertFalse(two.isAlive(), "B -> A must not hang acquiring monitors");
        assertNull(unexpected.get());
        assertEquals(2, a.snapshot().pending() + b.snapshot().pending(),
                "two immutable notices must remain exactly once");
    }
    @Test
    void staleAndForeignSettlementLeasesCannotAckANewerDelivery() {
        var parent = new ActorSupervisorControlLane(1);
        var root = new ActorSupervisorControlLane(1);
        var child = ActorRuntime.ActorId.create();
        parent.reserve(child, 17);
        root.reserve(child, 17);
        assertTrue(parent.publish(notice(child, 17, 1)));

        var first = parent.poll().orElseThrow();
        parent.retry(first);
        var second = parent.poll().orElseThrow();

        // A timed-out first callback must not be able to ACK the second
        // callback's valid retry, even though child+generation are identical.
        assertThrows(IllegalStateException.class, () -> parent.ack(first));
        assertThrows(IllegalStateException.class, () -> parent.retry(first));
        assertThrows(IllegalStateException.class, () -> root.ack(second));
        assertEquals(1, parent.snapshot().inFlight());
        assertEquals(0, parent.snapshot().acknowledged());

        parent.transferPublishedTo(root);
        assertThrows(IllegalStateException.class, () -> parent.ack(second));
        assertEquals(0, parent.snapshot().reserved());
        var rootDelivery = root.poll().orElseThrow();
        assertEquals(1, rootDelivery.notice().sequence());
        root.ack(rootDelivery);
        assertThrows(IllegalStateException.class, () -> root.ack(rootDelivery));
        assertEquals(0, root.snapshot().reserved());
        assertEquals(1, root.snapshot().acknowledged());
    }

    @Test
    void deliveryTicketOverflowDoesNotDequeueOrLoseTheControlNotice()
            throws Exception {
        var lane = new ActorSupervisorControlLane(1);
        var child = ActorRuntime.ActorId.create();
        lane.reserve(child, 47);
        assertTrue(lane.publish(notice(child, 47, 100)));

        // Force an exceptional poll attempt before the delivery lease is
        // allocated. Ticket overflow must leave the notice in the original
        // queue; the same rule applies to failed receipt allocation.
        var ticketField = ActorSupervisorControlLane.class
                .getDeclaredField("nextDeliveryTicket");
        ticketField.setAccessible(true);
        ticketField.setLong(lane, Long.MAX_VALUE);

        var before = lane.snapshot();
        assertThrows(ArithmeticException.class, lane::poll);
        var after = lane.snapshot();
        assertEquals(before.pending(), after.pending());
        assertEquals(before.reserved(), after.reserved());
        assertEquals(before.inFlight(), after.inFlight());
        assertEquals(before.published(), after.published());

        ticketField.setLong(lane, 0L);
        var delivery = lane.poll().orElseThrow();
        assertEquals(child, delivery.notice().actor());
        assertEquals(100, delivery.notice().sequence());
        lane.ack(delivery);
        assertEquals(0, lane.snapshot().reserved());
    }

    @Test
    void failedReceiptConstructionPreservesPendingNotice() {
        var lane = new ActorSupervisorControlLane(1);
        var child = ActorRuntime.ActorId.create();
        lane.reserve(child, 1);
        assertTrue(lane.publish(notice(child, 1, 1)));
        var before = lane.snapshot();
        assertThrows(IllegalStateException.class, () -> lane.pollMapped(delivery -> {
            throw new IllegalStateException("receipt construction failed");
        }));
        assertEquals(before, lane.snapshot());
        var delivery = lane.poll().orElseThrow();
        assertEquals(child, delivery.notice().actor());
        lane.ack(delivery);
    }
}

