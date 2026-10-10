package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class SupervisorFailureLaneTest {
    private static SupervisorFailureLane.ActorKey id(int n) {
        return new SupervisorFailureLane.ActorKey("child-" + n, 1);
    }

    @Test
    void terminalEventsBypassFloodedUserQueueAndRemainExactlyOnce() {
        SupervisorFailureLane lane = new SupervisorFailureLane(3);
        ArrayDeque<Integer> userMail = new ArrayDeque<>();
        for (int i = 0; i < 100_000; i++) userMail.addLast(i);
        lane.reserve(id(1));
        lane.reserve(id(2));
        assertTrue(lane.publish(new SupervisorFailureLane.Failure(
                id(1), SupervisorFailureLane.Kind.PANIC, "bad invariant")));
        assertFalse(lane.publish(new SupervisorFailureLane.Failure(
                id(1), SupervisorFailureLane.Kind.RAISE, "duplicate")));
        assertTrue(lane.publish(new SupervisorFailureLane.Failure(
                id(2), SupervisorFailureLane.Kind.RAISE, "escalated")));
        List<SupervisorFailureLane.Kind> handled = new ArrayList<>();
        SupervisorFailureLane.Handler handler = new RecordingHandler(handled);
        assertTrue(lane.dispatchOne(handler));
        assertTrue(lane.dispatchOne(handler));
        assertFalse(lane.dispatchOne(handler));
        assertEquals(List.of(SupervisorFailureLane.Kind.PANIC,
                SupervisorFailureLane.Kind.RAISE), handled);
        assertEquals(100_000, userMail.size());
        assertEquals(0, lane.reservedCount());
    }

    @Test
    void reservationsBoundFailuresAndRejectStaleGenerations() {
        SupervisorFailureLane lane = new SupervisorFailureLane(1);
        lane.reserve(id(1));
        assertThrows(IllegalStateException.class, () -> lane.reserve(id(2)));
        assertThrows(IllegalStateException.class, () -> lane.reserve(id(1)));
        assertThrows(IllegalStateException.class, () -> lane.publish(
                new SupervisorFailureLane.Failure(id(2),
                        SupervisorFailureLane.Kind.THROW, "unreserved")));
        lane.retireGracefully(id(1));
        lane.reserve(new SupervisorFailureLane.ActorKey("child-1", 2));
        assertThrows(IllegalStateException.class, () -> lane.publish(
                new SupervisorFailureLane.Failure(id(1),
                        SupervisorFailureLane.Kind.PANIC, "stale")));
    }

    @Test
    void failedHandlerKeepsTerminalEventForRetry() {
        SupervisorFailureLane lane = new SupervisorFailureLane(1);
        lane.reserve(id(1));
        lane.publish(new SupervisorFailureLane.Failure(
                id(1), SupervisorFailureLane.Kind.HOST_FAILURE, "ffi failure"));
        SupervisorFailureLane.Handler bad = new RecordingHandler(new ArrayList<>()) {
            @Override public void onHostFailure(SupervisorFailureLane.Failure event) {
                throw new IllegalStateException("handler failed");
            }
        };
        assertThrows(IllegalStateException.class, () -> lane.dispatchOne(bad));
        assertEquals(1, lane.pendingCount());
        assertEquals(1, lane.reservedCount());
        List<SupervisorFailureLane.Kind> handled = new ArrayList<>();
        assertTrue(lane.dispatchOne(new RecordingHandler(handled)));
        assertEquals(List.of(SupervisorFailureLane.Kind.HOST_FAILURE), handled);
    }

    @Test
    void everyFailureCaseHasExplicitHandlerAndSummaryBound() {
        SupervisorFailureLane lane = new SupervisorFailureLane(5);
        SupervisorFailureLane.Kind[] kinds = SupervisorFailureLane.Kind.values();
        for (int i = 0; i < kinds.length; i++) {
            lane.reserve(id(i));
            lane.publish(new SupervisorFailureLane.Failure(
                    id(i), kinds[i], "x".repeat(10_000)));
        }
        List<SupervisorFailureLane.Kind> handled = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            assertEquals(512, lane.poll().orElseThrow().summary().length());
            lane.retry(id(i));
        }
        for (int i = 0; i < kinds.length; i++) {
            assertTrue(lane.dispatchOne(new RecordingHandler(handled)));
        }
        assertEquals(List.of(kinds), handled);
    }

    private static class RecordingHandler implements SupervisorFailureLane.Handler {
        private final List<SupervisorFailureLane.Kind> handled;
        RecordingHandler(List<SupervisorFailureLane.Kind> handled) { this.handled = handled; }
        private void record(SupervisorFailureLane.Failure failure) { handled.add(failure.kind()); }
        @Override public void onThrow(SupervisorFailureLane.Failure event) { record(event); }
        @Override public void onRaise(SupervisorFailureLane.Failure event) { record(event); }
        @Override public void onPanic(SupervisorFailureLane.Failure event) { record(event); }
        @Override public void onCancel(SupervisorFailureLane.Failure event) { record(event); }
        @Override public void onHostFailure(SupervisorFailureLane.Failure event) { record(event); }
    }
}
