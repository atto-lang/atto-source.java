package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ActorSupervisorControlAtomicTransferTest {
    private static ActorRuntime.ActorFailureNotice notice(ActorRuntime.ActorId id,
            long generation, long sequence) {
        return new ActorRuntime.ActorFailureNotice(id, generation, Optional.empty(),
                ActorRuntime.ActorKind.SHARED, ActorRuntime.ActorFailureKind.FAILURE,
                sequence, IllegalStateException.class.getName());
    }

    @Test
    void missingLaterReservationLeavesInFlightAndPendingUnchanged() {
        var source = new ActorSupervisorControlLane(3);
        var root = new ActorSupervisorControlLane(3);
        var first = ActorRuntime.ActorId.create();
        var second = ActorRuntime.ActorId.create();
        var third = ActorRuntime.ActorId.create();
        source.reserve(first, 1);
        source.reserve(second, 1);
        source.reserve(third, 1);
        root.reserve(first, 1);
        root.reserve(second, 1);
        source.publish(notice(first, 1, 1));
        source.publish(notice(second, 1, 2));
        source.publish(notice(third, 1, 3));
        assertEquals(first, source.poll().orElseThrow().notice().actor());

        var sourceBefore = source.snapshot();
        var rootBefore = root.snapshot();
        assertThrows(IllegalStateException.class, () -> source.transferPublishedTo(root));
        assertEquals(sourceBefore, source.snapshot());
        assertEquals(rootBefore, root.snapshot());
        assertTrue(root.poll().isEmpty());

        root.reserve(third, 1);
        source.transferPublishedTo(root);
        assertEquals(0, source.snapshot().reserved());
        assertEquals(3, source.snapshot().transferred());
        for (var id : new ActorRuntime.ActorId[]{first, second, third}) {
            var delivery = root.poll().orElseThrow();
            assertEquals(id, delivery.notice().actor());
            root.ack(delivery);
        }
        assertTrue(root.poll().isEmpty());
        assertEquals(0, root.snapshot().reserved());
    }

    @Test
    void duplicateLaterNoticeCannotPartiallyPublishEarlierNotice() {
        var source = new ActorSupervisorControlLane(2);
        var root = new ActorSupervisorControlLane(2);
        var first = ActorRuntime.ActorId.create();
        var second = ActorRuntime.ActorId.create();
        for (var id : new ActorRuntime.ActorId[]{first, second}) {
            source.reserve(id, 1);
            root.reserve(id, 1);
        }
        source.publish(notice(first, 1, 1));
        source.publish(notice(second, 1, 2));
        root.publish(notice(second, 1, 2));
        var sourceBefore = source.snapshot();
        var rootBefore = root.snapshot();
        assertThrows(IllegalStateException.class, () -> source.transferPublishedTo(root));
        assertEquals(sourceBefore, source.snapshot());
        assertEquals(rootBefore, root.snapshot());
        var delivery = root.poll().orElseThrow();
        assertEquals(second, delivery.notice().actor());
        root.ack(delivery);
        assertTrue(root.poll().isEmpty());
    }

    @Test
    void staleGenerationCannotPartiallyTransfer() {
        var source = new ActorSupervisorControlLane(2);
        var root = new ActorSupervisorControlLane(2);
        var first = ActorRuntime.ActorId.create();
        var second = ActorRuntime.ActorId.create();
        source.reserve(first, 1);
        source.reserve(second, 1);
        root.reserve(first, 1);
        root.reserve(second, 2);
        source.publish(notice(first, 1, 1));
        source.publish(notice(second, 1, 2));
        var sourceBefore = source.snapshot();
        var rootBefore = root.snapshot();
        assertThrows(IllegalStateException.class, () -> source.transferPublishedTo(root));
        assertEquals(sourceBefore, source.snapshot());
        assertEquals(rootBefore, root.snapshot());
        assertTrue(root.poll().isEmpty());
    }
}
