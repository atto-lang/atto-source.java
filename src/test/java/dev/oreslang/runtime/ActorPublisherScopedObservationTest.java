package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Host/runtime transport slice for ObservableActor. These tests deliberately do
 * not claim that source-level trait parsing or observation grants exist yet.
 */
final class ActorPublisherScopedObservationTest {

    @Test
    void publisherScopedSubscriptionsNeverReceiveOtherPublisherOrSystemEvents()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus bus = group.events();
            bus.defineTopic("counts", ActorEventBus.DeliveryPolicy.LATEST, 1);

            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch joined = new CountDownLatch(2);
            CountDownLatch published = new CountDownLatch(2);
            // Producers run on separate carriers: order A's publish before B's so the
            // LATEST(capacity 1) wildcard deterministically retains B's value.
            CountDownLatch firstPublished = new CountDownLatch(1);
            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch read = new CountDownLatch(1);
            CountDownLatch left = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> producerA = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            switch (message) {
                                case "join" -> { join.join(); joined.countDown(); }
                                case "emit" -> {
                                    assertEquals(2, bus.publish("counts", 11).accepted());
                                    published.countDown();
                                    firstPublished.countDown();
                                }
                                case "leave" -> { group.leaveCurrent(); left.countDown(); }
                                default -> throw new AssertionError(message);
                            }
                        } catch (Throwable failure) {
                            error.compareAndSet(null, failure);
                            joined.countDown();
                            published.countDown();
                            left.countDown();
                            firstPublished.countDown();
                        }
                    });
            ActorRuntime.ActorRef<String> producerB = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            switch (message) {
                                case "join" -> { join.join(); joined.countDown(); }
                                case "emit" -> {
                                    assertEquals(2, bus.publish("counts", 22).accepted());
                                    published.countDown();
                                }
                                default -> throw new AssertionError(message);
                            }
                        } catch (Throwable failure) {
                            error.compareAndSet(null, failure);
                            joined.countDown();
                            published.countDown();
                        }
                    });

            AtomicReference<ActorEventBus.Subscription<Integer>> aSubscription = new AtomicReference<>();
            AtomicReference<ActorEventBus.Subscription<Integer>> bSubscription = new AtomicReference<>();
            AtomicReference<ActorEventBus.Subscription<Integer>> wildcardSubscription = new AtomicReference<>();
            ActorRuntime.ActorRef<String> observer = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            switch (message) {
                                case "subscribe" -> {
                                    join.join();
                                    aSubscription.set(bus.subscribePublisher("counts", producerA));
                                    bSubscription.set(bus.subscribePublisher("counts", producerB));
                                    wildcardSubscription.set(bus.subscribe("counts"));
                                    assertEquals(producerA.address(),
                                            aSubscription.get().publisherScope().orElseThrow());
                                    assertEquals(producerB.address(),
                                            bSubscription.get().publisherScope().orElseThrow());
                                    assertTrue(wildcardSubscription.get().publisherScope().isEmpty());
                                    subscribed.countDown();
                                }
                                case "read" -> {
                                    assertEquals(11, aSubscription.get().tryRead().orElseThrow().value());
                                    assertEquals(22, bSubscription.get().tryRead().orElseThrow().value());
                                    assertEquals(22, wildcardSubscription.get().tryRead().orElseThrow().value());
                                    assertEquals(0, aSubscription.get().pending());
                                    assertEquals(0, bSubscription.get().pending());
                                    read.countDown();
                                }
                                default -> throw new AssertionError(message);
                            }
                        } catch (Throwable failure) {
                            error.compareAndSet(null, failure);
                            subscribed.countDown();
                            read.countDown();
                        }
                    });

            producerA.send("join");
            producerB.send("join");
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            assertNull(error.get());

            observer.send("subscribe");
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            assertEquals(3, bus.topics().getFirst().subscribers());

            // A system event never reaches any publisher-scoped subscription.
            assertEquals(1, bus.publishSystem("counts", 777).accepted());
            producerA.send("emit");
            assertTrue(firstPublished.await(5, TimeUnit.SECONDS));
            producerB.send("emit");
            assertTrue(published.await(5, TimeUnit.SECONDS));
            assertNull(error.get());

            observer.send("read");
            assertTrue(read.await(5, TimeUnit.SECONDS));
            assertNull(error.get());

            // Publisher departure invalidates just its scoped subscription.
            producerA.send("leave");
            assertTrue(left.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            assertTrue(aSubscription.get().closed());
            assertFalse(bSubscription.get().closed());
            assertFalse(wildcardSubscription.get().closed());
            assertEquals(2, bus.topics().getFirst().subscribers());
        }
    }

    @Test
    void scopedReliableObserverQuotasIgnoreOtherPublisherAndRejectNonmember()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus bus = group.events();
            bus.defineTopic("critical", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch joined = new CountDownLatch(2);
            CountDownLatch observed = new CountDownLatch(1);
            CountDownLatch published = new CountDownLatch(2);
            AtomicReference<ActorEventBus.PublishReceipt> secondReceipt = new AtomicReference<>();
            AtomicReference<ActorEventBus.Subscription<Integer>> scoped = new AtomicReference<>();

            ActorRuntime.ActorRef<String> producerA = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            if ("join".equals(message)) {
                                join.join(); joined.countDown();
                            } else {
                                bus.publish("critical", 101);
                                published.countDown();
                            }
                        } catch (Throwable failure) {
                            error.compareAndSet(null, failure);
                            joined.countDown();
                            published.countDown();
                        }
                    });
            ActorRuntime.ActorRef<String> producerB = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            if ("join".equals(message)) {
                                join.join(); joined.countDown();
                            } else {
                                secondReceipt.set(bus.publish("critical", 202));
                                published.countDown();
                            }
                        } catch (Throwable failure) {
                            error.compareAndSet(null, failure);
                            joined.countDown();
                            published.countDown();
                        }
                    });
            ActorRuntime.ActorRef<String> foreignActor = foreign.spawnShared(
                    () -> (message, context) -> { });
            ActorRuntime.ActorRef<String> notJoined = runtime.spawnShared(
                    () -> (message, context) -> { });

            // ActorId alone cannot reauthorize a stale incarnation.
            ActorRuntime.ActorAddress address = producerA.address();
            assertTrue(runtime.isLiveActorAddress(address));
            assertFalse(runtime.isLiveActorAddress(new ActorRuntime.ActorAddress(
                    address.runtimeId(), address.actorId(), address.generation() + 1,
                    address.kind(), address.transport())));
            assertFalse(runtime.isLiveActorAddress(foreignActor.address()));

            ActorRuntime.ActorRef<String> observer = runtime.spawnShared(
                    () -> (message, context) -> {
                        try {
                            join.join();
                            assertThrows(SecurityException.class,
                                    () -> bus.subscribePublisher("critical", foreignActor));
                            assertThrows(SecurityException.class,
                                    () -> bus.subscribePublisher("critical", notJoined));
                            scoped.set(bus.subscribePublisher("critical", producerA));
                        } catch (Throwable failure) {
                            error.compareAndSet(null, failure);
                        } finally {
                            observed.countDown();
                        }
                    });

            producerA.send("join"); producerB.send("join");
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            observer.send("subscribe");
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            assertNull(error.get());

            producerA.send("emit");
            producerB.send("emit");
            assertTrue(published.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            assertEquals(0, secondReceipt.get().subscribers());
            assertEquals(0, secondReceipt.get().accepted());
            assertEquals(1, scoped.get().pending());

            // Removing the producer releases its unread retained delivery
            // without removing or mutating the other producer's state.
            producerA.stop();
            assertTrue(producerA.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(runtime.isLiveActorAddress(address));
            assertTrue(scoped.get().closed());
            assertEquals(0, scoped.get().pending(),
                    "publisher STOP must synchronously drain its scoped subscription");

            // Other actors are still live here. Their independently owned
            // USER mailbox envelopes may account for shared memory even
            // after this publisher's retained delivery has been refunded.
            // Join every physical retirement before asserting zero bytes;
            // never turn a still-live actor's mail into a false leak report.
            observer.stop();
            producerB.stop();
            notJoined.stop();
            assertTrue(observer.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(producerB.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(notJoined.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "all publisher-scoped and independent actor mail must retire");
        }
    }
    @Test
    void scopedSubscriptionsHaveFinitePerObserverQuotaAndRefundOnClose()
            throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy bounded = new IsolatePolicy(
                base.capabilities(), base.maxHeapBytes(), 1, base.maxWallTime());
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus bus = group.events();
            bus.defineTopic("quota", ActorEventBus.DeliveryPolicy.LATEST, 1);
            CountDownLatch publishersJoined = new CountDownLatch(2);
            ActorRuntime.ActorRef<Integer> a = runtime.spawnShared(
                    () -> (message, context) -> {
                        join.join();
                        publishersJoined.countDown();
                    });
            ActorRuntime.ActorRef<Integer> b = runtime.spawnShared(
                    () -> (message, context) -> {
                        join.join();
                        publishersJoined.countDown();
                    });
            a.send(1);
            b.send(1);
            assertTrue(publishersJoined.await(5, TimeUnit.SECONDS));

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> observer = runtime.spawnShared(
                    bounded, () -> (message, context) -> {
                        try {
                            join.join();
                            ActorEventBus.Subscription<Integer> first =
                                    bus.subscribePublisher("quota", a);
                            assertTrue(first == bus.<Integer>subscribePublisher("quota", a),
                                    "idempotent subscribe must not consume another lease");
                            assertThrows(IllegalStateException.class,
                                    () -> bus.subscribePublisher("quota", b),
                                    "mailbox policy with one credit bounds publisher leases");
                            assertEquals(1, bus.topics().getFirst().subscribers());
                            first.close();
                            ActorEventBus.Subscription<Integer> second =
                                    bus.subscribePublisher("quota", b);
                            assertFalse(second.closed());
                            group.leaveCurrent();
                            assertTrue(second.closed());
                            assertEquals(0, bus.topics().getFirst().subscribers());
                        } catch (Throwable failure) {
                            error.set(failure);
                        } finally {
                            done.countDown();
                        }
                    });
            observer.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            // done fires inside the actor callback before its envelope
            // reservation has necessarily been released. Join processed-mail
            // accounting before asserting physical shared-memory reclamation.
            runtime.drain(observer).get(5, TimeUnit.SECONDS);

            // Subscription quota credits are refunded inside the receive
            // callback; other independently live actor mailbox envelopes
            // are not leaked subscription state. Require physical retirement
            // of all three actors before measuring total runtime bytes.
            observer.stop();
            a.stop();
            b.stop();
            assertTrue(observer.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "all publisher-scoped subscriptions and actor mail must retire");
        }
    }

}
