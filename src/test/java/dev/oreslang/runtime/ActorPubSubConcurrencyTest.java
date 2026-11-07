package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorPubSubConcurrencyTest {
    @Test
    void cancellingReliablePublicationRevokesPendingAdmissionAndReleasesQuota()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus.Topic<String> topic = group.events().topic(
                    "reliable-cancel", ActorEventBus.DeliveryPolicy.RELIABLE, 1);

            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch closed = new CountDownLatch(1);
            AtomicReference<ActorEventBus.Subscription<String>> subscription =
                    new AtomicReference<>();
            AtomicReference<Throwable> actorFailure = new AtomicReference<>();

            ActorRuntime.ActorRef<String> subscriber =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            if ("subscribe".equals(message)) {
                                join.join();
                                subscription.set(topic.subscribe());
                                subscribed.countDown();
                                return;
                            }
                            if ("close".equals(message)) {
                                subscription.get().close();
                                context.self().stop();
                                closed.countDown();
                            }
                        } catch (Throwable failure) {
                            actorFailure.compareAndSet(null, failure);
                        }
                    });

            subscriber.send("subscribe");
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));

            ActorEventBus.PublishReceipt first =
                    group.events().publishSystem("reliable-cancel", "one");
            assertTrue(first.completion().isDone());

            ActorEventBus.PublishReceipt second =
                    group.events().publishSystem("reliable-cancel", "two");
            assertFalse(second.completion().isDone(),
                    "capacity-one subscriber should leave the second write pending");

            assertTrue(second.completion().cancel(false),
                    "publication cancellation must win while admission is pending");
            assertTrue(second.completion().isCancelled());

            // reliableOutstandingLimit is two for a capacity-one subscription.
            // If cancellation leaked the second reservation, this third publish
            // would synchronously throw EventBackpressureException.
            ActorEventBus.PublishReceipt third = assertDoesNotThrow(
                    () -> group.events().publishSystem(
                            "reliable-cancel", "three"));
            assertFalse(third.completion().isDone(),
                    "third delivery should wait behind the still-buffered first event");

            subscriber.send("close");
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertTrue(subscriber.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(actorFailure.get());
            assertTrue(third.completion().isCompletedExceptionally(),
                    "closing the subscription must fail its remaining pending admission");
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "cancel/close must release every frozen event reservation");
        }
    }

    @Test
    void leaveReturnIsPublicationBarrierForThatActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus.Topic<String> topic = group.events().topic(
                    "leave-fence", ActorEventBus.DeliveryPolicy.RELIABLE, 8);

            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch joinedPublisher = new CountDownLatch(1);
            CountDownLatch publishedBefore = new CountDownLatch(1);
            CountDownLatch attemptedAfter = new CountDownLatch(1);
            CountDownLatch drained = new CountDownLatch(1);
            AtomicReference<ActorEventBus.Subscription<String>> subscription =
                    new AtomicReference<>();
            AtomicReference<Throwable> afterFailure = new AtomicReference<>();
            AtomicReference<List<String>> observed = new AtomicReference<>();

            ActorRuntime.ActorRef<String> subscriber =
                    runtime.spawnShared(() -> (message, context) -> {
                        if ("subscribe".equals(message)) {
                            join.join();
                            subscription.set(topic.subscribe());
                            subscribed.countDown();
                            return;
                        }
                        if ("drain".equals(message)) {
                            ArrayList<String> values = new ArrayList<>();
                            subscription.get().tryRead()
                                    .ifPresent(event -> values.add(event.value()));
                            subscription.get().tryRead()
                                    .ifPresent(event -> values.add(event.value()));
                            observed.set(List.copyOf(values));
                            drained.countDown();
                            context.self().stop();
                        }
                    });

            ActorRuntime.ActorRef<String> publisher =
                    runtime.spawnShared(() -> (message, context) -> {
                        if ("join".equals(message)) {
                            join.join();
                            joinedPublisher.countDown();
                            return;
                        }
                        if ("before".equals(message)) {
                            topic.publish("before");
                            publishedBefore.countDown();
                            return;
                        }
                        try {
                            topic.publish("after");
                        } catch (Throwable failure) {
                            afterFailure.set(failure);
                        } finally {
                            attemptedAfter.countDown();
                            context.self().stop();
                        }
                    });

            subscriber.send("subscribe");
            publisher.send("join");
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            assertTrue(joinedPublisher.await(5, TimeUnit.SECONDS));

            publisher.send("before");
            assertTrue(publishedBefore.await(5, TimeUnit.SECONDS));

            group.leave(publisher);
            assertFalse(group.contains(publisher),
                    "leave() must revoke membership before it returns");

            publisher.send("after");
            assertTrue(attemptedAfter.await(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, afterFailure.get());

            subscriber.send("drain");
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("before"), observed.get(),
                    "no publication initiated after leave() returns may commit");

            assertTrue(publisher.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(subscriber.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentPublishersPreservePerPublisherFifo() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus.Topic<String> topic = group.events().topic(
                    "fifo", ActorEventBus.DeliveryPolicy.RELIABLE, 256);

            int perPublisher = 64;
            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch publishersJoined = new CountDownLatch(2);
            CountDownLatch published = new CountDownLatch(perPublisher * 2);
            CountDownLatch drained = new CountDownLatch(1);
            AtomicReference<ActorEventBus.Subscription<String>> subscription =
                    new AtomicReference<>();
            AtomicReference<List<String>> observed = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.ActorRef<String> subscriber =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            if ("subscribe".equals(message)) {
                                join.join();
                                subscription.set(topic.subscribe());
                                subscribed.countDown();
                                return;
                            }
                            ArrayList<String> values = new ArrayList<>();
                            for (int i = 0; i < perPublisher * 2; i++) {
                                values.add(subscription.get()
                                        .tryRead().orElseThrow().value());
                            }
                            observed.set(List.copyOf(values));
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            if ("drain".equals(message)) {
                                drained.countDown();
                                context.self().stop();
                            }
                        }
                    });

            ActorRuntime.ActorRef<String> publisherA =
                    publisher(runtime, join, topic, "A", publishersJoined, published, failure);
            ActorRuntime.ActorRef<String> publisherB =
                    publisher(runtime, join, topic, "B", publishersJoined, published, failure);

            subscriber.send("subscribe");
            publisherA.send("join");
            publisherB.send("join");
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            assertTrue(publishersJoined.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < perPublisher; i++) {
                publisherA.send(Integer.toString(i));
                publisherB.send(Integer.toString(i));
            }
            assertTrue(published.await(10, TimeUnit.SECONDS));
            assertNull(failure.get());

            subscriber.send("drain");
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());

            List<String> values = observed.get();
            assertEquals(perPublisher * 2, values.size());
            assertPublisherOrder(values, "A", perPublisher);
            assertPublisherOrder(values, "B", perPublisher);

            publisherA.stop();
            publisherB.stop();
            assertTrue(publisherA.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(publisherB.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(subscriber.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ActorRuntime.ActorRef<String> publisher(
            ActorRuntime runtime,
            ActorRuntime.ActorGroupJoinCapability join,
            ActorEventBus.Topic<String> topic,
            String prefix,
            CountDownLatch joined,
            CountDownLatch published,
            AtomicReference<Throwable> failure) {
        return runtime.spawnShared(() -> (message, context) -> {
            try {
                if ("join".equals(message)) {
                    join.join();
                    joined.countDown();
                    return;
                }
                ActorEventBus.PublishReceipt receipt =
                        topic.publish(prefix + ":" + message);
                if (!receipt.completion().isDone()) {
                    throw new AssertionError(
                            "capacity should admit FIFO test publication immediately");
                }
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            } finally {
                if (!"join".equals(message)) published.countDown();
            }
        });
    }

    private static void assertPublisherOrder(
            List<String> observed,
            String prefix,
            int count) {
        int next = 0;
        for (String value : observed) {
            if (!value.startsWith(prefix + ":")) continue;
            assertEquals(prefix + ":" + next, value,
                    "per-publisher FIFO order changed");
            next++;
        }
        assertEquals(count, next,
                "missing publications from publisher " + prefix);
    }
}
