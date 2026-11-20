package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BoundedDemandChannelTest {
    private static BoundedDemandChannel<Integer> ints(int capacity, int maxLoans, int maxBytes) {
        return new BoundedDemandChannel<>(capacity, maxLoans, maxBytes, 32, ignored -> 8);
    }

    @Test
    void demandIsRequiredAndRetainsRejectedReference() {
        record Payload(int id) { }
        BoundedDemandChannel<Payload> port =
                new BoundedDemandChannel<>(1, 1, 16, 2, ignored -> 8);
        Payload item = new Payload(7);
        var before = port.offer(item);
        assertEquals(BoundedDemandChannel.Refusal.NO_SUBSCRIBER, before.reason());
        assertSame(item, before.retained().orElseThrow());
        var subscription = port.subscribe();
        var noDemand = port.offer(item);
        assertEquals(BoundedDemandChannel.Refusal.NO_DEMAND, noDemand.reason());
        assertSame(item, noDemand.retained().orElseThrow());
        subscription.request(1);
        assertTrue(port.offer(item).admitted());
        assertEquals(0, port.outstandingDemand());
        assertEquals(7, subscription.poll().delivery().orElseThrow().value().id());
    }

    @Test
    void fifoAndAdmissionAreBoundedIndependentlyFromLoans() {
        var port = ints(2, 1, 32);
        var subscriber = port.subscribe();
        subscriber.request(4);
        assertTrue(port.offer(1).admitted());
        assertTrue(port.offer(2).admitted());
        assertEquals(BoundedDemandChannel.Refusal.FULL, port.offer(3).reason());
        var first = subscriber.poll().delivery().orElseThrow();
        assertEquals(1, first.value());
        assertEquals(BoundedDemandChannel.PollStatus.NOT_READY, subscriber.poll().status());
        assertTrue(port.offer(3).admitted());
        assertEquals(24, port.retainedBytes());
        assertTrue(subscriber.ack(first.ticket()));
        assertFalse(subscriber.ack(first.ticket()));
        assertEquals(16, port.retainedBytes());
        var second = subscriber.poll().delivery().orElseThrow();
        assertEquals(2, second.value());
        assertTrue(subscriber.ack(second.ticket()));
        var third = subscriber.poll().delivery().orElseThrow();
        assertEquals(3, third.value());
        assertTrue(subscriber.ack(third.ticket()));
        assertEquals(0, port.retainedBytes());
    }

    @Test
    void byteBudgetCountsCheckedOutButUnacknowledgedValues() {
        var port = ints(2, 2, 16);
        var sub = port.subscribe();
        sub.request(4);
        assertTrue(port.offer(10).admitted());
        assertTrue(port.offer(20).admitted());
        var first = sub.poll().delivery().orElseThrow();
        assertEquals(BoundedDemandChannel.Refusal.BYTE_BUDGET, port.offer(30).reason());
        assertTrue(sub.ack(first.ticket()));
        assertTrue(port.offer(30).admitted());
    }

    @Test
    void completionDrainsCommittedFifoAndNeverAddsNextAfterTerminal() {
        var port = ints(2, 2, 16);
        var sub = port.subscribe();
        sub.request(2);
        port.offer(1);
        port.offer(2);
        assertTrue(port.complete());
        assertFalse(port.complete());
        assertEquals(BoundedDemandChannel.Refusal.CLOSED, port.offer(3).reason());
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var delivery = sub.poll().delivery().orElseThrow();
            seen.add(delivery.value());
            assertTrue(sub.ack(delivery.ticket()));
        }
        assertEquals(List.of(1, 2), seen);
        assertEquals(BoundedDemandChannel.PollStatus.COMPLETE, sub.poll().status());
        assertEquals(0, port.retainedBytes());
    }

    @Test
    void cancellationReleasesQueueButNotUnacknowledgedConsumerLoans() {
        var port = ints(2, 2, 16);
        var sub = port.subscribe();
        sub.request(2);
        port.offer(1);
        port.offer(2);
        long loan = sub.poll().delivery().orElseThrow().ticket();
        assertTrue(sub.cancel());
        assertFalse(sub.cancel());
        assertEquals(0, port.queuedCount());
        assertEquals(1, port.inFlightCount());
        assertEquals(8, port.retainedBytes());
        assertEquals(BoundedDemandChannel.PollStatus.CANCELLED, sub.poll().status());
        assertTrue(sub.ack(loan));
        assertFalse(sub.ack(loan));
        assertFalse(sub.ack(loan + 1000));
        assertEquals(0, port.retainedBytes());
    }

    @Test
    void rejectsUnboundedCreditsInvalidPayloadBudgetsAndSecondSubscriber() {
        assertThrows(IllegalArgumentException.class, () -> ints(0, 1, 8));
        var port = ints(1, 1, 8);
        var sub = port.subscribe();
        assertThrows(IllegalStateException.class, port::subscribe);
        assertThrows(IllegalArgumentException.class, () -> sub.request(0));
        assertThrows(IllegalArgumentException.class, () -> sub.request(Long.MAX_VALUE));
        sub.request(1);
        assertTrue(port.offer(4).admitted());
        assertEquals(BoundedDemandChannel.Refusal.NO_DEMAND, port.offer(5).reason());
        assertThrows(IllegalArgumentException.class, () ->
                new BoundedDemandChannel<String>(1, 1, 8, 1, ignored -> 9)
                        .offer("big"));
    }

    @Test
    void completedStreamCannotBeCancelledAfterTerminal() {
        var port = ints(2, 2, 16);
        var sub = port.subscribe();
        sub.request(2);
        assertTrue(port.offer(11).admitted());
        assertTrue(port.offer(22).admitted());
        assertTrue(port.complete());
        assertFalse(port.cancel(), "complete is an irrevocable terminal outcome");
        assertEquals(2, port.queuedCount(), "cancel cannot erase committed completion items");
        var first = sub.poll().delivery().orElseThrow();
        var second = sub.poll().delivery().orElseThrow();
        assertEquals(11, first.value());
        assertEquals(22, second.value());
        assertTrue(sub.ack(first.ticket()));
        assertTrue(sub.ack(second.ticket()));
        assertEquals(BoundedDemandChannel.PollStatus.COMPLETE, sub.poll().status());
        assertEquals(0, port.retainedBytes());
    }

    @Test
    void completionAndCancellationRacePublishesExactlyOneTerminal() throws Exception {
        for (int attempt = 0; attempt < 64; attempt++) {
            var port = ints(2, 2, 16);
            var sub = port.subscribe();
            sub.request(1);
            assertTrue(port.offer(7).admitted());
            var gate = new java.util.concurrent.CountDownLatch(1);
            var completeWon = new java.util.concurrent.atomic.AtomicBoolean();
            var cancelWon = new java.util.concurrent.atomic.AtomicBoolean();
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread complete = new Thread(() -> {
                try {
                    gate.await();
                    completeWon.set(port.complete());
                } catch (Throwable ex) { failure.compareAndSet(null, ex); }
            });
            Thread cancel = new Thread(() -> {
                try {
                    gate.await();
                    cancelWon.set(port.cancel());
                } catch (Throwable ex) { failure.compareAndSet(null, ex); }
            });
            complete.start();
            cancel.start();
            gate.countDown();
            complete.join(5_000);
            cancel.join(5_000);
            assertFalse(complete.isAlive());
            assertFalse(cancel.isAlive());
            assertNull(failure.get());
            assertTrue(completeWon.get() ^ cancelWon.get(), "exactly one terminal wins");
            if (completeWon.get()) {
                var item = sub.poll().delivery().orElseThrow();
                assertEquals(7, item.value());
                assertTrue(sub.ack(item.ticket()));
                assertEquals(BoundedDemandChannel.PollStatus.COMPLETE, sub.poll().status());
            } else {
                assertEquals(BoundedDemandChannel.PollStatus.CANCELLED, sub.poll().status());
            }
            assertEquals(0, port.retainedBytes());
        }
    }

    @Test
    void invalidSizerNeverConsumesCreditOrPublishesValue() {
        BoundedDemandChannel<Integer> port =
                new BoundedDemandChannel<>(1, 1, 8, 1, x -> {
                    if (x == -1) throw new IllegalArgumentException("invalid payload");
                    return 8;
                });
        var sub = port.subscribe();
        sub.request(1);
        assertThrows(IllegalArgumentException.class, () -> port.offer(-1));
        assertEquals(1, port.outstandingDemand());
        assertEquals(0, port.queuedCount());
        assertEquals(0, port.retainedBytes());
        assertTrue(port.offer(5).admitted());
        var item = sub.poll().delivery().orElseThrow();
        assertEquals(5, item.value());
        assertTrue(sub.ack(item.ticket()));
    }

    @Test
    void pathologicalDescriptorBudgetsFailBeforeArrayAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> ints(BoundedDemandChannel.MAX_QUEUED_SLOTS + 1, 1, 8));
        assertThrows(IllegalArgumentException.class,
                () -> ints(1, BoundedDemandChannel.MAX_IN_FLIGHT + 1, 8));
    }

}
