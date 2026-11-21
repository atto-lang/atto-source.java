package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual ActorRuntime/EventBus regressions, not substitutes for compiler guests.
 * Exact-head JUnit has to execute before these can count as passed tests.
 */
@Timeout(30)
final class ActorEventBusAdmissionRevocationTest {

    @Test
    void rejectedReliableAdmissionDoesNotConsumeSequenceOrPayloadBudget()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus bus = group.events();
            bus.defineTopic("sequence", ActorEventBus.DeliveryPolicy.RELIABLE, 1);

            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch firstRead = new CountDownLatch(1);
            CountDownLatch remainingRead = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorEventBus.Subscription<List<Integer>>> subscription =
                    new AtomicReference<>();
            java.util.concurrent.ConcurrentLinkedQueue<Long> observed =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();

            ActorRuntime.ActorRef<Integer> subscriber =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> {
                        try {
                            if (message == 0) {
                                join.join();
                                subscription.set(bus.subscribe("sequence"));
                            } else if (message == 1) {
                                observed.add(subscription.get().tryRead().orElseThrow().sequence());
                            } else if (message == 2) {
                                observed.add(subscription.get().tryRead().orElseThrow().sequence());
                                observed.add(subscription.get().tryRead().orElseThrow().sequence());
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            if (message == 0) subscribed.countDown();
                            if (message == 1) firstRead.countDown();
                            if (message == 2) remainingRead.countDown();
                        }
                    });

            subscriber.send(0);
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));

            ActorEventBus.PublishReceipt first =
                    bus.publishSystem("sequence", new ArrayList<>(List.of(1)));
            ActorEventBus.PublishReceipt second =
                    bus.publishSystem("sequence", new ArrayList<>(List.of(2)));
            assertTrue(first.completion().isDone());
            assertFalse(second.completion().isDone(),
                    "capacity-one channel holds the second reliable write");
            long priorBytes = runtime.sharedMemoryBytes();

            assertThrows(ActorEventBus.EventBackpressureException.class,
                    () -> bus.publishSystem("sequence", new ArrayList<>(List.of(99))),
                    "third outstanding event must be refused without admission");
            assertEquals(priorBytes, runtime.sharedMemoryBytes(),
                    "rejected RELIABLE event must not retain its payload reservation");

            subscriber.send(1);
            assertTrue(firstRead.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            ActorEventBus.PublishReceipt third =
                    bus.publishSystem("sequence", new ArrayList<>(List.of(3)));

            subscriber.send(2);
            assertTrue(remainingRead.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(List.of(1L, 2L, 3L), List.copyOf(observed),
                    "the refused publication must not create a sequence gap");
            assertTrue(second.completion().isDone());
            assertTrue(third.completion().isDone());
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "every accepted delivery must release its frozen payload");

            subscriber.stop();
            assertTrue(subscriber.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void directGroupLeaveRejoinDetachesOldSubscriptionBeforeNewAdmission()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus bus = group.events();
            bus.defineTopic("direct", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch cycled = new CountDownLatch(1);
            CountDownLatch consumed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorEventBus.Subscription<List<Integer>>> previous =
                    new AtomicReference<>();
            AtomicReference<ActorEventBus.Subscription<List<Integer>>> current =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> actor =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> {
                        try {
                            if (message == 0) {
                                join.join();
                                previous.set(bus.subscribe("direct"));
                            } else if (message == 1) {
                                group.leaveCurrent();
                                assertTrue(previous.get().closed());
                                assertThrows(SecurityException.class,
                                        () -> bus.subscribe("direct"),
                                        "revoked actors cannot resubscribe");
                                assertThrows(SecurityException.class,
                                        () -> bus.publish("direct", List.of(99)),
                                        "revoked actors cannot publish");
                                join.join();
                                current.set(bus.subscribe("direct"));
                                assertNotSame(previous.get(), current.get(),
                                        "new membership must not inherit the closed subscription");
                                assertEquals(0, current.get().pending());
                            } else if (message == 2) {
                                ActorEventBus.Event<List<Integer>> event =
                                        current.get().tryRead().orElseThrow();
                                assertEquals(List.of(2), event.value());
                                group.leaveCurrent();
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            if (message == 0) subscribed.countDown();
                            if (message == 1) cycled.countDown();
                            if (message == 2) consumed.countDown();
                        }
                    });

            actor.send(0);
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            bus.publishSystem("direct", new ArrayList<>(List.of(1)));
            assertTrue(runtime.sharedMemoryBytes() > 0);

            actor.send(1);
            assertTrue(cycled.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "old unread subscription must release retained event payload");

            bus.publishSystem("direct", new ArrayList<>(List.of(2)));
            actor.send(2);
            assertTrue(consumed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(0L, runtime.sharedMemoryBytes());
            assertEquals(0, group.memberCount());
            assertTrue(actor.isAlive(), "leaving a cooperation group must not stop actors");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void groupShutdownFailsBufferedAndPendingReliableWritesWithoutKillingMembers()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus bus = group.events();
            bus.defineTopic("shutdown", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            CountDownLatch ready = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> actor =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> {
                        try {
                            join.join();
                            bus.subscribe("shutdown");
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            ready.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));

            ActorEventBus.PublishReceipt buffered =
                    bus.publishSystem("shutdown", new ArrayList<>(List.of(1, 2, 3)));
            ActorEventBus.PublishReceipt waiting =
                    bus.publishSystem("shutdown", new ArrayList<>(List.of(4, 5, 6)));
            assertTrue(buffered.completion().isDone());
            assertFalse(waiting.completion().isDone());
            assertTrue(runtime.sharedMemoryBytes() > 0);

            group.close();
            assertTrue(group.closed());
            assertTrue(bus.closed());
            assertTrue(waiting.completion().isCompletedExceptionally(),
                    "pending reliable delivery must fail during close");
            assertEquals(0L, runtime.sharedMemoryBytes(),
                    "close must release both buffered and pending event reservations");
            assertThrows(IllegalStateException.class,
                    () -> bus.publishSystem("shutdown", List.of(7)));
            assertTrue(actor.isAlive(),
                    "group shutdown cannot cancel an ordinary cooperating actor");

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actorPublicationIsRejectedAfterConcurrentHostGroupShutdown()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus bus = group.events();
            bus.defineTopic("close-race", ActorEventBus.DeliveryPolicy.LATEST, 1);
            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();

            ActorRuntime.ActorRef<Integer> publisher =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> {
                        try {
                            if (message == 0) {
                                join.join();
                            } else if (message == -1) {
                                assertThrows(IllegalStateException.class,
                                        () -> bus.publish("close-race", List.of(999)),
                                        "no actor may publish after host close returns");
                            } else {
                                attempts.incrementAndGet();
                                try {
                                    bus.publish("close-race", List.of(message));
                                } catch (SecurityException | IllegalStateException expected) {
                                    rejected.incrementAndGet();
                                }
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            if (message == 0) joined.countDown();
                            if (message == -1) finished.countDown();
                        }
                    });

            publisher.send(0);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));

            // The host can close concurrently with queued actor DATA turns.
            // Either publication or revocation may linearize first.
            for (int i = 1; i <= 24; i++) publisher.send(i);
            group.close();
            publisher.send(-1);
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(24, attempts.get());
            assertTrue(rejected.get() >= 0 && rejected.get() <= 24);
            assertEquals(0L, runtime.sharedMemoryBytes());
            assertTrue(publisher.isAlive(),
                    "group close must never take ownership of the publisher");

            publisher.stop();
            assertTrue(publisher.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
