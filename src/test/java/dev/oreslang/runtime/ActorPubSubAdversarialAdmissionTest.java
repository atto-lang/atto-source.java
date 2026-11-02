package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adversarial admission/lifetime checks for the in-memory, actor-owned pub/sub path. */
@Timeout(20)
final class ActorPubSubAdversarialAdmissionTest {
    @Test
    void reliableRejectionRollsBackEverySubscriberReservation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus.Topic<String> topic = group.events().topic(
                    "atomic", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            CountDownLatch aReady = new CountDownLatch(1);
            CountDownLatch bReady = new CountDownLatch(1);
            CountDownLatch bChecked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.ActorRef<String> a = runtime.spawnShared(() -> (message, context) -> {
                try {
                    if ("subscribe".equals(message)) {
                        join.join();
                        topic.subscribe();
                        aReady.countDown();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    aReady.countDown();
                }
            });
            ActorRuntime.ActorRef<String> b = runtime.spawnShared(() -> (message, context) -> {
                try {
                    if ("subscribe".equals(message)) {
                        join.join();
                        topic.subscribe();
                        bReady.countDown();
                    } else if ("check".equals(message)) {
                        assertTrue(topic.subscribe().tryRead().isEmpty(),
                                "rejected RELIABLE fanout must deliver to no subscribers");
                        bChecked.countDown();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    bReady.countDown();
                    bChecked.countDown();
                }
            });

            a.send("subscribe");
            assertTrue(aReady.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(group.events().publishSystem("atomic", "first").completion().isDone());
            ActorEventBus.PublishReceipt waiting =
                    group.events().publishSystem("atomic", "second");
            assertFalse(waiting.completion().isDone());

            b.send("subscribe");
            assertTrue(bReady.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertThrows(ActorEventBus.EventBackpressureException.class,
                    () -> group.events().publishSystem("atomic", "rejected"));

            b.send("check");
            assertTrue(bChecked.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());

            assertTrue(waiting.completion().cancel(false));
            a.stop();
            b.stop();
            assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "failed admission, cancellation, and retirement must release frozen events");
        }
    }

    @Test
    void latestCoalescesWhileLossyDropsAtOneSlot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus.Topic<String> latest = group.events().topic(
                    "latest", ActorEventBus.DeliveryPolicy.LATEST, 1);
            ActorEventBus.Topic<String> lossy = group.events().topic(
                    "lossy", ActorEventBus.DeliveryPolicy.LOSSY, 1);
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch drained = new CountDownLatch(1);
            AtomicReference<List<String>> values = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                try {
                    if ("subscribe".equals(message)) {
                        join.join();
                        latest.subscribe();
                        lossy.subscribe();
                        ready.countDown();
                    } else {
                        values.set(List.of(
                                latest.subscribe().tryRead().orElseThrow().value(),
                                lossy.subscribe().tryRead().orElseThrow().value()));
                        drained.countDown();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    ready.countDown();
                    drained.countDown();
                }
            });
            actor.send("subscribe");
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());

            assertEquals(0, group.events().publishSystem("latest", "one").coalesced());
            assertEquals(1, group.events().publishSystem("latest", "two").coalesced());
            assertEquals(1, group.events().publishSystem("latest", "three").coalesced());
            assertEquals(0, group.events().publishSystem("lossy", "one").dropped());
            assertEquals(1, group.events().publishSystem("lossy", "two").dropped());
            assertEquals(1, group.events().publishSystem("lossy", "three").dropped());

