package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ActorSupervisorControlCounterSaturationTest {
    @Test
    void cumulativeControlTelemetrySaturatesAtLongMax() throws Exception {
        var lane = new ActorSupervisorControlLane(2);
        var first = ActorRuntime.ActorId.create();
        var second = ActorRuntime.ActorId.create();
        var third = ActorRuntime.ActorId.create();

        lane.reserve(first, 1);
        lane.reserve(second, 1);
        setLongField(lane, "published", Long.MAX_VALUE);
        setLongField(lane, "acknowledged", Long.MAX_VALUE);
        setLongField(lane, "retried", Long.MAX_VALUE);
        setLongField(lane, "reservationRejected", Long.MAX_VALUE);
        setLongField(lane, "duplicatePublishRejected", Long.MAX_VALUE);

        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> lane.reserve(third, 1));
        assertTrue(lane.publish(notice(first, 1, 1)));
        assertFalse(lane.publish(notice(first, 1, 2)));

        assertEquals(first, lane.poll().orElseThrow().actor());
        lane.retry(first, 1);
        assertEquals(first, lane.poll().orElseThrow().actor());
        lane.ack(first, 1);

        var stats = lane.snapshot();
        assertEquals(Long.MAX_VALUE, stats.published());
        assertEquals(Long.MAX_VALUE, stats.acknowledged());
        assertEquals(Long.MAX_VALUE, stats.retried());
        assertEquals(Long.MAX_VALUE, stats.reservationRejected());
        assertEquals(Long.MAX_VALUE, stats.duplicatePublishRejected());
    }

    @Test
    void transferTelemetrySaturatesAtLongMax() throws Exception {
        var root = new ActorSupervisorControlLane(1);
        var source = root.childLane();
        var child = ActorRuntime.ActorId.create();

        source.reserve(child, 7);
        root.reserve(child, 7);
        source.publish(notice(child, 7, 1));
        setLongField(source, "transferred", Long.MAX_VALUE);

        source.transferPublishedToRoot();

        assertEquals(Long.MAX_VALUE, source.snapshot().transferred());
        assertEquals(0, source.snapshot().reserved());
        assertEquals(1, root.snapshot().pending());
        assertEquals(child, root.poll().orElseThrow().actor());
    }

    @Test
    void fallbackMovesInFlightAndQueuedNoticesTogetherAfterValidatingEveryReservation() {
        var root = new ActorSupervisorControlLane(3);
        var source = root.childLane();
        var inFlight = ActorRuntime.ActorId.create();
        var queued = ActorRuntime.ActorId.create();
        var live = ActorRuntime.ActorId.create();
        source.reserve(inFlight, 7);
        source.reserve(queued, 8);
        source.reserve(live, 9);
        root.reserve(inFlight, 7);
        source.publish(notice(inFlight, 7, 1));
        source.publish(notice(queued, 8, 2));
        assertEquals(inFlight, source.poll().orElseThrow().actor());

        assertThrows(IllegalStateException.class, root::transferPublishedToRoot);
        assertThrows(IllegalStateException.class, source::childLane);
        assertThrows(IllegalStateException.class, source::transferPublishedToRoot);
        assertEquals(0, root.pendingCount());
        assertEquals(1, source.snapshot().inFlight());
        assertEquals(1, source.pendingCount());

        root.reserve(queued, 8);
        source.transferPublishedToRoot();
        assertEquals(1, source.snapshot().reserved());
        assertEquals(0, source.snapshot().inFlight());
        assertEquals(0, source.pendingCount());
        assertEquals(inFlight, root.poll().orElseThrow().actor());
        root.ack(inFlight, 7);
        assertEquals(queued, root.poll().orElseThrow().actor());
        root.ack(queued, 8);
        source.retireGracefully(live, 9);
    }


    @Test
    void concurrentAckAndFallbackTransferNeverDoubleSettleOrLoseQueuedNotice() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 64; i++) {
                var root = new ActorSupervisorControlLane(2);
                var source = root.childLane();
                var first = ActorRuntime.ActorId.create();
                var second = ActorRuntime.ActorId.create();
                for (var child : java.util.List.of(first, second)) {
                    root.reserve(child, 1);
                    source.reserve(child, 1);
                    assertTrue(source.publish(notice(child, 1, child.equals(first) ? 1 : 2)));
                }
                assertEquals(first, source.poll().orElseThrow().actor());
                var start = new java.util.concurrent.CyclicBarrier(2);
                var ack = executor.submit(() -> {
                    start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        source.ack(first, 1);
                        return true;
                    } catch (IllegalStateException alreadyTransferred) {
                        return false;
                    }
                });
                var transfer = executor.submit(() -> {
                    start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    source.transferPublishedToRoot();
                    return null;
                });
                boolean ackWon = ack.get(5, java.util.concurrent.TimeUnit.SECONDS);
                transfer.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(0, source.snapshot().reserved());
                assertEquals(0, source.snapshot().pending());
                assertEquals(0, source.snapshot().inFlight());
                assertEquals(ackWon ? 1 : 2, root.pendingCount());
                if (ackWon) {
                    root.retireGracefully(first, 1);
                } else {
                    assertEquals(first, root.poll().orElseThrow().actor());
                    root.ack(first, 1);
                }
                assertEquals(second, root.poll().orElseThrow().actor());
                root.ack(second, 1);
                assertEquals(0, root.snapshot().reserved());
                assertEquals(0, root.snapshot().inFlight());
                assertTrue(root.poll().isEmpty());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleGenerationCannotPublishAcknowledgeRetryOrRetireReplacementSlot() {
        var lane = new ActorSupervisorControlLane(1);
        var child = ActorRuntime.ActorId.create();
        lane.reserve(child, 1);
        lane.publish(notice(child, 1, 1));
        lane.poll().orElseThrow();
        lane.ack(child, 1);
        lane.reserve(child, 2);
        assertThrows(IllegalStateException.class, () -> lane.publish(notice(child, 1, 2)));
        assertThrows(IllegalStateException.class, () -> lane.retireGracefully(child, 1));
        assertTrue(lane.publish(notice(child, 2, 3)));
        assertEquals(2, lane.poll().orElseThrow().generation());
        assertThrows(IllegalStateException.class, () -> lane.ack(child, 1));
        assertThrows(IllegalStateException.class, () -> lane.retry(child, 1));
        assertEquals(1, lane.snapshot().inFlight());
        lane.ack(child, 2);
        assertEquals(0, lane.snapshot().reserved());
    }

    private static ActorRuntime.ActorFailureNotice notice(
            ActorRuntime.ActorId child,
            long generation,
            long sequence) {
        return new ActorRuntime.ActorFailureNotice(
                child,
                generation,
                Optional.empty(),
                ActorRuntime.ActorKind.SHARED,
                ActorRuntime.ActorFailureKind.FAILURE,
                sequence,
                IllegalStateException.class.getName());
    }

    private static void setLongField(Object target, String fieldName, long value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }
}
