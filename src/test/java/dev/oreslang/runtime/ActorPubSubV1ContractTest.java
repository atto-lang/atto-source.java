package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class ActorPubSubV1ContractTest {
    @Test
    void admissionReceiptPreservesLegacyConstructorAndUnsignedSequenceText() {
        ActorEventBus.PublishReceipt legacy = new ActorEventBus.PublishReceipt(
                0, 0, 0, 0, OresFuture.completed(java.util.List.of()));
        assertEquals(0L, legacy.sequence());
        assertEquals("0", legacy.sequenceUnsignedString());
        assertEquals(0, legacy.accepted());

        ActorEventBus.PublishReceipt maximum = new ActorEventBus.PublishReceipt(
                0, 0, 0, 0, OresFuture.completed(java.util.List.of()), -1L);
        assertEquals("18446744073709551615", maximum.sequenceUnsignedString(),
                "unsigned u64 receipts must never round through signed decimal");
    }

    @Test
    void publisherNeedNotSubscribeAndSubscriberReceivesAcceptedEvent() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability subscriberJoin = group.joinCapability();
            ActorRuntime.ActorGroupJoinCapability publisherJoin = group.joinCapability();
            ActorEventBus.Topic<String> completed = group.events().topic(
                    "payment.completed", ActorEventBus.DeliveryPolicy.RELIABLE, 4);

            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch published = new CountDownLatch(1);
            CountDownLatch read = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorEventBus.Subscription<String>> subscription = new AtomicReference<>();
            AtomicReference<String> observed = new AtomicReference<>();

            ActorRuntime.ActorRef<String> subscriber = runtime.spawnShared(() -> (message, context) -> {
                try {
                    if ("subscribe".equals(message)) {
                        subscriberJoin.join();
                        subscription.set(completed.subscribe());
                        subscribed.countDown();
                        return;
                    }
                    observed.set(subscription.get().tryRead().orElseThrow().value());
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    if ("read".equals(message)) read.countDown();
                }
            });

            ActorRuntime.ActorRef<String> publisher = runtime.spawnShared(() -> (message, context) -> {
                try {
                    publisherJoin.join();
                    // Publication authority comes from group membership. This actor
                    // intentionally never calls subscribe().
                    ActorEventBus.PublishReceipt receipt = completed.publish(message);
                    assertTrue(receipt.completion().isDone(),
                            "empty-capacity reliable publish should admit without blocking");
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    published.countDown();
                }
            });

            subscriber.send("subscribe");
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));

            publisher.send("p-1");
            assertTrue(published.await(5, TimeUnit.SECONDS));
            assertEquals(1, group.events().topics().getFirst().subscribers(),
                    "publisher membership must not create an implicit subscription");

            subscriber.send("read");
            assertTrue(read.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals("p-1", observed.get());

            publisher.stop();
            subscriber.stop();
            assertTrue(publisher.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(subscriber.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closingOneSubscriptionDoesNotAffectAnotherGroup() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup a = runtime.createActorGroup();
            ActorRuntime.ActorGroup b = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability joinA = a.joinCapability();
            ActorRuntime.ActorGroupJoinCapability joinB = b.joinCapability();
            ActorEventBus.Topic<Integer> topicA =
                    a.events().topic("updates", ActorEventBus.DeliveryPolicy.LATEST, 1);
            ActorEventBus.Topic<Integer> topicB =
                    b.events().topic("updates", ActorEventBus.DeliveryPolicy.LATEST, 1);

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                try {
                    joinA.join();
                    joinB.join();

                    ActorEventBus.Subscription<Integer> subA = topicA.subscribe();
                    ActorEventBus.Subscription<Integer> subB = topicB.subscribe();
                    subA.close();

                    assertTrue(subA.closed());
                    assertFalse(subB.closed());
                    assertTrue(b.contains(context.self()));

                    topicB.publish(7);
                    assertEquals(7, subB.tryRead().orElseThrow().value());

                    a.leaveCurrent();
                    assertTrue(b.contains(context.self()));
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    done.countDown();
                }
            });

            actor.send("run");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(0, a.memberCount());
            assertEquals(1, b.memberCount());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, b.memberCount());
        }
    }
}
