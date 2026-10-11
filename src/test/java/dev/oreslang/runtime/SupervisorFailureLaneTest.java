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

    @Test
    void concurrentProducersCannotLoseReservedTerminalEvents() throws Exception {
        int total = 256;
        SupervisorFailureLane lane = new SupervisorFailureLane(total);
        for (int i = 0; i < total; i++) lane.reserve(id(i));

        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var producers = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                final int segment = worker;
                producers.add(pool.submit(() -> {
                    start.await();
                    for (int i = segment; i < total; i += 4) {
                        if (!lane.publish(new SupervisorFailureLane.Failure(
                                id(i), SupervisorFailureLane.Kind.PANIC, "panic"))) {
                            throw new IllegalStateException("lost first publication");
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            java.util.Set<SupervisorFailureLane.ActorKey> handled = new java.util.HashSet<>();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (handled.size() < total && System.nanoTime() < deadline) {
                var next = lane.poll();
                if (next.isEmpty()) {
                    Thread.yield();
                    continue;
                }
                var event = next.orElseThrow();
                assertTrue(handled.add(event.actor()), "duplicate terminal event");
                lane.ack(event.actor());
            }
            for (var producer : producers) producer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(total, handled.size());
            assertEquals(0, lane.pendingCount());
            assertEquals(0, lane.reservedCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fifoRetryMovesOnlyTheInFlightEventToTail() {
        SupervisorFailureLane lane = new SupervisorFailureLane(3);
        lane.reserve(id(1));
        lane.reserve(id(2));
        lane.reserve(id(3));
        for (int i = 1; i <= 3; i++) {
            lane.publish(new SupervisorFailureLane.Failure(
                    id(i), SupervisorFailureLane.Kind.RAISE, "raised"));
        }
        assertEquals(id(1), lane.poll().orElseThrow().actor());
        lane.retry(id(1));
        assertEquals(id(2), lane.poll().orElseThrow().actor());
        lane.ack(id(2));
        assertEquals(id(3), lane.poll().orElseThrow().actor());
        lane.ack(id(3));
        assertEquals(id(1), lane.poll().orElseThrow().actor());
        lane.ack(id(1));
        assertEquals(0, lane.reservedCount());
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
