package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises real host ActorEventBus and ChannelRuntime cancellation/accounting
 * paths. These test bodies are authored, not executed, until exact-head JUnit.
 */
@Timeout(30)
final class ActorEventBusReliableUnwindTest {

    @Test
    void multiSubscriberCancelReleasesEveryPendingReliableWriter()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus bus = group.events();
            bus.defineTopic("fanout-cancel", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            CountDownLatch subscribed = new CountDownLatch(2);
            AtomicReference<Throwable> setupFailure = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> a =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> {
                        try {
                            join.join();
                            bus.subscribe("fanout-cancel");
                        } catch (Throwable problem) {
                            setupFailure.compareAndSet(null, problem);
                        } finally {
                            subscribed.countDown();
                        }
                    });
            ActorRuntime.ActorRef<Integer> b =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> {
                        try {
                            join.join();
                            bus.subscribe("fanout-cancel");
                        } catch (Throwable problem) {
                            setupFailure.compareAndSet(null, problem);
                        } finally {
                            subscribed.countDown();
                        }
                    });
            a.send(0);
            b.send(0);
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            assertNull(setupFailure.get(), () -> String.valueOf(setupFailure.get()));

            ActorEventBus.PublishReceipt buffered = bus.publishSystem(
                    "fanout-cancel", new ArrayList<>(List.of(1, 10)));
            ActorEventBus.PublishReceipt pending = bus.publishSystem(
                    "fanout-cancel", new ArrayList<>(List.of(2, 20)));
            assertEquals(2, buffered.subscribers());
            assertEquals(2, pending.subscribers());
            assertTrue(buffered.completion().isDone());
            assertFalse(pending.completion().isDone(),
                    "both subscribers must apply bounded asynchronous backpressure");
            long beforeCancel = runtime.sharedMemoryBytes();
            assertTrue(beforeCancel > 0);

            assertTrue(pending.completion().cancel(false));
            assertTrue(pending.completion().isCancelled());
            assertTrue(runtime.sharedMemoryBytes() > 0,
                    "the earlier buffered event remains retained until close");

            ActorEventBus.PublishReceipt next = bus.publishSystem(
                    "fanout-cancel", new ArrayList<>(List.of(3, 30)));
            assertFalse(next.completion().isDone(),
                    "cancelled pending sends must free quota for a new pending publication");

            group.close();
            assertTrue(next.completion().isCompletedExceptionally());
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "both channels, cancelled writers and queued events must release their bytes");
            assertTrue(a.isAlive());
            assertTrue(b.isAlive());
            a.stop();
            b.stop();
            assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void oneSubscriberClosingFailsFanoutReceiptAndRevokesOtherPendingSends()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus bus = group.events();
            bus.defineTopic("fanout-close", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            CountDownLatch subscribed = new CountDownLatch(2);
            CountDownLatch closed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorEventBus.Subscription<List<Integer>>> closing =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> a =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> {
                        try {
                            if (message == 0) {
                                join.join();
                                closing.set(bus.subscribe("fanout-close"));
                            } else if (message == 1) {
                                closing.get().close();
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            if (message == 0) subscribed.countDown();
                            if (message == 1) closed.countDown();
                        }
                    });
            ActorRuntime.ActorRef<Integer> b =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> {
                        try {
                            join.join();
                            bus.subscribe("fanout-close");
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            subscribed.countDown();
                        }
                    });
            a.send(0);
            b.send(0);
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));

            ActorEventBus.PublishReceipt first =
                    bus.publishSystem("fanout-close", new ArrayList<>(List.of(11)));
            ActorEventBus.PublishReceipt pending =
                    bus.publishSystem("fanout-close", new ArrayList<>(List.of(12)));
            assertTrue(first.completion().isDone());
            assertFalse(pending.completion().isDone());

            a.send(1);
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(pending.completion().isCompletedExceptionally(),
                    "closing one subscriber must fail the shared RELIABLE completion");
            assertFalse(pending.completion().isCancelled(),
                    "a failed child is different from explicit caller cancellation");
            group.close();
            assertEquals(0L, runtime.sharedMemoryBytes());
            assertTrue(a.isAlive());
            assertTrue(b.isAlive());
            a.stop();
            b.stop();
            assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sequenceOverflowFailsBeforeWriteWithoutRetainingFrozenPayload()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorEventBus bus = group.events();
            bus.defineTopic("overflow", ActorEventBus.DeliveryPolicy.LOSSY, 1);
            AtomicLong sequence = topicSequence(bus, "overflow");
            sequence.set(Long.MAX_VALUE - 1L);

            ActorEventBus.PublishReceipt last = bus.publishSystem(
                    "overflow", new ArrayList<>(List.of(1)));
            assertTrue(last.completion().isDone());
            assertEquals(Long.MAX_VALUE, sequence.get());
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "unsubscribed publication must release its frozen payload immediately");

            assertThrows(IllegalStateException.class, () -> bus.publishSystem(
                    "overflow", new ArrayList<>(List.of(2))));
            assertEquals(Long.MAX_VALUE, sequence.get(),
                    "sequence must not wrap into a negative event identifier");
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "sequence exhaustion must not strand any frozen payload reservation");
        }
    }

    private static AtomicLong topicSequence(ActorEventBus bus, String name)
            throws Exception {
        Field topicsField = ActorEventBus.class.getDeclaredField("topics");
        topicsField.setAccessible(true);
        Map<?, ?> topics = (Map<?, ?>) topicsField.get(bus);
        Object topic = topics.get(name);
        assertNotNull(topic);
        Field sequence = topic.getClass().getDeclaredField("sequence");
        sequence.setAccessible(true);
        return (AtomicLong) sequence.get(topic);
    }
}
