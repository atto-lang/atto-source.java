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
        var source = new ActorSupervisorControlLane(1);
        var root = new ActorSupervisorControlLane(1);
        var child = ActorRuntime.ActorId.create();

        source.reserve(child, 7);
        root.reserve(child, 7);
        source.publish(notice(child, 7, 1));
        setLongField(source, "transferred", Long.MAX_VALUE);

        source.transferPublishedTo(root);

        assertEquals(Long.MAX_VALUE, source.snapshot().transferred());
        assertEquals(0, source.snapshot().reserved());
        assertEquals(1, root.snapshot().pending());
        assertEquals(child, root.poll().orElseThrow().actor());
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