            actor.send("drain");
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(List.of("three", "one"), values.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void duplicateSubscriptionIsIdempotentButRejoinGetsFreshSubscription()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus.Topic<String> topic = group.events().topic(
                    "membership", ActorEventBus.DeliveryPolicy.LATEST, 1);
            CountDownLatch firstReady = new CountDownLatch(1);
            CountDownLatch secondReady = new CountDownLatch(1);
            CountDownLatch read = new CountDownLatch(1);
            AtomicReference<ActorEventBus.Subscription<String>> first = new AtomicReference<>();
            AtomicReference<ActorEventBus.Subscription<String>> second = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                try {
                    if ("initial".equals(message)) {
                        join.join();
                        first.set(topic.subscribe());
                        assertSame(first.get(), topic.subscribe(),
                                "same actor/topic pair must not duplicate a delivery edge");
                        firstReady.countDown();
                    } else if ("rejoin".equals(message)) {
                        join.join();
                        second.set(topic.subscribe());
                        assertNotSame(first.get(), second.get(),
                                "rejoined membership must not reuse a closed subscription");
                        assertTrue(first.get().closed());
                        secondReady.countDown();
                    } else {
                        assertEquals("new", second.get().tryRead().orElseThrow().value());
                        read.countDown();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    firstReady.countDown();
                    secondReady.countDown();
                    read.countDown();
                }
            });
            actor.send("initial");
            assertTrue(firstReady.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            group.leave(actor);
            assertTrue(first.get().closed(), "leave must synchronously revoke old delivery edge");
            actor.send("rejoin");
            assertTrue(secondReady.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(1, group.events().topics().getFirst().subscribers());
            group.events().publishSystem("membership", "new");
            actor.send("read");
            assertTrue(read.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void reliablePublishDoesNotBlockThePublishingActorTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus.Topic<String> topic = group.events().topic(
                    "turn", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch pending = new CountDownLatch(1);
            CountDownLatch handledNextTurn = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorEventBus.PublishReceipt> waiting = new AtomicReference<>();

            ActorRuntime.ActorRef<String> subscriber =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            join.join();
                            topic.subscribe();
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            ready.countDown();
                        }
                    });
            ActorRuntime.ActorRef<String> publisher =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            if ("publish".equals(message)) {
                                join.join();
                                assertTrue(topic.publish("first").completion().isDone());
                                waiting.set(topic.publish("second"));
                                assertFalse(waiting.get().completion().isDone());
                                pending.countDown();
                            } else {
                                handledNextTurn.countDown();
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                            pending.countDown();
                            handledNextTurn.countDown();
                        }
                    });

            subscriber.send("subscribe");
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            publisher.send("publish");
            assertTrue(pending.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            publisher.send("next-turn");
            assertTrue(handledNextTurn.await(5, TimeUnit.SECONDS),
                    "pending channel admission must not park a carrier or actor turn");
            assertNull(failure.get());
            assertTrue(waiting.get().completion().cancel(false));
            publisher.stop();
            subscriber.stop();
            assertTrue(publisher.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(subscriber.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void groupCloseRacingWithPublishRevokesSubscriptionsButNotActorLifetime()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.events().topic("race", ActorEventBus.DeliveryPolicy.LATEST, 1);
            CountDownLatch ready = new CountDownLatch(1);
            AtomicReference<Throwable> actorFailure = new AtomicReference<>();
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            join.join();
                            group.events().<String>topic("race").subscribe();
                        } catch (Throwable problem) {
                            actorFailure.compareAndSet(null, problem);
                        } finally {
                            ready.countDown();
                        }
                    });
            actor.send("subscribe");
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertNull(actorFailure.get());

            CountDownLatch started = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<?> publishing = executor.submit(() -> {
                    started.countDown();
                    for (int i = 0; i < 100; i++) {
                        try {
                            group.events().publishSystem("race", "value-" + i);
                        } catch (IllegalStateException closed) {
                            return;
                        }
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                group.close();
                publishing.get(5, TimeUnit.SECONDS);
            }
            assertFalse(group.contains(actor));
            assertTrue(actor.isAlive(), "group closure must not stop unrelated actor lifetime");
            assertThrows(IllegalStateException.class,
                    () -> group.events().publishSystem("race", "late"));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "group closure must drain all outstanding event leases");
        }
    }

    @Test
    void actorTerminationRemovesOwnedSubscriptionsAndBufferedEvents()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.events().topic("death", ActorEventBus.DeliveryPolicy.RELIABLE, 2);
            CountDownLatch ready = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            join.join();
                            group.events().<String>topic("death").subscribe();
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            ready.countDown();
                        }
                    });
            actor.send("subscribe");
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(1, group.events().topics().getFirst().subscribers());
            assertTrue(group.events().publishSystem("death", "buffered").completion().isDone());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, group.events().topics().getFirst().subscribers());
            assertEquals(0, group.events().publishSystem("death", "after-death").subscribers());
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }
}
