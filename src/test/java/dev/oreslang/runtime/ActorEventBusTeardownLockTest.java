package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorEventBusTeardownLockTest {
    @Test
    void leaveDetachesBeforePendingReadCallbacksAndReleasesMembershipLock() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var capability = group.joinCapability();
            var bus = group.events();
            bus.defineTopic("leave-locks", ActorEventBus.DeliveryPolicy.RELIABLE, 1);

            Object membershipLock = privateField(ActorRuntime.class, "groupMembershipLock").get(runtime);
            var callbackRan = new CountDownLatch(1);
            var actorDone = new CountDownLatch(1);
            var underMembership = new AtomicBoolean();
            var underDelivery = new AtomicBoolean();
            var failure = new AtomicReference<Throwable>();

            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    capability.join();
                    var subscription = bus.<Integer>subscribe("leave-locks");
                    Object deliveryLock = deliveryLock(subscription);
                    var read = subscription.readAsync();
                    read.whenCompleteRuntime((ignored, terminal) -> {
                        underMembership.set(Thread.holdsLock(membershipLock));
                        underDelivery.set(Thread.holdsLock(deliveryLock));
                        callbackRan.countDown();
                    });

                    group.leaveCurrent();
                    assertTrue(subscription.closed());
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    actorDone.countDown();
                }
            });

            actor.send("leave");
            assertTrue(callbackRan.await(5, TimeUnit.SECONDS));
            assertTrue(actorDone.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertFalse(
                    underMembership.get(),
                    "channel/Future callbacks must run after groupMembershipLock is released");
            assertFalse(
                    underDelivery.get(),
                    "channel/Future callbacks must run after topic deliveryLock is released");
            assertEquals(0, group.memberCount());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void groupClosePublishesPendingReadFailureAfterDefinitionAndDeliveryLocks() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var capability = group.joinCapability();
            var bus = group.events();
            bus.defineTopic("group-close-locks", ActorEventBus.DeliveryPolicy.RELIABLE, 1);

            Object definitionLock = privateField(ActorEventBus.class, "definitionLock").get(bus);
            var subscribed = new CountDownLatch(1);
            var callbackRan = new CountDownLatch(1);
            var underDefinition = new AtomicBoolean();
            var underDelivery = new AtomicBoolean();
            var deliveryLock = new AtomicReference<Object>();

            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                capability.join();
                var subscription = bus.<Integer>subscribe("group-close-locks");
                deliveryLock.set(deliveryLock(subscription));
                subscription.readAsync().whenCompleteRuntime((ignored, terminal) -> {
                    underDefinition.set(Thread.holdsLock(definitionLock));
                    underDelivery.set(Thread.holdsLock(deliveryLock.get()));
                    callbackRan.countDown();
                });
                subscribed.countDown();
            });

            actor.send("subscribe");
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));

            group.close();

            assertTrue(callbackRan.await(5, TimeUnit.SECONDS));
            assertFalse(
                    underDefinition.get(),
                    "group close callbacks must run after definitionLock is released");
            assertFalse(
                    underDelivery.get(),
                    "group close callbacks must run after topic deliveryLock is released");
            assertEquals(0, group.memberCount());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Object deliveryLock(ActorEventBus.Subscription<?> subscription)
            throws Exception {
        Field topicField = privateField(subscription.getClass(), "topic");
        Object topic = topicField.get(subscription);
        return privateField(topic.getClass(), "deliveryLock").get(topic);
    }

    private static Field privateField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
