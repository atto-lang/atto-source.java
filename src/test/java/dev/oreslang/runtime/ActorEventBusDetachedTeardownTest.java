package dev.oreslang.runtime;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Regression for the two-phase detach-then-physical-close EventBus fence.
 *  Tests are authored only; a zero-step GitHub job is NOT_EXECUTED. */
@Timeout(40)
final class ActorEventBusDetachedTeardownTest {
    @Test
    void leaveCannotCompleteReadCallbacksUnderMembershipOrTopicLocks()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var join = group.joinCapability();
            var bus = group.events();
            bus.defineTopic("leave-callback", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            Object membershipLock = field(ActorRuntime.class, "groupMembershipLock").get(runtime);

            CountDownLatch callback = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicBoolean underMembership = new AtomicBoolean();
            AtomicBoolean underDelivery = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorEventBus.Subscription<Integer>> old = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    join.join();
                    var subscription = bus.<Integer>subscribe("leave-callback");
                    old.set(subscription);
                    Object deliveryLock = subscriptionDeliveryLock(subscription);
                    subscription.readAsync().whenCompleteRuntime((ignored, terminal) -> {
                        underMembership.set(Thread.holdsLock(membershipLock));
                        underDelivery.set(Thread.holdsLock(deliveryLock));
                        callback.countDown();
                    });
                    group.leaveCurrent();
                    assertTrue(subscription.closed(),
                            "old incarnation must be fenced before callback returns");
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    finished.countDown();
                }
            });
            actor.send("leave");
            assertTrue(callback.await(10, TimeUnit.SECONDS));
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertFalse(underMembership.get());
            assertFalse(underDelivery.get());
            assertNotNull(old.get());
            assertTrue(old.get().closed());
            assertEquals(0, group.memberCount());

            group.close();
            actor.stop();
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void groupCloseSettlesPendingReadOutsideDefinitionAndDeliveryLocks()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var join = group.joinCapability();
            var bus = group.events();
            bus.defineTopic("close-callback", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            Object definitionLock = field(ActorEventBus.class, "definitionLock").get(bus);
            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch callback = new CountDownLatch(1);
            AtomicBoolean underDefinition = new AtomicBoolean();
            AtomicBoolean underDelivery = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    join.join();
                    var sub = bus.<Integer>subscribe("close-callback");
                    Object deliveryLock = subscriptionDeliveryLock(sub);
                    sub.readAsync().whenCompleteRuntime((ignored, terminal) -> {
                        underDefinition.set(Thread.holdsLock(definitionLock));
                        underDelivery.set(Thread.holdsLock(deliveryLock));
                        callback.countDown();
                    });
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    subscribed.countDown();
                }
            });
            actor.send("subscribe");
            assertTrue(subscribed.await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            group.close();
            assertTrue(callback.await(10, TimeUnit.SECONDS));
            assertFalse(underDefinition.get());
            assertFalse(underDelivery.get());
            assertEquals(0, group.memberCount());
            assertTrue(actor.isAlive(),
                    "cooperation group close must not kill ordinary actors");
            actor.stop();
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void scopedPublisherLeaveRevokesOldChannelBeforeSameActorRejoins()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var grant = group.joinCapability();
            var bus = group.events();
            bus.defineTopic("scoped-leave", ActorEventBus.DeliveryPolicy.RELIABLE, 2);
            var publisher = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            AtomicReference<ActorEventBus.Subscription<String>> first =
                    new AtomicReference<>();
            AtomicReference<ActorEventBus.Subscription<String>> second =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch firstReady = new CountDownLatch(1);
            CountDownLatch secondReady = new CountDownLatch(1);

            var observer = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    if ("first".equals(message)) {
                        first.set(bus.<String>subscribePublisher("scoped-leave", publisher));
                    } else if ("second".equals(message)) {
                        second.set(bus.<String>subscribePublisher("scoped-leave", publisher));
                        assertNotSame(first.get(), second.get(),
                                "old publisher scope cannot become live after rejoin");
                        assertTrue(first.get().closed());
                        assertThrows(IllegalStateException.class, first.get()::tryRead);
                        second.get().close();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    if ("first".equals(message)) firstReady.countDown();
                    else if ("second".equals(message)) secondReady.countDown();
                }
            });
            group.join(publisher, grant);
            group.join(observer, grant);
            observer.send("first");
            assertTrue(firstReady.await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertNotNull(first.get());

            group.leave(publisher);
            assertTrue(first.get().closed());
            group.join(publisher, grant);
            observer.send("second");
            assertTrue(secondReady.await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertNotNull(second.get());
            group.close();
            observer.stop();
            publisher.stop();
            assertTrue(observer.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(publisher.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static Object subscriptionDeliveryLock(
            ActorEventBus.Subscription<?> subscription) throws Exception {
        var topic = field(subscription.getClass(), "topic").get(subscription);
        return field(topic.getClass(), "deliveryLock").get(topic);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
