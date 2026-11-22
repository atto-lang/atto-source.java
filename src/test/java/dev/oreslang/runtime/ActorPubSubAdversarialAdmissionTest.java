package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
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
    void dynamicCallableAndNestedCallablePayloadsAreRejectedBeforeFanout() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            // main requires capacity 1 for LATEST topics.
            group.events().topic("data-only", ActorEventBus.DeliveryPolicy.LATEST, 1);
            Runnable dynamicCallable = () -> { };
            assertThrows(IllegalArgumentException.class,
                    () -> group.events().publishSystem("data-only", dynamicCallable));
            assertThrows(IllegalArgumentException.class,
                    () -> group.events().publishSystem("data-only",
                            List.of("ordinary-data", dynamicCallable)));
            assertThrows(IllegalArgumentException.class,
                    () -> group.events().publishSystem("data-only",
                            new Object[] { 42, dynamicCallable }));
        }
    }

    @Test
    void zeroRecipientPublicationsAllocateSequenceButDoNotReplay() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            group.events().topic("empty", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            ActorEventBus.PublishReceipt first =
                    group.events().publishSystem("empty", "first");
            ActorEventBus.PublishReceipt second =
                    group.events().publishSystem("empty", "second");
            ActorEventBus.PublishReceipt third = group.events()
                    .tryPublishReliableSystem("empty", "third").orElseThrow();
            assertEquals(0, first.accepted());
            assertEquals(0, second.accepted());
            assertEquals(1L, first.sequence());
            assertEquals(2L, second.sequence());
            assertEquals(3L, third.sequence());
            assertTrue(third.completion().isDone(),
                    "no-recipient try admission returns an already completed receipt");
            assertEquals("1", first.sequenceUnsignedString());
            assertEquals("2", second.sequenceUnsignedString());
        }
    }

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
            CountDownLatch acceptedRead = new CountDownLatch(1);
            AtomicReference<Long> acceptedSequence = new AtomicReference<>();
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
                    } else if ("readAccepted".equals(message)) {
                        ActorEventBus.Event<String> accepted =
                                topic.subscribe().tryRead().orElseThrow();
                        assertEquals("accepted", accepted.value());
                        acceptedSequence.set(accepted.sequence());
                        acceptedRead.countDown();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    bReady.countDown();
                    bChecked.countDown();
                    acceptedRead.countDown();
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
            assertTrue(group.events().tryPublishReliableSystem("atomic", "also-rejected").isEmpty(),
                    "Full must return an explicit, nonblocking empty result");

            b.send("check");
            assertTrue(bChecked.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());

            assertTrue(waiting.completion().cancel(false));
            a.stop();
            assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
            // No subscriber receives the rejected publication, and its
            // attempt must not burn a topic-local sequence number.
            ActorEventBus.PublishReceipt acceptedReceipt = group.events()
                    .tryPublishReliableSystem("atomic", "accepted").orElseThrow();
            assertTrue(acceptedReceipt.completion().isDone());
            assertEquals(3L, acceptedReceipt.sequence(),
                    "admission receipt must report contiguous sequence after rejection");
            assertEquals("3", acceptedReceipt.sequenceUnsignedString());
            b.send("readAccepted");
            assertTrue(acceptedRead.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(3L, acceptedSequence.get().longValue(),
                    "rejected RELIABLE fanout must not consume sequence 3");
            b.stop();
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "failed admission, cancellation, and retirement must release frozen events");
        }
    }

    @Test
    void tryReliableRejectsExtensionPoliciesAndInvalidPayloadWithoutCreatingReceipts() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            group.events().topic("latest-only", ActorEventBus.DeliveryPolicy.LATEST, 1);
            group.events().topic("reliable-only", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            assertThrows(IllegalArgumentException.class,
                    () -> group.events().tryPublishReliableSystem("latest-only", "never"));
            assertThrows(IllegalArgumentException.class,
                    () -> group.events().tryPublishReliableSystem(
                            "reliable-only", (Runnable) () -> { }));
            ActorEventBus.PublishReceipt success = group.events()
                    .tryPublishReliableSystem("reliable-only", "accepted").orElseThrow();
            assertEquals(1L, success.sequence(),
                    "invalid payload and extension-policy attempts must not consume a sequence");
        }
    }

    @Test
    void typedTryReliableRefusesMissingActorAuthorityAndAdmitsAfterJoin() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability grant = group.joinCapability();
            ActorEventBus.Topic<String> topic = group.events().topic(
                    "try-authority", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            CountDownLatch denied = new CountDownLatch(1);
            CountDownLatch admitted = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<Long> sequence = new AtomicReference<>();
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                try {
                    if ("denied".equals(message)) {
                        assertThrows(SecurityException.class,
                                () -> topic.tryPublishReliable("not-authorized"));
                        denied.countDown();
                    } else if ("admit".equals(message)) {
                        grant.join();
                        sequence.set(topic.tryPublishReliable("authorized")
                                .orElseThrow().sequence());
                        admitted.countDown();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    denied.countDown();
                    admitted.countDown();
                }
            });
            actor.send("denied");
            assertTrue(denied.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            actor.send("admit");
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(1L, sequence.get().longValue(),
                    "unauthorized try admission must not create a sequence");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
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
    @Test
    void repeatedLeaveAndRejoinNeverReplaysPreviousMembershipDeliveries()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus.Topic<String> topic = group.events().topic(
                    "epochs", ActorEventBus.DeliveryPolicy.LATEST, 1);
            LinkedBlockingQueue<ActorEventBus.Subscription<String>> admissions =
                    new LinkedBlockingQueue<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            join.join();
                            ActorEventBus.Subscription<String> current = topic.subscribe();
                            assertTrue(current.tryRead().isEmpty(),
                                    "new membership observed delivery from a retired membership");
                            admissions.add(current);
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        }
                    });

            for (int epoch = 0; epoch < 24; epoch++) {
                actor.send("join");
                ActorEventBus.Subscription<String> current =
                        admissions.poll(5, TimeUnit.SECONDS);
                assertNull(failure.get());
                assertTrue(current != null, "actor did not complete rejoin " + epoch);
                assertFalse(current.closed());
                assertEquals(1, group.events().topics().getFirst().subscribers());

                // Do not drain this event. The membership transition must
                // retire it, not transplant it into the next delivery edge.
                group.events().publishSystem("epochs", "old-" + epoch);
                group.leave(actor);
                assertTrue(current.closed());
                assertEquals(0, group.events().topics().getFirst().subscribers());
            }
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "repeated revocation/rejoin must not retain abandoned payloads");
        }
    }

    @Test
    void subscriptionQuotaBoundsChannelFanoutAndCloseReturnsCredit()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            List<ActorEventBus.Topic<String>> topics = new ArrayList<>();
            for (int i = 0; i <= 64; i++) {
                topics.add(group.events().topic(
                        "quota-" + i, ActorEventBus.DeliveryPolicy.RELIABLE, 1));
            }
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            join.join();
                            int limit = Math.min(64, Math.max(1,
                                    ActorRuntime.currentActorPolicy().maxMailboxMessages()));
                            List<ActorEventBus.Subscription<String>> subscriptions =
                                    new ArrayList<>();
                            for (int i = 0; i < limit; i++) {
                                subscriptions.add(topics.get(i).subscribe());
                            }
                            assertSame(subscriptions.get(0), topics.get(0).subscribe(),
                                    "duplicate subscribe must not consume quota");
                            IllegalStateException exhausted = assertThrows(
                                    IllegalStateException.class,
                                    () -> topics.get(limit).subscribe());
                            assertTrue(exhausted.getMessage().contains(
                                    "subscription limit exceeded"));
                            subscriptions.get(0).close();
                            assertDoesNotThrow(() -> topics.get(limit).subscribe(),
                                    "closing an edge must return one subscription credit");
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            done.countDown();
                        }
                    });
            actor.send("test");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void unsubscribeWhileHostPublishesIsAnAdmissionBarrier() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus.Topic<String> topic = group.events().topic(
                    "unsubscribe-race", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch unsubscribed = new CountDownLatch(1);
            AtomicReference<ActorEventBus.Subscription<String>> edge =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            if ("subscribe".equals(message)) {
                                join.join();
                                edge.set(topic.subscribe());
                                subscribed.countDown();
                            } else {
                                edge.get().close();
                                unsubscribed.countDown();
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                            subscribed.countDown();
                            unsubscribed.countDown();
                        }
                    });
            actor.send("subscribe");
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());

            CountDownLatch started = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<?> publishing = executor.submit(() -> {
                    started.countDown();
                    for (int i = 0; i < 200; i++) {
                        try {
                            group.events().publishSystem("unsubscribe-race", "p-" + i);
                        } catch (ActorEventBus.EventBackpressureException exhausted) {
                            // A synchronous bounded-credit denial is permitted;
                            // no carrier may wait for capacity on this path.
                        }
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                actor.send("unsubscribe");
                assertTrue(unsubscribed.await(5, TimeUnit.SECONDS),
                        "actor-side unsubscribe must not wait for a host publisher");
                publishing.get(5, TimeUnit.SECONDS);
            }
            assertNull(failure.get());
            assertTrue(edge.get().closed());
            assertEquals(0, group.events().topics().getFirst().subscribers(),
                    "no publication may retain an unsubscribed delivery edge");
            assertEquals(0, group.events().publishSystem(
                    "unsubscribe-race", "post-barrier").subscribers());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void replacementActorCannotReadRetiredGenerationEvent() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus.Topic<String> topic = group.events().topic(
                    "replacement", ActorEventBus.DeliveryPolicy.LATEST, 1);
            CountDownLatch oldReady = new CountDownLatch(1);
            AtomicReference<ActorEventBus.Subscription<String>> oldEdge =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<String> oldActor =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            join.join();
                            oldEdge.set(topic.subscribe());
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            oldReady.countDown();
                        }
                    });
            oldActor.send("subscribe");
            assertTrue(oldReady.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            group.events().publishSystem("replacement", "old-generation");
            oldActor.stop();
            assertTrue(oldActor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(oldEdge.get().closed(),
                    "actor death must revoke the physical subscription");

            CountDownLatch newReady = new CountDownLatch(1);
            CountDownLatch read = new CountDownLatch(1);
            AtomicReference<String> observed = new AtomicReference<>();
            ActorRuntime.ActorRef<String> replacement =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            if ("subscribe".equals(message)) {
                                join.join();
                                ActorEventBus.Subscription<String> current = topic.subscribe();
                                assertNotSame(oldEdge.get(), current);
                                assertTrue(current.tryRead().isEmpty(),
                                        "replacement must not inherit old-generation data");
                                newReady.countDown();
                            } else {
                                observed.set(topic.subscribe().tryRead().orElseThrow().value());
                                read.countDown();
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                            newReady.countDown();
                            read.countDown();
                        }
                    });
            replacement.send("subscribe");
            assertTrue(newReady.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            group.events().publishSystem("replacement", "new-generation");
            replacement.send("read");
            assertTrue(read.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals("new-generation", observed.get());
            replacement.stop();
            assertTrue(replacement.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

}
