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

    @Test
    void deferredChannelCommitCannotBeCancelledThroughMappedFuture() {
        var channel = new ChannelRuntime.Channel<String>(1);
        var read = channel.readAsync();
        var write = ChannelRuntime.withDeferredAsyncCompletions(() -> {
            var committed = channel.writeAsync("committed");
            assertFalse(read.isDone());
            assertFalse(committed.isDone());
            assertFalse(read.cancel(false), "read already owns the committed value");
            assertFalse(committed.cancel(false), "write already committed");
            return committed;
        });
        assertEquals("committed", read.join());
        assertTrue(write.isDone());
        assertFalse(write.isCompletedExceptionally());
        assertTrue(channel.tryRead().isEmpty());

        var pending = channel.readAsync();
        assertTrue(pending.cancel(false), "uncommitted mapped read remains cancellable");
        assertTrue(channel.tryWrite("next"));
        assertEquals("next", channel.tryRead().orElseThrow());
    }

    @Test
    void reliableFanoutNotifiesPendingReadersOutsideMembershipFence() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var join = group.joinCapability();
            ActorEventBus.Topic<String> topic = group.events().topic(
                    "callback-fence", ActorEventBus.DeliveryPolicy.RELIABLE, 2);
            var subscribed = new CountDownLatch(2);
            var leftRead = new AtomicReference<OresFuture<ActorEventBus.Event<String>>>();
            var rightRead = new AtomicReference<OresFuture<ActorEventBus.Event<String>>>();
            var left = runtime.<String>spawnShared(() -> (message, context) -> {
                join.join();
                leftRead.set(topic.subscribe().readAsync());
                subscribed.countDown();
            });
            var right = runtime.<String>spawnShared(() -> (message, context) -> {
                join.join();
                rightRead.set(topic.subscribe().readAsync());
                subscribed.countDown();
            });
            left.send("subscribe");
            right.send("subscribe");
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            var callbackFailure = new AtomicReference<Throwable>();
            var callbackDone = new CountDownLatch(1);
            try {
                leftRead.get().whenCompleteRuntime((value, failure) -> {
                    try {
                        assertNull(failure);
                        assertEquals("payload", value.value());
                        // This other carrier must be able to acquire the
                        // membership fence before the callback returns.
                        worker.submit(() -> group.leave(right)).get(3, TimeUnit.SECONDS);
                    } catch (Throwable problem) {
                        callbackFailure.set(problem);
                    } finally {
                        callbackDone.countDown();
                    }
                });
                var receipt = group.events().tryPublishReliableSystem(
                        "callback-fence", "payload").orElseThrow();
                assertEquals(2, receipt.accepted());
                assertTrue(callbackDone.await(5, TimeUnit.SECONDS));
                assertNull(callbackFailure.get(),
                        "read notification ran under an outer membership lock");
                assertTrue(receipt.completion().isDone());
                assertFalse(receipt.completion().isCompletedExceptionally());
                assertEquals("payload", rightRead.get().join().value(),
                        "other already-admitted recipient must not be lost to reentrant leave");
                assertFalse(group.contains(right));
                assertEquals(0L, runtime.sharedMemoryBytes());
            } finally {
                worker.shutdownNow();
                left.stop();
                right.stop();
            }
        }
    }

    @Test
    void leaveClosesOldEdgeBeforeCallbackCanRejoinAndSubscribe() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var join = group.joinCapability();
            ActorEventBus.Topic<String> topic = group.events().topic(
                    "leave-callback", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            var first = new AtomicReference<ActorEventBus.Subscription<String>>();
            var second = new AtomicReference<ActorEventBus.Subscription<String>>();
            var pending = new AtomicReference<OresFuture<ActorEventBus.Event<String>>>();
            var failure = new AtomicReference<Throwable>();
            var ready = new CountDownLatch(1);
            var rejoined = new CountDownLatch(1);
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    if ("first".equals(message)) {
                        join.join();
                        first.set(topic.subscribe());
                        pending.set(first.get().readAsync());
                        ready.countDown();
                    } else {
                        second.set(topic.subscribe());
                        rejoined.countDown();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                actor.send("first");
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                pending.get().whenCompleteRuntime((value, problem) -> {
                    try {
                        assertInstanceOf(ChannelRuntime.ChannelClosedException.class,
                                OresFuture.unwrap(problem));
                        assertTrue(first.get().closed());
                        executor.submit(() -> group.join(actor, join))
                                .get(3, TimeUnit.SECONDS);
                        actor.send("second");
                        assertTrue(rejoined.await(3, TimeUnit.SECONDS));
                        assertTrue(second.get() != first.get(),
                                "rejoin must receive a fresh edge");
                    } catch (Throwable callbackFailure) {
                        failure.compareAndSet(null, callbackFailure);
                    }
                });
                group.leave(actor);
                assertNull(failure.get());
                assertTrue(group.contains(actor));
                assertTrue(first.get().closed());
                assertFalse(second.get().closed());
                assertEquals(0, second.get().pending());
                var receipt = group.events().tryPublishReliableSystem(
                        "leave-callback", "fresh").orElseThrow();
                assertEquals(1, receipt.accepted());
                assertEquals(1, second.get().pending());
                assertEquals(0, first.get().pending());
                group.leave(actor);
                assertTrue(second.get().closed());
                assertSharedQuotaRetired(runtime);
            } finally {
                executor.shutdownNow();
                actor.stop();
            }
        }
    }

    @Test
    void membershipRejoinCannotResurrectPublicationAlreadyFreezing() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var join = group.joinCapability();
            ActorEventBus.Topic<List<String>> topic = group.events().topic(
                    "freeze-incarnation", ActorEventBus.DeliveryPolicy.RELIABLE, 2);
            var subscribed = new CountDownLatch(1);
            var freezing = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var rejected = new CountDownLatch(1);
            var fresh = new CountDownLatch(1);
            var subscription = new AtomicReference<ActorEventBus.Subscription<List<String>>>();
            var oldFailure = new AtomicReference<Throwable>();
            var freshFailure = new AtomicReference<Throwable>();
            var receipt = new AtomicReference<ActorEventBus.PublishReceipt>();
            var firstTraversal = new java.util.concurrent.atomic.AtomicBoolean(true);
            // Host-only payload fixture: pause precisely after authority capture
            // and before immutable graph admission, not with a timing sleep.
            List<String> delayed = new java.util.AbstractList<>() {
                @Override public String get(int index) {
                    if (index != 0) throw new IndexOutOfBoundsException(index);
                    return "old";
                }
                @Override public int size() {
                    if (firstTraversal.compareAndSet(true, false)) {
                        freezing.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("freeze release timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }
                    return 1;
                }
            };
            var observer = runtime.<String>spawnShared(() -> (message, context) -> {
                join.join();
                subscription.set(topic.subscribe());
                subscribed.countDown();
            });
            var publisher = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("old".equals(message)) {
                    try {
                        join.join();
                        topic.tryPublishReliable(delayed);
                    } catch (Throwable problem) {
                        oldFailure.set(problem);
                    } finally {
                        rejected.countDown();
                    }
                } else {
                    try {
                        receipt.set(topic.tryPublishReliable(List.of("fresh")).orElseThrow());
                    } catch (Throwable problem) {
                        freshFailure.set(problem);
                    } finally {
                        fresh.countDown();
                    }
                }
            });
            try {
                observer.send("subscribe");
                assertTrue(subscribed.await(5, TimeUnit.SECONDS));
                publisher.send("old");
                assertTrue(freezing.await(5, TimeUnit.SECONDS));
                group.leave(publisher);
                group.join(publisher, join);
                release.countDown();
                assertTrue(rejected.await(5, TimeUnit.SECONDS));
                assertInstanceOf(SecurityException.class, oldFailure.get());
                assertEquals(0, subscription.get().pending());
                assertSharedQuotaRetired(runtime);
                publisher.send("fresh");
                assertTrue(fresh.await(5, TimeUnit.SECONDS));
                assertNull(freshFailure.get());
                assertEquals(1L, receipt.get().sequence());
                assertEquals(1, subscription.get().pending());
                group.leave(observer);
                assertSharedQuotaRetired(runtime);
            } finally {
                release.countDown();
                observer.stop();
                publisher.stop();
            }
        }
    }

    @Test
    void scopedSubscriptionCannotCommitAfterPublisherRevocationWinsFence() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var join = group.joinCapability();
            group.events().topic("scope-fence", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            var joined = new CountDownLatch(2);
            var attempting = new CountDownLatch(1);
            var done = new CountDownLatch(1);
            var problem = new AtomicReference<Throwable>();
            var edge = new AtomicReference<ActorEventBus.Subscription<String>>();
            var publisher = runtime.<String>spawnShared(() -> (message, context) -> {
                join.join();
                joined.countDown();
            });
            var observer = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("join".equals(message)) {
                    join.join();
                    joined.countDown();
                    return;
                }
                attempting.countDown();
                try {
                    edge.set(group.events().subscribePublisher("scope-fence", publisher));
                } catch (Throwable failure) {
                    problem.set(failure);
                } finally {
                    done.countDown();
                }
            });
            try {
                publisher.send("join");
                observer.send("join");
                assertTrue(joined.await(5, TimeUnit.SECONDS));
                group.withEventAdmissionMembershipFence(() -> {
                    observer.send("subscribe");
                    try {
                        assertTrue(attempting.await(5, TimeUnit.SECONDS));
                        // The observer must not publish a subscription while
                        // this carrier owns the authoritative membership fence.
                        assertFalse(done.await(100, TimeUnit.MILLISECONDS));
                        group.leave(publisher);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return null;
                });
                assertTrue(done.await(5, TimeUnit.SECONDS));
                assertInstanceOf(SecurityException.class, problem.get());
                assertNull(edge.get());
                assertEquals(0, group.events().tryPublishReliableSystem(
                        "scope-fence", "no edge").orElseThrow().accepted());
                assertSharedQuotaRetired(runtime);
            } finally {
                observer.stop();
                publisher.stop();
            }
        }
    }

    /**
     * Handler latches fire inside the active mailbox envelope; shared quota
     * includes that envelope until its try-with-resources close. The existing
     * latch and pending/sequence assertions still establish publication order.
     * Require actual zero quota before continuing, with a bounded wait for
     * physical retirement rather than assuming a latch means turn teardown.
     */
    private static void assertSharedQuotaRetired(ActorRuntime runtime)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (runtime.sharedMemoryBytes() != 0L && System.nanoTime() - deadline < 0L) {
            Thread.sleep(1);
        }
        assertEquals(0L, runtime.sharedMemoryBytes(),
                "all publication and active-mailbox reservations must retire");
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
