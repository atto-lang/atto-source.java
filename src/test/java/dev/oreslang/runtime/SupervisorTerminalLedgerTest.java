package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class SupervisorTerminalLedgerTest {
    @Test
    void prepaidTerminalSlotsCannotOverflowOrDropUnderSaturation() {
        var ledger = new SupervisorTerminalLedger(2);
        var a = ledger.reserve(ActorRuntime.ActorId.create(), 1);
        var b = ledger.reserve(ActorRuntime.ActorId.create(), 2);
        assertThrows(IllegalStateException.class,
                () -> ledger.reserve(ActorRuntime.ActorId.create(), 3));
        assertEquals(1L, ledger.stats().reservationRejections());

        ledger.publish(a, SupervisorTerminalLedger.Kind.FAILURE);
        ledger.publish(b, SupervisorTerminalLedger.Kind.CANCELLED);
        assertEquals(2, ledger.stats().ready());
        assertEquals(2, ledger.stats().published());

        SupervisorTerminalLedger.Receipt first = ledger.peek().orElseThrow();
        assertSame(first, ledger.peek().orElseThrow(),
                "unacknowledged terminal receipt must be replayable");

        assertThrows(IllegalStateException.class,
                () -> ledger.reserve(ActorRuntime.ActorId.create(), 3),
                "terminated actors do not free CONTROL credit before ack");

        ledger.ack(first);
        var replacement = ledger.reserve(ActorRuntime.ActorId.create(), 3);
        assertNotNull(replacement);
        assertEquals(2, ledger.stats().inUse());
        assertEquals(1, ledger.stats().ready());
    }

    @Test
    void prepaidPublicationHasNoGlobalOrdinalExhaustionFailureMode() {
        var ledger = new SupervisorTerminalLedger(4);
        var reservations = new ArrayList<SupervisorTerminalLedger.Reservation>();
        for (int i = 0; i < 4; i++) {
            reservations.add(ledger.reserve(ActorRuntime.ActorId.create(), i + 1));
        }
        for (var reservation : reservations) {
            assertDoesNotThrow(() -> ledger.publish(
                    reservation, SupervisorTerminalLedger.Kind.FAILURE));
        }
        assertEquals(4, ledger.stats().ready());
        assertEquals(4L, ledger.stats().published());

        while (ledger.peek().isPresent()) {
            ledger.ack(ledger.peek().orElseThrow());
        }
        assertEquals(4L, ledger.stats().acknowledged());
        assertEquals(0, ledger.stats().inUse());
    }

    @Test
    void acknowledgementIsOrderedExactlyOnceAndLedgerAffine() {
        var left = new SupervisorTerminalLedger(2);
        var right = new SupervisorTerminalLedger(1);
        var a = left.reserve(ActorRuntime.ActorId.create(), 1);
        var b = left.reserve(ActorRuntime.ActorId.create(), 2);
        left.publish(a, SupervisorTerminalLedger.Kind.GRACEFUL);
        left.publish(b, SupervisorTerminalLedger.Kind.FAILURE);

        var first = left.peek().orElseThrow();
        assertThrows(SecurityException.class, () -> right.ack(first));
        left.ack(first);
        assertThrows(IllegalStateException.class, () -> left.ack(first));

        var second = left.peek().orElseThrow();
        assertEquals(b.actorId(), second.actorId());
        left.ack(second);
        assertTrue(left.peek().isEmpty());
        assertEquals(2, left.stats().acknowledged());
    }

    @Test
    void publishedReceiptCannotBeErasedByReleaseAndStaleReservationCannotRecur() {
        var ledger = new SupervisorTerminalLedger(1);
        var first = ledger.reserve(ActorRuntime.ActorId.create(), 7);
        ledger.publish(first, SupervisorTerminalLedger.Kind.FAILURE);
        assertThrows(IllegalStateException.class,
                () -> ledger.releaseUnstarted(first));
        var receipt = ledger.peek().orElseThrow();
        ledger.ack(receipt);

        var second = ledger.reserve(ActorRuntime.ActorId.create(), 8);
        assertThrows(SecurityException.class,
                () -> ledger.publish(first, SupervisorTerminalLedger.Kind.FAILURE));
        ledger.releaseUnstarted(second);
        assertEquals(1, ledger.stats().releasedBeforeTerminal());
    }

    @Test
    void receiptContainsNoFailureTextOrGuestPayloadSurface() {
        var ledger = new SupervisorTerminalLedger(1);
        var reservation = ledger.reserve(ActorRuntime.ActorId.create(), 1);
        ledger.publish(reservation, SupervisorTerminalLedger.Kind.QUOTA_EXCEEDED);
        var receipt = ledger.peek().orElseThrow();

        String rendered = receipt.toString();
        assertTrue(rendered.contains("QUOTA_EXCEEDED"));
        assertFalse(rendered.contains("Throwable"));
        assertFalse(rendered.contains("Exception"));
        assertFalse(rendered.contains("message="));
        assertFalse(rendered.contains("sequence="));
    }

    @Test
    void concurrentReservationCannotOverbookPrepaidControlCapacity() throws Exception {
        var ledger = new SupervisorTerminalLedger(8);
        var start = new CountDownLatch(1);
        var admitted = new AtomicInteger();
        var reservations =
                java.util.Collections.synchronizedList(
                        new ArrayList<SupervisorTerminalLedger.Reservation>());
        try (var pool = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 64; i++) {
                final int incarnation = i + 1;
                tasks.add(pool.submit(() -> {
                    start.await();
                    try {
                        var reservation = ledger.reserve(
                                ActorRuntime.ActorId.create(), incarnation);
                        reservations.add(reservation);
                        admitted.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        // Explicit capacity rejection is the required outcome.
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }

        assertEquals(8, admitted.get());
        assertEquals(8, ledger.stats().inUse());
        assertEquals(0, ledger.stats().ready());

        for (var reservation : reservations) {
            ledger.publish(reservation, SupervisorTerminalLedger.Kind.FAILURE);
        }
        assertEquals(8, ledger.stats().ready());
        assertEquals(8, ledger.stats().published());
    }

    @Test
    void exhaustedFreeEpochIsRetiredWithoutPoisoningOtherCapacity() throws Exception {
        var ledger = new SupervisorTerminalLedger(2);
        setSlotEpoch(ledger, 0, Long.MAX_VALUE);

        var reservation = ledger.reserve(ActorRuntime.ActorId.create(), 1);
        var afterAdmission = ledger.stats();
        assertEquals(1, afterAdmission.retiredEpochSlots());
        assertEquals(1, afterAdmission.inUse());
        assertEquals(0, afterAdmission.ready());

        assertThrows(IllegalStateException.class,
                () -> ledger.reserve(ActorRuntime.ActorId.create(), 2),
                "one retired slot plus one active slot must exhaust effective capacity");

        ledger.releaseUnstarted(reservation);
        var replacement = ledger.reserve(ActorRuntime.ActorId.create(), 3);
        assertNotNull(replacement,
                "retiring one poisoned epoch must not brick unrelated reusable slots");
        assertEquals(1, ledger.stats().retiredEpochSlots());
    }

    @Test
    void slotThatUsesFinalEpochRetiresWhenItsReservationIsReleased() throws Exception {
        var ledger = new SupervisorTerminalLedger(2);
        setSlotEpoch(ledger, 0, Long.MAX_VALUE - 1L);

        var finalEpoch = ledger.reserve(ActorRuntime.ActorId.create(), 1);
        ledger.releaseUnstarted(finalEpoch);

        assertEquals(1, ledger.stats().retiredEpochSlots());
        assertEquals(0, ledger.stats().inUse());

        var other = ledger.reserve(ActorRuntime.ActorId.create(), 2);
        assertNotNull(other);
        assertEquals(1, ledger.stats().inUse());
        assertEquals(1, ledger.stats().retiredEpochSlots());
    }

    private static void setSlotEpoch(
            SupervisorTerminalLedger ledger,
            int index,
            long epoch) throws Exception {
        Field slotsField = SupervisorTerminalLedger.class.getDeclaredField("slots");
        slotsField.setAccessible(true);
        Object slots = slotsField.get(ledger);
        Object slot = Array.get(slots, index);
        Field epochField = slot.getClass().getDeclaredField("epoch");
        epochField.setAccessible(true);
        epochField.setLong(slot, epoch);
    }

    @Test
    void supervisorCrashBeforeAckLeavesSameHeadForReplay() {
        var ledger = new SupervisorTerminalLedger(2);
        var reservation = ledger.reserve(ActorRuntime.ActorId.create(), 11);
        ledger.publish(reservation, SupervisorTerminalLedger.Kind.STARTUP_FAILED);

        var beforeCrash = ledger.peek().orElseThrow();
        // Simulate policy failure: no ack.
        var afterRestart = ledger.peek().orElseThrow();
        assertSame(beforeCrash, afterRestart);
        assertEquals(beforeCrash.actorId(), afterRestart.actorId());
        assertEquals(beforeCrash.incarnation(), afterRestart.incarnation());
        assertEquals(1, ledger.stats().ready());

        ledger.ack(afterRestart);
        assertEquals(0, ledger.stats().ready());
    }
}
